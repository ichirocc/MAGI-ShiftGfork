package com.magi.app.v6

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * フル評価の C++ 移行ゲート。
 *
 * [Evaluator.fullEvalParts] はここを経由して [NativeBridge.nativeFullEval] を優先する。
 * ハンドル未設定・.so 不可・パリティ失敗時は Kotlin 実装へ退化する。
 *
 * ViolationReport（セル位置・内訳マップ）は引き続き UnifiedViolationChecker（Kotlin）。
 * 辞書式スコア（hard/soft 数値）のフル計算ホットパスのみ C++。
 */
object NativeFullEval {
    private const val TAG = "MAGI_FULL"

    @Volatile
    var problemHandle: Long = 0L
        private set

    private val nativeHits = AtomicLong(0)
    private val kotlinFalls = AtomicLong(0)
    private val parityFails = AtomicLong(0)

    /** 最適化開始時に呼ぶ。終了時は [detach]。 */
    fun attach(handle: Long) {
        problemHandle = handle
        Log.i(TAG, "attach handle=$handle available=${NativeBridge.available}")
    }

    fun detach() {
        problemHandle = 0L
    }

    fun stats(): String =
        "nativeHits=${nativeHits.get()} kotlinFalls=${kotlinFalls.get()} parityFails=${parityFails.get()}"

    /**
     * @return [hard, soft] or null if native 不可
     */
    fun tryParts(schedule: Array<IntArray>): LongArray? {
        val h = problemHandle
        if (h == 0L || !NativeBridge.available || !NativeGate.usable) return null
        val flat = NativeEval.flatten(schedule)
        val r = runCatching { NativeBridge.nativeFullEval(h, flat) }.getOrNull() ?: return null
        if (r.size < 2 || r[0] < 0L) return null
        // 辞書式パック契約: soft が桁を超えたら信用せず Kotlin へ
        if (r[1] < 0L || r[1] >= com.magi.app.v6.SCORE_HARD_UNIT) {
            Log.w(TAG, "native soft out of range soft=${r[1]} → kotlin fallback")
            return null
        }
        nativeHits.incrementAndGet()
        return longArrayOf(r[0], r[1])
    }

    fun tryPacked(schedule: Array<IntArray>): Long? {
        val p = tryParts(schedule) ?: return null
        return p[0] * SCORE_HARD_UNIT + p[1]
    }

    /**
     * 起動時サンプル照合。不一致なら [NativeGate.disable]。
     */
    fun assertParitySample(problem: Problem, schedule: Array<IntArray>): Boolean {
        val h = problemHandle
        if (h == 0L) return false
        val ok = NativeParityGate.assertOrDisable(problem, schedule, "full-eval-attach")
        if (!ok) parityFails.incrementAndGet()
        return ok
    }

    internal fun noteKotlinFallback() {
        kotlinFalls.incrementAndGet()
    }
}
