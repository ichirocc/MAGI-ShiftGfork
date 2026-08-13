package com.magi.app.v6

/**
 * ViolationReport 生成の単一入口（app-base MirrorCore 型に整合）。
 *
 * - Android 本番の [ViolationReport] は MirrorCore の data class（weightedScore: Double）。
 * - エンジン CI 用 domain の of() / Long weighted はここでは使わない。
 * - 差分ホットパスは hard 優先。weightedScore は packed 由来の近似。
 */
object ViolationReports {

    /** 差分・スコアのみ（表示マップは空）。SA ホットパス用。 */
    fun fromDeltaPacked(
        hard: Int,
        soft: Int,
        packedScore: Long,
    ): ViolationReport {
        val h = hard.coerceAtLeast(0)
        val s = soft.coerceAtLeast(0)
        return ViolationReport(
            violations = emptyMap(),
            needViolations = emptyMap(),
            countViolations = emptyMap(),
            breakdown = emptyMap(),
            total = h + s,
            hard = h,
            soft = s,
            weightedScore = packedScore.toDouble(),
        )
    }

    /** hard/soft のみ（業務 weighted は soft をそのまま近似）。 */
    fun fromHardSoft(hard: Int, soft: Int, breakdown: Map<String, Int> = emptyMap()): ViolationReport {
        val h = hard.coerceAtLeast(0)
        val s = soft.coerceAtLeast(0)
        return ViolationReport(
            violations = emptyMap(),
            needViolations = emptyMap(),
            countViolations = emptyMap(),
            breakdown = breakdown,
            total = h + s,
            hard = h,
            soft = s,
            weightedScore = s.toDouble(),
        )
    }
}
