package com.magi.app.v6.engine.nativex

import com.magi.app.v6.engine.NativeWriteBatch
import com.magi.app.v6.engine.RejectReason
import com.magi.app.v6.engine.TransitionResult

/**
 * magi_native の JNI 実装側ラッパ。
 * ライブラリ未ロード時は [available]=false。
 *
 * C++ は magi_native_api.hpp.txt の契約を実装すること。
 */
class JniNativeMoveEngine private constructor(
    private val handle: Long,
) : NativeMoveEngine {

    override val available: Boolean get() = handle != 0L && NativeGate.enabled

    override fun tryWrites(batch: NativeWriteBatch, temperature: Double): TransitionResult {
        if (!available) return TransitionResult.Rejected(RejectReason.INVALID_RANGE)
        val code = try {
            nativeTryWrites(
                handle,
                batch.baseVersion,
                batch.writes,
                batch.mode,
                temperature,
                batch.lahcThreshold,
            )
        } catch (t: Throwable) {
            NativeGate.disable("JNI tryWrites: ${t.message}")
            return TransitionResult.Rejected(RejectReason.INVALID_RANGE)
        }
        return when (code) {
            1 -> TransitionResult.AcceptedCurrent
            2 -> TransitionResult.AcceptedBest
            else -> TransitionResult.Rejected(RejectReason.NOT_BETTER)
        }
    }

    override fun packedScore(): Long =
        if (!available) 0L else runCatching { nativePackedScore(handle) }.getOrDefault(0L)

    override fun close() {
        if (handle != 0L) runCatching { nativeDestroyProblem(handle) }
    }

    companion object {
        const val ABI_VERSION = 3

        private val loadOk: Boolean = try {
            System.loadLibrary("magi_native")
            nativeAbiVersion() == ABI_VERSION
        } catch (_: Throwable) {
            false
        }

        fun isLibraryPresent(): Boolean = loadOk

        /**
         * @param flatProblem 平坦化した問題バイナリ（C++ と合意したフォーマット）
         */
        fun create(flatProblem: ByteArray): NativeMoveEngine {
            if (!loadOk) return NullNativeMoveEngine()
            val h = runCatching { nativeCreateProblem(flatProblem) }.getOrDefault(0L)
            if (h == 0L) return NullNativeMoveEngine()
            return JniNativeMoveEngine(h)
        }

        @JvmStatic private external fun nativeAbiVersion(): Int
        @JvmStatic private external fun nativeCreateProblem(flat: ByteArray): Long
        @JvmStatic private external fun nativeDestroyProblem(handle: Long)
        @JvmStatic private external fun nativeTryWrites(
            handle: Long,
            baseVersion: Long,
            writes: IntArray,
            mode: Int,
            temperature: Double,
            lahcThreshold: Long,
        ): Int
        @JvmStatic private external fun nativePackedScore(handle: Long): Long
    }
}
