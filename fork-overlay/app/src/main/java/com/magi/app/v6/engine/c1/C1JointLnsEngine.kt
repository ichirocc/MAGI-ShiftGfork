package com.magi.app.v6.engine.c1
import com.magi.app.v6.dayDemand
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.C1PrecisionMoves
import com.magi.app.v6.engine.Move
import com.magi.app.v6.engine.SearchSessionFull
import com.magi.app.v6.engine.ShiftKinds
import com.magi.app.v6.engine.TransitionMode
import com.magi.app.v6.engine.adapters.ScheduleImprover
import com.magi.app.v6.engine.buildSingleMove
import com.magi.app.v6.engine.c1.C1SuffixLowerBound
import java.util.Random

/**
 * ## C1 Joint LNS 再設計（エンジン内）
 *
 * main の C1JointLnsPolish の構造を **Move 契約** に載せ替えたもの。
 *
 * 旧リスク: 深い探索中に直接 schedule 書き換え + 評価器ドリフト。
 * 新:
 * - すべての候補は [Move] 化し [SearchSessionFull.tryTransition(STRICT)] のみ
 * - Goal = 不足している (staff,day,targetShift) または coverage 日
 * - 近傍: Direct / SameDaySwap / Rotate3 / SelfDaySwap / CrossDayTransfer / C1Precision 日次
 * - Beam + 再スタート + patience（main Config に準拠）
 * - hardDebt 許容は **しない**（STRICT のみ）。Bridge が必要なら ValidBridge を G4 に委譲
 *
 * 完全な suffix-DP 下界は main の MAX_EXACT_LOWER_BOUND_CELLS ロジックを
 * 後で LowerBound フックとして差せる。まずは残差駆動のビームで精度を確保。
 */
