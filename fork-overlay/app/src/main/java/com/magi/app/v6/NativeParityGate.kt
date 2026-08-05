package com.magi.app.v6

import android.util.Log

/**
 * C++ / Kotlin 評価パリティの単一ゲート。
 *
 * 不一致時は [NativeGate.disable] でプロセス内の native 探索を閉じ、Kotlin のみで継続する
 * （クラッシュではなく安全退化）。ログに hard/soft を残し診断可能にする。
 *
 * 根本方針:
 * - 探索中の受理スコアと最終チェッカーは **Kotlin Evaluator / UnifiedViolationChecker** が正本
 * - C++ は加速用。1 回でも soft/hard がずれたらそのプロセスでは使わない
 */
object NativeParityGate {
    private const val TAG = "MAGI_PARITY"
    private const val M = SCORE_HARD_UNIT

    @Volatile
    var lastMismatch: String? = null
        private set

    /** 同一盤面で native fullEvalParts と Kotlin を照合。不一致なら native を閉じ false。 */
    fun assertOrDisable(problem: Problem, schedule: Array<IntArray>, label: String): Boolean {
        if (!NativeBridge.available || !NativeGate.enabled) return false
        val r = runCatching { NativeEval.parityCheck(problem, schedule) }.getOrNull() ?: return false
        if (r.match) {
            Log.i(TAG, "$label OK hard=${r.kotlinHard} soft=${r.kotlinSoft} nativeUs=${r.nativeUs}")
            return true
        }
        val msg =
            "$label NG C++ hard=${r.nativeHard}/soft=${r.nativeSoft} ≠ Kotlin hard=${r.kotlinHard}/soft=${r.kotlinSoft}"
        lastMismatch = msg
        Log.e(TAG, msg)
        NativeGate.disable(msg)
        NativeGate.userEnabled = false
        return false
    }

    /** packed score 同士の照合（SA チャンク用）。不一致で disable。 */
    fun assertPackedOrDisable(nativePacked: Long, kotlinPacked: Long, label: String): Boolean {
        if (nativePacked == kotlinPacked) return true
        val nh = nativePacked / M
        val ns = nativePacked % M
        val kh = kotlinPacked / M
        val ks = kotlinPacked % M
        val msg = "$label NG packed C++=$nativePacked (h=$nh s=$ns) Kotlin=$kotlinPacked (h=$kh s=$ks)"
        lastMismatch = msg
        Log.e(TAG, msg)
        NativeGate.disable(msg)
        NativeGate.userEnabled = false
        return false
    }
}
