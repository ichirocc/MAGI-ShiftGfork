package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import java.util.Random

/**
 * Path Relinking（Glover / Laguna）:
 * elite A から elite B へ、差分配列を段階的に埋めて中間解を STRICT 評価する。
 *
 * - 差セルだけを順序付けて適用（lock 除外）
 * - 各ステップで betterReport なら best / elite 候補に載せる
 * - 出力は STRICT 改善のみ（ValidBridge は G4.considerBridge 側）
 */
class PathRelinker(
    private val problem: Problem,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val rng: Random = Random(0L),
) {
    data class Result(
        val improvedBest: Boolean,
        val intermediateAccepted: Int,
        val bestSchedule: Array<IntArray>?,
        val bestReport: ViolationReport?,
    )

    /**
     * @param session 現在の探索セッション（best 更新に使用）
     * @param guide 目標 elite 盤面
     */
    fun relink(
        session: SearchSessionFull,
        guide: Array<IntArray>,
        deadlineMs: Long,
        maxSteps: Int = 64,
    ): Result {
        val start = session.best
        val diffs = ArrayList<IntArray>() // [s,d, targetSh]
        for (s in 0 until problem.S) {
            for (d in 0 until problem.T) {
                if (problem.wishLocked(s, d)) continue
                val from = start[s][d]
                val to = guide[s][d]
                if (from == to) continue
                if (!problem.canDo(s, to)) continue
                diffs.add(intArrayOf(s, d, to))
            }
        }
        if (diffs.isEmpty()) {
            return Result(false, 0, null, null)
        }
        // ランダム順 + 希望一致を先に
        diffs.sortWith(
            compareByDescending<IntArray> { cell ->
                val s = cell[0]; val d = cell[1]; val sh = cell[2]
                if (problem.preferred[s][d] == sh) 2 else 0
            }.thenBy { rng.nextInt() },
        )

        var accepted = 0
        var improvedBest = false
        var step = 0
        val limit = minOf(maxSteps, diffs.size)
        while (step < limit && System.currentTimeMillis() < deadlineMs) {
            val cell = diffs[step++]
            val s = cell[0]
            val d = cell[1]
            val sh = cell[2]
            // 既に一致していればスキップ
            if (session.current[s][d] == sh) continue
            val move = buildSingleMove(session, problem, s, d, sh, "relink") ?: continue
            if (move.baseVersion != session.version) continue
            val r = session.tryTransition(move, TransitionMode.STRICT)
            if (r is TransitionResult.AcceptedBest) {
                accepted++
                improvedBest = true
            } else if (r is TransitionResult.AcceptedCurrent) {
                accepted++
            }
            // 途中で guide より良い辞書式になれば継続価値あり
        }

        // 最終 best を返す
        return Result(
            improvedBest = improvedBest,
            intermediateAccepted = accepted,
            bestSchedule = SearchSessionFull.deepCopy(session.best),
            bestReport = session.bestReport,
        )
    }

    /**
     * elite 集合からペアを選び複数回 relink。
     */
    fun relinkElites(
        session: SearchSessionFull,
        elites: List<EliteEntry>,
        deadlineMs: Long,
        maxPairs: Int = 6,
    ): Int {
        if (elites.size < 2) return 0
        var total = 0
        val pairs = minOf(maxPairs, elites.size * (elites.size - 1) / 2)
        var tried = 0
        while (tried < pairs && System.currentTimeMillis() < deadlineMs) {
            val i = rng.nextInt(elites.size)
            var j = rng.nextInt(elites.size)
            if (i == j) j = (j + 1) % elites.size
            val guide = elites[j].schedule
            val r = relink(session, guide, deadlineMs, maxSteps = 48)
            total += r.intermediateAccepted
            tried++
        }
        return total
    }
}
