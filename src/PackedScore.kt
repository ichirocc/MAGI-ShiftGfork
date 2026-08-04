package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport

/**
 * SA/LAHC 用パックドスコア。
 * Evaluator は weightedScore = hard * HARD_UNIT + weightedSoft を返すため、
 * そのまま辞書式パックとして使う（hard の二重計上をしない）。
 */
object PackedScore {
    const val HARD_UNIT = 1_000_000_000L

    fun of(report: ViolationReport): Long = report.weightedScore

    fun of(hard: Int, weightedSoft: Long): Long {
        val w = weightedSoft.coerceAtLeast(0L)
        check(w < HARD_UNIT) { "weightedSoft=$w >= HARD_UNIT" }
        return hard.toLong() * HARD_UNIT + w
    }
}
