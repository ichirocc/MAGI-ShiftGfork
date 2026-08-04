package com.magi.app.v6.engine

/**
 * 評価重みの単一ソース。
 * 本番起動時に MirrorKeys / 重み表で entries を埋める。
 */
data class WeightEntry(val key: String, val weight: Double, val hard: Boolean)

object ObjectiveWeightsSource {
    var entries: List<WeightEntry> = emptyList()

    fun hash(): String {
        val s = entries.joinToString("|") { "${it.key}=${it.weight}:${it.hard}" }
        return s.hashCode().toUInt().toString(16)
    }

    fun softWeight(key: String): Double =
        entries.firstOrNull { it.key == key && !it.hard }?.weight ?: 1.0

    fun isHard(key: String): Boolean =
        entries.firstOrNull { it.key == key }?.hard == true

    fun hardKeys(): List<String> =
        entries.filter { it.hard }.map { it.key }.ifEmpty {
            listOf("covU", "shiftU", "exact", "illegal", "lockBreak", "c3n", "c1", "groupViol")
        }

    fun requireSyncedWith(expectedHash: String) {
        check(hash() == expectedHash) {
            "ObjectiveWeights hash mismatch: got ${hash()} expected $expectedHash"
        }
    }

    fun isReady(): Boolean = entries.isNotEmpty()

    /** 本番から HARD/SOFT キーと重みを一括登録 */
    fun install(hardKeys: Collection<String>, softWeights: Map<String, Double>) {
        entries = hardKeys.map { WeightEntry(it, 0.0, hard = true) } +
            softWeights.map { (k, w) -> WeightEntry(k, w, hard = false) }
    }
}
