package com.magi.app.v6

import android.util.Log
import com.magi.app.v6.engine.parallel.ParallelSaCoordinator
import kotlinx.coroutines.CancellationException

/**
 * 最適化開始時の強制終了を防ぐ入口ガード。
 *
 * 根本原因（実機: g1-parallel 中の OOM/LMK）:
 * 1. 評価のたび deepCopy + 共有ロック → ヒープ圧迫
 * 2. 並列 8 で Session×8 が同時常駐
 * 3. 進捗ログ過多
 *
 * ParallelSaCoordinator は Kotlin のみ・コピーは Elite 公開時のみ。
 */
object OptimizeCrashGuard {
    private const val TAG = "MAGI"

    fun beforeOptimize(workers: Int, nativeUserPref: Boolean): Int {
        val w = ParallelSaCoordinator.safeWorkerCount(workers)
        if (w != workers) {
            Log.w(TAG, "OptimizeCrashGuard: workers $workers → $w (mem/cores)")
        }

        NativeGate.userEnabled = nativeUserPref && NativeBridge.available

        if (!NativeBridge.available) {
            NativeGate.userEnabled = false
            Log.i(TAG, "OptimizeCrashGuard: native .so なし → Kotlin のみ workers=$w")
        } else {
            Log.i(
                TAG,
                "OptimizeCrashGuard: workers=$w nativeUser=$nativeUserPref " +
                    "usable=${NativeGate.usable} parallel=kotlin-only",
            )
        }
        return w
    }

    fun <T> runCatchingFatal(label: String, fallback: () -> T, block: () -> T): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "OptimizeCrashGuard: $label", t)
            fallback()
        }
    }
}
