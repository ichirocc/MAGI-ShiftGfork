package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import java.util.Random

/**
 * main 系 RSI / find*Fix を参考にした高速・高精度制約研磨。
 *
 * 高速化:
 * - HARD 残差を先に枯らす優先キュー
 * - キーごとに候補をリング生成（全盤シャッフルしない）
 * - 残差が 0 になったキーは即スキップ
 * - 同一手の再試行をハッシュで抑制
 *
 * 高精度化:
 * - STRICT のみ（betterReport 改善のみ受理）
 * - 受理後も focus 残差が減らない連続失敗でフォーカス切替（RSI）
 * - 候補を複数生成し、先に残差を減らせる手から試す
 */
class FastConstraintPolish(
    private val problem: Problem,
    private val rng: Random = Random(0L),
    private val candidatesPerWave: Int = 8,
    private val maxFailsPerKey: Int = 20,
    private val maxWaves: Int = 48,
) {
    private val hardKeys = listOf(
        "covU", "shiftU", "exact", "illegal", "lockBreak", "c3n", "c1", "groupViol",
    )
    private val softKeys = listOf(
        "covO", "c2", "range", "low", "high", "wish", "pref",
        "bal", "fair", "weekly", "c3", "c3m", "c3mn", "apt",
    )
    private val focusWeight = mapOf(
        "c3n" to 100.0, "covU" to 100.0, "shiftU" to 100.0, "groupViol" to 100.0,
        "c1" to 80.0, "exact" to 90.0, "illegal" to 100.0, "lockBreak" to 100.0,
        "low" to 70.0, "high" to 45.0, "pref" to 60.0, "wish" to 60.0,
        "c3" to 40.0, "c3m" to 35.0, "c3mn" to 35.0, "weekly" to 30.0,
        "covO" to 25.0, "c2" to 25.0, "apt" to 20.0, "bal" to 10.0, "fair" to 10.0,
        "range" to 30.0,
    )

    fun polishAll(session: SearchSessionFull, deadlineMs: Long): Int {
        var improved = 0
        var waves = 0
        val excluded = HashSet<String>()
        while (waves++ < maxWaves && System.currentTimeMillis() < deadlineMs) {
            val focus = pickFocus(session.currentReport, excluded) ?: break
            val gained = polishKey(session, focus, deadlineMs)
            improved += gained
            if (gained == 0) {
                excluded.add(focus)
                // 全部 exclude したらリセットして別シード相当
                if (excluded.size >= hardKeys.size + softKeys.size) excluded.clear()
            } else {
                excluded.clear()
            }
            if (session.currentReport.hard == 0 && softResidual(session.currentReport) == 0) break
        }
        return improved
    }

    fun pickFocus(report: ViolationReport, exclude: Set<String> = emptySet()): String? {
        var best: String? = null
        var bestScore = 0.0
        fun consider(keys: List<String>) {
            for (k in keys) {
                if (k in exclude) continue
                val v = report.breakdown[k] ?: 0
                if (v <= 0) continue
                val s = v * (focusWeight[k] ?: 1.0)
                if (s > bestScore) {
                    bestScore = s
                    best = k
                }
            }
        }
        consider(hardKeys)
        if (best != null) return best
        consider(softKeys)
        return best
    }

    private fun softResidual(r: ViolationReport): Int =
        softKeys.sumOf { r.breakdown[it] ?: 0 }

    private fun polishKey(session: SearchSessionFull, key: String, deadlineMs: Long): Int {
        var improved = 0
        var fails = 0
        val seen = HashSet<Int>()
        while (fails < maxFailsPerKey && System.currentTimeMillis() < deadlineMs) {
            val beforeKey = session.currentReport.breakdown[key] ?: 0
            if (beforeKey <= 0) break

            var acceptedThisWave = false
            var genGuard = 0
            while (genGuard++ < candidatesPerWave * 3 && !acceptedThisWave &&
                System.currentTimeMillis() < deadlineMs
            ) {
                val move = ConstraintPolishers.propose(key, session, problem, rng) ?: break
                val h = hashMove(move)
                if (h in seen) continue
                seen.add(h)
                // version 整合
                if (move.baseVersion != session.version) continue
                val r = session.tryTransition(move, TransitionMode.STRICT)
                if (r is TransitionResult.AcceptedBest || r is TransitionResult.AcceptedCurrent) {
                    improved++
                    acceptedThisWave = true
                    val afterKey = session.currentReport.breakdown[key] ?: 0
                    // 残差が増えた受理は稀（他目的改善）→ 失敗カウントに寄せてフォーカス切替を促す
                    if (afterKey >= beforeKey) fails++
                    else fails = 0
                }
            }
            if (!acceptedThisWave) fails++
            if (seen.size > 256) seen.clear()
        }
        return improved
    }

    private fun hashMove(m: Move): Int {
        var h = 1
        for (x in m.writes) h = 31 * h + x
        return h
    }
}

/**
 * G3 / Scheduler から呼ぶ入口。旧 ConstraintPolishService を高速版に置換。
 */
class ConstraintPolishService(
    private val problem: Problem,
    private val rng: Random = Random(0L),
) {
    private val engine = ConstraintPolishEngine(problem, rng)
    private val fast = FastConstraintPolish(problem, rng)

    fun polishAll(
        session: SearchSessionFull,
        deadlineMs: Long,
    ): Int {
        // 断捨離: Engine 一本化（重複 VNS/ALNS/Fast カスケードを廃止）
        // 精度は Engine の残差フォーカス + C1 日次一括手で担保
        // 速度は評価回数削減（多セル1Move）と単一経路で担保
        var n = engine.run(session, deadlineMs)
        // 残り時間で C1 日次一括を短く追加
        val now = System.currentTimeMillis()
        if (now < deadlineMs && session.bestReport.hard > 0) {
            n += c1Burst(session, deadlineMs)
        }
        return n
    }

    private fun c1Burst(session: SearchSessionFull, deadlineMs: Long): Int {
        var n = 0
        var guard = 0
        while (System.currentTimeMillis() < deadlineMs && guard++ < 80) {
            val d = rng.nextInt(problem.T)
            val need = problem.dayDemand[d]
            val move = C1PrecisionMoves.fillCoverageDay(session, problem, d, need, rng)
                ?: C1PrecisionMoves.trimCoverageDay(session, problem, d, need, rng)
                ?: C1PrecisionMoves.fillGroupDay(session, problem, d, rng)
                ?: C1PrecisionMoves.dayTripleRepair(session, problem, d, rng)
                ?: continue
            if (move.baseVersion != session.version) continue
            if (session.tryTransition(move, TransitionMode.STRICT) !is TransitionResult.Rejected) n++
        }
        return n
    }

    fun pickFocus(report: ViolationReport): String? = fast.pickFocus(report)
}