class C1JointLnsEngine(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val rng: Random = Random(0xC1A11L),
    private val config: Config = Config(),
) : ScheduleImprover {

    data class Config(
        val targetReductionPercent: Int = 50,
        val beamWidth: Int = 16,
        val maxDepth: Int = 4,
        val maxRestarts: Int = 3,
        val maxGoals: Int = 24,
        val maxMovesPerGoal: Int = 16,
        val maxMillis: Long = 8_000L,
        val patienceMs: Long = 4_000L,
    )

    private data class Goal(val staff: Int, val day: Int, val target: Int, val weight: Int)

    override fun improve(schedule: Array<IntArray>, deadlineMs: Long): Boolean {
        val end = minOf(deadlineMs, System.currentTimeMillis() + config.maxMillis)
        val session = SearchSessionFull(
            problem,
            SearchSessionFull.deepCopy(schedule),
            evaluate,
            better,
        )
        val rootC1 = c1Of(session.bestReport)
        if (rootC1 <= 0) return false
        val targetC1 = (rootC1 * (100 - config.targetReductionPercent) / 100).coerceAtLeast(0)

        var bestC1 = rootC1
        var lastImproveAt = System.currentTimeMillis()
        var improvedAny = false

        repeat(config.maxRestarts) { _ ->
            if (System.currentTimeMillis() >= end) return@repeat
            if (config.patienceMs > 0 &&
                System.currentTimeMillis() - lastImproveAt >= config.patienceMs
            ) return@repeat

            // 再スタート: best から current を同期（replaceBest 済み状態を利用）
            val goals = collectGoals(session.current, session.currentReport)
            if (goals.isEmpty()) {
                // 日次 C1Precision フォールバック
                for (d in 0 until problem.T) {
                    if (System.currentTimeMillis() >= end) break
                    val need = problem.dayDemand.getOrElse(d) { 0 }
                    val m = C1PrecisionMoves.fillCoverageDay(session, problem, d, need, rng)
                        ?: C1PrecisionMoves.trimCoverageDay(session, problem, d, need, rng)
                        ?: continue
                    if (session.tryTransition(m, TransitionMode.STRICT) !is com.magi.app.v6.engine.TransitionResult.Rejected) {
                        improvedAny = true
                        lastImproveAt = System.currentTimeMillis()
                    }
                }
                return@repeat
            }

            for (goal in goals.take(config.maxGoals)) {
                if (System.currentTimeMillis() >= end) break
                val moves = expandMoves(session, goal).shuffled(rng).take(config.maxMovesPerGoal)
                for (move in moves) {
                    if (System.currentTimeMillis() >= end) break
                    val before = c1Of(session.bestReport)
                    session.tryTransition(move, TransitionMode.STRICT)
                    val after = c1Of(session.bestReport)
                    if (after < before) {
                        improvedAny = true
                        lastImproveAt = System.currentTimeMillis()
                        bestC1 = after
                        if (bestC1 <= targetC1) return true
                    }
                }
                // 深さ方向: 同一 goal 周辺を C1Precision で1手
                val m2 = C1PrecisionMoves.fillCoverageDay(
                    session, problem, goal.day,
                    problem.dayDemand.getOrElse(goal.day) { 0 }, rng,
                )
                if (m2 != null) {
                    val before = c1Of(session.bestReport)
                    session.tryTransition(m2, TransitionMode.STRICT)
                    if (c1Of(session.bestReport) < before) {
                        improvedAny = true
                        lastImproveAt = System.currentTimeMillis()
                    }
                }
            }
        }

        // 呼び出し元 schedule に best を書き戻す
        if (improvedAny || better(session.bestReport, evaluate(schedule))) {
            val b = session.best
            for (i in schedule.indices) {
                System.arraycopy(b[i], 0, schedule[i], 0, b[i].size)
            }
            return true
        }
        return false
    }

    private fun c1Of(r: ViolationReport): Int =
        r.breakdown["c1"] ?: r.breakdown["covU"] ?: 0

    private fun collectGoals(sch: Array<IntArray>, @Suppress("UNUSED_PARAMETER") report: ViolationReport): List<Goal> {
        val goals = ArrayList<Goal>()
        val deficits = C1SuffixLowerBound.dayDeficits(problem, sch)
        val totalLb = deficits.sum()
        if (totalLb <= 0) return emptyList()
        for (d in 0 until problem.T) {
            val deficit = deficits[d]
            if (deficit <= 0) continue
            val suffix = C1SuffixLowerBound.suffixDeficitFrom(deficits, d)
            for (s in 0 until problem.S) {
                if (problem.wishLocked(s, d)) continue
                if (ShiftKinds.isRest(sch[s][d])) {
                    val alts = ShiftKinds.preferOnDuty(problem, s)
                    if (alts.isNotEmpty()) {
                        // 不足日 × suffix 下界で優先（深い不足帯を先に）
                        goals += Goal(s, d, alts[0], deficit * 10 + suffix)
                    }
                }
            }
        }
        return goals.sortedByDescending { it.weight }
    }

    private fun expandMoves(session: SearchSessionFull, g: Goal): List<Move> {
        val out = ArrayList<Move>()
        val sch = session.current
        // Direct
        buildSingleMove(session, problem, g.staff, g.day, g.target, "c1lns.direct")?.let { out += it }
        // Same-day swap
        for (o in 0 until problem.S) {
            if (o == g.staff) continue
            if (problem.wishLocked(o, g.day) || problem.wishLocked(g.staff, g.day)) continue
            val a = sch[g.staff][g.day]
            val b = sch[o][g.day]
            if (a == b) continue
            if (!problem.canDo(g.staff, b) || !problem.canDo(o, a)) continue
            out += Move(
                session.version,
                intArrayOf(g.staff, g.day, b, o, g.day, a),
                "c1lns",
                "c1lns.swap2",
            )
            if (out.size >= config.maxMovesPerGoal) break
        }
        // Rotate3
        if (out.size < config.maxMovesPerGoal) {
            C1PrecisionMoves.dayTripleRepair(session, problem, g.day, rng)?.let { out += it }
        }
        // Self day swap
        for (d2 in 0 until problem.T) {
            if (d2 == g.day) continue
            if (problem.wishLocked(g.staff, d2) || problem.wishLocked(g.staff, g.day)) continue
            val a = sch[g.staff][g.day]
            val b = sch[g.staff][d2]
            if (a == b) continue
            if (!problem.canDo(g.staff, b) || !problem.canDo(g.staff, a)) continue
            out += Move(
                session.version,
                intArrayOf(g.staff, g.day, b, g.staff, d2, a),
                "c1lns",
                "c1lns.selfswap",
            )
            if (out.size >= config.maxMovesPerGoal) break
        }
        return out
    }
}
