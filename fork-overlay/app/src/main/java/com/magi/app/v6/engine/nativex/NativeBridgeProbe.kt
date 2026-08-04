package com.magi.app.v6.engine.nativex

import com.magi.app.v6.NativeBridge

/**
 * NativeBridge.nativeTryWrites を [NativeWritesProbe] にする。
 * handle は nativeCreateProblem の戻り値。
 */
class NativeBridgeProbe(
    private val problemHandle: Long,
) : NativeWritesProbe {
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
        // scheduleFlat は JNI 側で書き戻済み
        return NativeProbeResult(
            status = ret[0].toInt(),
            scheduleFlat = scheduleFlat,
            nativeScore = ret[1],
        )
    }
}
