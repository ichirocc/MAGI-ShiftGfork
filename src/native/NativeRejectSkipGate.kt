package com.magi.app.v6.engine.nativex

import com.magi.app.v6.engine.TransitionMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native 拒否スキップの安全ゲート。
 *
 * - STRICT のみ対象（ANNEAL/LAHC は悪化受理がありスキップ禁止）
 * - ウォームアップ中は常に Kotlin を実行し、Native reject と Kotlin reject の一致を計測
 * - mismatchRate < threshold かつ samples >= minSamples でスキップ許可
 */
class NativeRejectSkipGate(
    private val minSamples: Long = 500L,
    private val maxMismatchRate: Double = 0.001, // 0.1%
) {
    private val samples = AtomicLong(0)
    private val mismatches = AtomicLong(0)
    private val nativeRejects = AtomicLong(0)
    private val skipped = AtomicLong(0)
    private val enabled = AtomicBoolean(false)

    fun recordNativeReject() {
        nativeRejects.incrementAndGet()
    }

    /** Kotlin も reject した → 一致 */
    fun recordAgreeReject() {
        samples.incrementAndGet()
        maybeEnable()
    }

    /** Native は reject、Kotlin は accept → 不一致（スキップ危険） */
    fun recordMismatch() {
        samples.incrementAndGet()
        mismatches.incrementAndGet()
        if (mismatchRate() > maxMismatchRate * 5) {
            enabled.set(false) // 安全側に閉じる
        } else {
            maybeEnable()
        }
    }

    private fun maybeEnable() {
        val n = samples.get()
        if (n >= minSamples && mismatchRate() <= maxMismatchRate) {
            enabled.set(true)
        }
    }

    fun mismatchRate(): Double {
        val n = samples.get().coerceAtLeast(1L)
        return mismatches.get().toDouble() / n
    }

    fun canSkipStrict(): Boolean = enabled.get()

    fun onSkipped() {
        skipped.incrementAndGet()
    }

    fun stats(): String =
        "samples=${samples.get()} mismatch=${mismatches.get()} rate=${"%.4f".format(mismatchRate())} " +
            "enabled=${enabled.get()} skipped=${skipped.get()} nativeRejects=${nativeRejects.get()}"
}

/** プロセス共有ゲート（計測継続） */
object GlobalNativeSkipGate {
    @JvmField
    val gate = NativeRejectSkipGate()
}
