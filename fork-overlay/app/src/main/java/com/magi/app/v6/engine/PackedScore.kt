package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport
import kotlin.math.roundToLong

object PackedScore {
    const val HARD_UNIT = 1_000_000_000L

    fun of(report: ViolationReport): Long {
        val softPart = report.weightedScore.roundToLong().coerceAtLeast(0L) % HARD_UNIT
        return report.hard.toLong() * HARD_UNIT + softPart
    }

    fun of(hard: Int, weightedSoft: Long): Long {
        val w = weightedSoft.coerceAtLeast(0L) % HARD_UNIT
        return hard.toLong() * HARD_UNIT + w
    }
}
