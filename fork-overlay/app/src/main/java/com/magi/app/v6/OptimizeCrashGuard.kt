package com.magi.app.v6

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 最適化開始時の強制終了を防ぐ入口ガード。
 *
 * 根本原因（実機ログ: V5 SA 開始直後に落ちる）:
 * 1. 並列 SA が 1 本の native problem handle を共有 → JNI/C++ 競合で SIGSEGV
 * 2. Native パリティ不一致でも native 継続 → 異常経路
 * 3. Worker が Exception のみ捕捉 → Error / 一部 Throwable でプロセス終了
 *
 * 本オブジェクトは最適化の直前に呼ぶ。SaOptimizer 側の per-worker handle と併用する。
 */
object OptimizeCrashGuard {
    private const val TAG = "MAGI"

    /**
     * @param workers ユーザー設定の並列数
     * @param nativeUserPref 設定トグル「ネイティブ加速」
     * @return 安全側にクランプした並列数
     */
    fun beforeOptimize(workers: Int, nativeUserPref: Boolean): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var w = workers.coerceIn(1, cores.coerceAtMost(8))
        // ParallelSa がワーカー専用 handle を持つので並列 native を許可。上限はコア数と 8。
        val maxPar = 8
        if (w > maxPar) {
            Log.w(TAG, "OptimizeCrashGuard: workers $w → $maxPar")
            w = maxPar
        }

        NativeGate.userEnabled = nativeUserPref && NativeBridge.available

        if (!NativeBridge.available) {
            NativeGate.userEnabled = false
            Log.i(TAG, "OptimizeCrashGuard: native .so なし → Kotlin のみ workers=$w")
        } else {
            Log.i(
                TAG,
                "OptimizeCrashGuard: workers=$w cores=$cores nativeUser=$nativeUserPref " +
                    "usable=${NativeGate.usable} (parallel uses per-worker handles)",
            )
        }
        return w
    }

    /** 最適化全体を包む。Error 含む Throwable を捕捉し Result 化しない呼び出し側向け。 */
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
