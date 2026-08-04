package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport

/**
 * C++ SA チャンク向け書き込みバッチ契約。
 * 生の schedule[i][j]= や strongPerturb 専用経路は持たない。
 */
data class NativeWriteBatch(
    val baseVersion: Long,
    val writes: IntArray,
    /** 0=STRICT, 1=ANNEAL, 2=LAHC */
    val mode: Int,
    /** LAHC 用履歴閾値（mode=2） */
    val lahcThreshold: Long = Long.MAX_VALUE,
)

interface NativeTransitionBridge {
    fun tryWrites(batch: NativeWriteBatch, temperature: Double = 0.0): TransitionResult
    fun snapshotBestSchedule(): Array<IntArray>
    fun bestReport(): ViolationReport
}

/** Native 未使用時: SearchSessionFull に委譲 */
class KotlinOnlyTransitionBridge(
    private val session: SearchSessionFull,
    private val rng: java.util.Random = java.util.Random(0L),
    private val packedScore: (ViolationReport) -> Long = PackedScore::of,
) : NativeTransitionBridge {
    override fun tryWrites(batch: NativeWriteBatch, temperature: Double): TransitionResult {
        val move = Move(batch.baseVersion, batch.writes, "native_batch", "bridge")
        return when (batch.mode) {
            0 -> session.tryTransition(move, TransitionMode.STRICT)
            1 -> session.tryMetropolis(move, temperature, rng, packedScore)
            2 -> session.tryLahc(move, batch.lahcThreshold, packedScore)
            else -> TransitionResult.Rejected(RejectReason.INVALID_RANGE)
        }
    }

    override fun snapshotBestSchedule(): Array<IntArray> =
        SearchSessionFull.deepCopy(session.best)

    override fun bestReport(): ViolationReport = session.bestReport
}

fun Move.toNativeBatch(mode: TransitionMode, lahcThreshold: Long = Long.MAX_VALUE): NativeWriteBatch =
    NativeWriteBatch(
        baseVersion = baseVersion,
        writes = writes,
        mode = when (mode) {
            TransitionMode.STRICT -> 0
            TransitionMode.ANNEAL -> 1
            TransitionMode.LAHC -> 2
        },
        lahcThreshold = lahcThreshold,
    )
