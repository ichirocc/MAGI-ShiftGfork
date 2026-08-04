package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * 正規化済み遷移手。
 * writes: [staff, day, shift] × n（同一セル重複なし・適用時スキップ禁止）
 */
data class Move(
    val baseVersion: Long,
    val writes: IntArray,
    val family: String,
    val source: String,
) {
    val cellCount: Int get() = writes.size / 3

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Move) return false
        return baseVersion == other.baseVersion &&
            family == other.family &&
            source == other.source &&
            writes.contentEquals(other.writes)
    }

    override fun hashCode(): Int {
        var h = baseVersion.hashCode()
        h = 31 * h + writes.contentHashCode()
        h = 31 * h + family.hashCode()
        h = 31 * h + source.hashCode()
        return h
    }
}

enum class TransitionMode { STRICT, ANNEAL, LAHC }

enum class StopReason {
    CANCELLED,
    DEADLINE,
    FIXED_POINT,
    PROVEN_PIN_WALL,
    UNKNOWN_BUDGET,
}

enum class RejectReason {
    STALE_VERSION,
    INVALID_RANGE,
    DUPLICATE_CELL,
    NOOP,
    NOT_ALLOWED,
    WISH_PIN,
    EXACT_COUNT,
    NOT_BETTER,
}

enum class ExactCountPolicy {
    /**
     * 変更前に充足（count==lo）していた lo==hi ピンを、変更後に崩す手だけ拒否。
     * もともと外れていたピンは修復・現状維持を許す（exactPinRegression 相当）。
     */
    FORBID_WORSEN,
    /** ピン対象シフトが載っているセルの書き換え自体を禁止 */
    IMMUTABLE,
}

sealed interface TransitionResult {
    data object AcceptedBest : TransitionResult
    data object AcceptedCurrent : TransitionResult
    data class Rejected(val reason: RejectReason) : TransitionResult
}

data class RunArtifacts(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val alternatives: List<Array<IntArray>> = emptyList(),
    val fusionElites: List<Array<IntArray>> = emptyList(),
    val infeasibleFamilies: Set<String> = emptySet(),
    val stopReason: StopReason = StopReason.DEADLINE,
    val phaseLogs: List<MirrorLog> = emptyList(),
)
