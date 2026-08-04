package com.magi.app.v6.engine

/**
 * 評価重みの単一ソース。
 * 本番起動時に MirrorKeys / 重み表で entries を埋める。
 *
 * ログ（[WeightAuditLog]）で「重みが適切か」を検証できるようにする。
 */
data class WeightEntry(val key: String, val weight: Double, val hard: Boolean)

object ObjectiveWeightsSource {
    var entries: List<WeightEntry> = emptyList()

    /** 上流 V6 と整合する既定 SOFT 重み（install 前・単体テスト用） */
    val DEFAULT_SOFT: Map<String, Double> = mapOf(
        "c1" to 15.0,
        "c2" to 1.0,
        "c3" to 3.0,
        "c3m" to 2.0,
        "c3mn" to 15.0,
        "c41" to 1.0,
        "c42" to 1.0,
        "low" to 90.0,
        "high" to 45.0,
        "range" to 1.0,
        "apt" to 1.0,
        "fair" to 1.0,
        "bal" to 1.0,
        "weekly" to 1.0,
        "covO" to 1.0,
        "wish" to 1.0,
        "pref" to 1.0,
    )

    val DEFAULT_HARD: List<String> = listOf(
        "groupViol", "c3n", "covU", "shiftU", "pref", "exact", "illegal", "lockBreak", "c1",
    )

    fun hash(): String {
        val s = entries.joinToString("|") { "${it.key}=${it.weight}:${it.hard}" }
        return s.hashCode().toUInt().toString(16)
    }

    fun softWeight(key: String): Double =
        entries.firstOrNull { it.key == key && !it.hard }?.weight
            ?: DEFAULT_SOFT[key]
            ?: 1.0

    fun isHard(key: String): Boolean =
        entries.firstOrNull { it.key == key }?.hard == true
            || (entries.isEmpty() && key in DEFAULT_HARD)

    fun hardKeys(): List<String> =
        entries.filter { it.hard }.map { it.key }.ifEmpty { DEFAULT_HARD }

    fun softEntries(): List<WeightEntry> =
        if (entries.any { !it.hard }) entries.filter { !it.hard }
        else DEFAULT_SOFT.map { (k, w) -> WeightEntry(k, w, hard = false) }

    fun requireSyncedWith(expectedHash: String) {
        check(hash() == expectedHash) {
            "ObjectiveWeights hash mismatch: got ${hash()} expected $expectedHash"
        }
    }

    fun isReady(): Boolean = entries.isNotEmpty()

    fun install(hardKeys: Collection<String>, softWeights: Map<String, Double>) {
        entries = hardKeys.map { WeightEntry(it, 0.0, hard = true) } +
            softWeights.map { (k, w) -> WeightEntry(k, w, hard = false) }
    }

    /** install が無い環境でもログと比較可能にする */
    fun ensureDefaults() {
        if (entries.isEmpty()) {
            install(DEFAULT_HARD, DEFAULT_SOFT)
        }
    }
}
