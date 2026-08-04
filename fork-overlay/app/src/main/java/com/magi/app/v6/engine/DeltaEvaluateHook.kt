package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * フル Checker の代わりに Delta / Native を差し込む口。
 * SearchSessionFull は現状 evaluate ラムダのみ。
 * 高速化時は Move 適用後にこの hook で ViolationReport 相当を返す。
 */
fun interface DeltaEvaluateHook {
    /**
     * @param schedule 適用後盤面
     * @param writes 直前の Move.writes（差分ヒント）
     * @param undoSnap 適用前の staff,day,oldShift
     */
    fun evaluateAfterMove(
        schedule: Array<IntArray>,
        writes: IntArray,
        undoSnap: IntArray,
    ): ViolationReport
}

/**
 * デフォルト: フル評価にフォールバック。
 */
fun fullEvaluateHook(evaluate: (Array<IntArray>) -> ViolationReport): DeltaEvaluateHook =
    DeltaEvaluateHook { schedule, _, _ -> evaluate(schedule) }
