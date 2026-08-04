package com.magi.app.v6.engine.domain_ref

/**
 * 辞書式目的の評価結果。
 * 比較は [betterReport]: hard → weightedScore → total。
 */
data class ViolationReport(
    val hard: Int = 0,
    val soft: Int = 0,
    val total: Int = 0,
    val weightedScore: Long = 0L,
    val breakdown: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val HARD_UNIT: Long = 1_000_000_000L

        fun of(
            hard: Int,
            soft: Int,
            breakdown: Map<String, Int> = emptyMap(),
            weightedScore: Long = hard * HARD_UNIT + soft.toLong().coerceAtLeast(0L),
        ): ViolationReport {
            val h = hard.coerceAtLeast(0)
            val s = soft.coerceAtLeast(0)
            return ViolationReport(
                hard = h,
                soft = s,
                total = h + s,
                weightedScore = weightedScore,
                breakdown = breakdown,
            )
        }

        val EMPTY: ViolationReport = of(0, 0)
    }
}

data class MirrorLog(
    val tag: String = "",
    val message: String = "",
    val hardDelta: Int = 0,
    val softDelta: Int = 0,
)

/**
 * a が b より良い（最小化）。
 * 1) hard  2) weightedScore  3) total
 */
fun betterReport(a: ViolationReport, b: ViolationReport): Boolean {
    if (a.hard != b.hard) return a.hard < b.hard
    if (a.weightedScore != b.weightedScore) return a.weightedScore < b.weightedScore
    return a.total < b.total
}

fun equalReport(a: ViolationReport, b: ViolationReport): Boolean =
    a.hard == b.hard && a.weightedScore == b.weightedScore && a.total == b.total
