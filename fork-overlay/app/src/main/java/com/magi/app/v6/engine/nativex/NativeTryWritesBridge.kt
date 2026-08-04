package com.magi.app.v6.engine.nativex

import com.magi.app.v6.NativeBridge
import com.magi.app.v6.engine.Move
import com.magi.app.v6.engine.TransitionMode

/**
 * 既存 NativeBridge.nativeTryWrites への薄いアダプタ。
 */
object NativeTryWritesBridge {
    fun tryWrites(
        problemHandle: Long,
        scheduleFlat: IntArray,
        move: Move,
        mode: TransitionMode,
        temperature: Double = 0.0,
        lahcThreshold: Long = Long.MAX_VALUE,
    ): LongArray {
        if (!NativeBridge.available || problemHandle == 0L) {
            return longArrayOf(0, -1, -1, -1)
        }
        val modeI = when (mode) {
            TransitionMode.STRICT -> 0
            TransitionMode.ANNEAL -> 1
            TransitionMode.LAHC -> 2
        }
        return runCatching {
            NativeBridge.nativeTryWrites(
                problemHandle,
                scheduleFlat,
                move.writes,
                modeI,
                temperature,
                lahcThreshold,
            )
        }.getOrElse { longArrayOf(0, -1, -1, -1) }
    }

    fun flatten(schedule: Array<IntArray>): IntArray {
        val s = schedule.size
        val t = if (s > 0) schedule[0].size else 0
        val out = IntArray(s * t)
        var k = 0
        for (i in 0 until s) for (j in 0 until t) out[k++] = schedule[i][j]
        return out
    }

    fun unflatten(flat: IntArray, S: Int, T: Int): Array<IntArray> =
        Array(S) { i -> IntArray(T) { j -> flat[i * T + j] } }
}
