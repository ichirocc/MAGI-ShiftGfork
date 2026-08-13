package com.magi.app.v6.engine.nativex

import com.magi.app.v6.engine.Move
import com.magi.app.v6.engine.RejectReason
import com.magi.app.v6.engine.SearchSessionFull
import com.magi.app.v6.engine.TransitionMode
import com.magi.app.v6.engine.TransitionResult

fun interface NativeWritesProbe {
    fun probe(
        scheduleFlat: IntArray,
        writes: IntArray,
        mode: Int,
        temperature: Double,
    ): NativeProbeResult
}

data class NativeProbeResult(
    val status: Int,
    val scheduleFlat: IntArray? = null,
    val nativeScore: Long = -1L,
)

object ScheduleFlat {
    fun flatten(schedule: Array<IntArray>): IntArray {
        val s = schedule.size
        val t = if (s > 0) schedule[0].size else 0
        val out = IntArray(s * t)
        flattenInto(schedule, out)
        return out
    }

    /**
     * [flatten] のバッファ再利用版。SA/G1 のホットループ（毎反復呼ばれる）で
     * IntArray(S*T) を新規確保し続けると GC 圧迫の原因になるため、呼び出し側が
     * 使い回すスクラッチバッファへ書き込むだけにする。out のサイズは
     * schedule.size * schedule[0].size 以上であること（呼び出し側の責任）。
     */
    fun flattenInto(schedule: Array<IntArray>, out: IntArray) {
        val s = schedule.size
        val t = if (s > 0) schedule[0].size else 0
        var k = 0
        for (i in 0 until s) for (j in 0 until t) out[k++] = schedule[i][j]
    }
}

fun SearchSessionFull.nativePrefilter(
    move: Move,
    mode: TransitionMode,
    probe: NativeWritesProbe?,
    temperature: Double = 0.0,
): NativeProbeResult {
    if (probe == null) return NativeProbeResult(0)
    val modeI = when (mode) {
        TransitionMode.STRICT -> 0
        TransitionMode.ANNEAL -> 1
        TransitionMode.LAHC -> 2
    }
    val flat = ScheduleFlat.flatten(current)
    return runCatching {
        probe.probe(flat.copyOf(), move.writes, modeI, temperature)
    }.getOrElse { NativeProbeResult(0) }
}

/**
 * STRICT 専用: Native が reject かつゲート許可なら Kotlin 評価をスキップ。
 * ウォームアップ中は両方実行して一致率を学習する。
 */
fun SearchSessionFull.tryTransitionStrictWithOptionalNativeSkip(
    move: Move,
    probe: NativeWritesProbe?,
    gate: NativeRejectSkipGate = GlobalNativeSkipGate.gate,
): TransitionResult {
    if (probe == null) return tryTransition(move, TransitionMode.STRICT)
    val pre = nativePrefilter(move, TransitionMode.STRICT, probe)
    if (pre.status == 0) {
        gate.recordNativeReject()
        if (gate.canSkipStrict()) {
            gate.onSkipped()
            return TransitionResult.Rejected(RejectReason.NOT_BETTER)
        }
        // 学習: Kotlin も試す
        val r = tryTransition(move, TransitionMode.STRICT)
        if (r is TransitionResult.Rejected) gate.recordAgreeReject()
        else gate.recordMismatch()
        return r
    }
    // Native accept hint → 必ず Kotlin で再検証
    return tryTransition(move, TransitionMode.STRICT)
}

fun SearchSessionFull.tryTransitionWithNativeProbe(
    move: Move,
    mode: TransitionMode,
    probe: NativeWritesProbe?,
    temperature: Double = 0.0,
): TransitionResult {
    if (mode == TransitionMode.STRICT) {
        return tryTransitionStrictWithOptionalNativeSkip(move, probe)
    }
    nativePrefilter(move, mode, probe, temperature)
    return tryTransition(move, mode)
}
