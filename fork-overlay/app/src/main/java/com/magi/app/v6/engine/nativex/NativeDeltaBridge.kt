package com.magi.app.v6.engine.nativex

import com.magi.app.v6.NativeBridge
import com.magi.app.v6.NativeEval
import com.magi.app.v6.NativeGate
import com.magi.app.v6.Problem
import com.magi.app.v6.SCORE_HARD_UNIT
import com.magi.app.v6.engine.Move

/**
 * 差分評価の C++ 移行入口。
 *
 * Kotlin [com.magi.app.v6.DeltaEvaluator] 相当のホットパスを
 * [NativeBridge.nativeDeltaEval] / [NativeBridge.nativeTryWrites]（SaChunk.deltaApply）へ委譲する。
 * .so 不可・パリティ失敗時は null を返し、呼び出し側が Kotlin フル評価へ退化する。
 */
object NativeDeltaBridge {
    data class DeltaScore(
        val beforePacked: Long,
        val afterPacked: Long,
        val hardAfter: Int,
        val softAfter: Long,
    ) {
        val improved: Boolean get() = afterPacked < beforePacked
        val hardImproved: Boolean get() = hardAfter < (beforePacked / SCORE_HARD_UNIT).toInt()
    }

    fun scoreAfterWrites(
        problemHandle: Long,
        scheduleFlat: IntArray,
        writes: IntArray,
    ): DeltaScore? {
        if (!NativeBridge.available || !NativeGate.usable || problemHandle == 0L) return null
        if (writes.isEmpty() || writes.size % 3 != 0) return null
        val r = runCatching {
            NativeBridge.nativeDeltaEval(problemHandle, scheduleFlat, writes)
        }.getOrNull() ?: return null
        if (r.size < 4 || r[0] != 1L) return null
        val after = r[2]
        return DeltaScore(
            beforePacked = r[1],
            afterPacked = after,
            hardAfter = (after / SCORE_HARD_UNIT).toInt(),
            softAfter = after % SCORE_HARD_UNIT,
        )
    }

    fun scoreAfterMove(
        problem: Problem,
        problemHandle: Long,
        schedule: Array<IntArray>,
        move: Move,
    ): DeltaScore? {
        val s = problem.S; val t = problem.T
        val flat = IntArray(s * t) { idx -> schedule[idx / t][idx % t] }
        return scoreAfterWrites(problemHandle, flat, move.writes)
    }
}
