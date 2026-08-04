package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * G3 など大量変更後に best だけを差し替える。
 * tryTransition ではセル数が多すぎる場合の正式な引き継ぎ。
 *
 * 新 session を返す（version は 0 から）。
 * better でない候補は無視して previous を返す。
 */
fun replaceBestIfBetter(
    problem: Problem,
    previous: SearchSessionFull,
    candidate: Array<IntArray>,
    candidateReport: ViolationReport,
    evaluate: (Array<IntArray>) -> ViolationReport,
    better: (ViolationReport, ViolationReport) -> Boolean,
): SearchSessionFull {
    if (!better(candidateReport, previous.bestReport)) return previous
    return SearchSessionFull(problem, candidate, evaluate, better)
}

/**
 * 2 盤面の差分を Move にできるときだけ STRICT 適用（小差分用）。
 * 差分セルが 64 超なら null（replaceBestIfBetter を使え）。
 */
fun diffAsMove(
    board: BoardView,
    from: Array<IntArray>,
    to: Array<IntArray>,
    family: String = "diff",
): Move? {
    val writes = ArrayList<Int>()
    val sMax = minOf(from.size, to.size)
    for (i in 0 until sMax) {
        val a = from[i]
        val b = to[i]
        val tMax = minOf(a.size, b.size)
        for (j in 0 until tMax) {
            if (a[j] != b[j]) {
                writes.add(i)
                writes.add(j)
                writes.add(b[j])
            }
        }
    }
    if (writes.isEmpty() || writes.size > 64 * 3) return null
    return Move(board.version, writes.toIntArray(), family, "diff_apply")
}
