package com.magi.app.v6.engine.nativex

import com.magi.app.v6.NativeBridge
import com.magi.app.v6.NativeGate

/**
 * NativeBridge.nativeTryWrites / nativeDeltaEval を [NativeWritesProbe] にする。
 * handle は nativeCreateProblem の戻り値（ワーカー専用を推奨）。
 */
class NativeBridgeProbe(
    private val problemHandle: Long,
) : NativeWritesProbe {

    val handle: Long get() = problemHandle

    override fun probe(
        scheduleFlat: IntArray,
        writes: IntArray,
        mode: Int,
        temperature: Double,
    ): NativeProbeResult {
        if (!NativeBridge.available || problemHandle == 0L) {
            return NativeProbeResult(0)
        }
        val ret = runCatching {
            NativeBridge.nativeTryWrites(
                problemHandle,
                scheduleFlat,
                writes,
                mode,
                temperature,
                Long.MAX_VALUE,
            )
        }.getOrNull() ?: return NativeProbeResult(0)
        if (ret.size < 4 || ret[0] == 0L) return NativeProbeResult(0)
        // scheduleFlat は JNI 側で書き戻済み（受理時）
        return NativeProbeResult(
            status = ret[0].toInt(),
            scheduleFlat = scheduleFlat,
            nativeScore = ret[1],
        )
    }

    /**
     * 盤面非破壊の差分スコア。Kotlin フル評価の前段フィルタに使う。
     * @return null = native 不可 / 失敗
     */
    fun deltaScore(scheduleFlat: IntArray, writes: IntArray): NativeDeltaBridge.DeltaScore? {
        if (!NativeBridge.available || !NativeGate.usable || problemHandle == 0L) return null
        return NativeDeltaBridge.scoreAfterWrites(problemHandle, scheduleFlat, writes)
    }
}
