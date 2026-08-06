package com.magi.app.v6.engine.integration

import android.util.Log
import com.magi.app.model.MagiState
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.engine.AppVersion
import com.magi.app.v6.engine.OptimizeBenchLog
import com.magi.app.v6.engine.SearchProgress

/**
 * Worker / UI から呼ぶ勤務表最適化の本番入口。
 * 内部は [FullOptimizePipeline]（初期解・G1–G4・HARD 残差・最終評価）。
 */
object RebuildOptimizeEntry {
    @JvmField
    @Volatile
    var enabled: Boolean = true  // MAGI-ShiftGfork: 既定は再構築（V5 SA 強制終了回避）

    fun applyBuildConfigDefault() {
        val v = runCatching {
            val cl = Class.forName("com.magi.app.BuildConfig")
            cl.getField("REBUILD_ENGINE").getBoolean(null)
        }.getOrDefault(false)
        enabled = true  // ShiftGfork では常に再構築。BuildConfig は参考ログのみ
        android.util.Log.i("MAGI", "RebuildOptimizeEntry enabled=true (buildConfig=$v)")
    }

    fun optimize(
        state: MagiState,
        schedule: Array<IntArray>,
        budgetSec: Int,
        seed: Long,
        workers: Int = 1,
        shouldStop: () -> Boolean = { false },
        onProgress: ((SearchProgress) -> Unit)? = null,
    ): ScheduleRunResult {
        val ver = AppVersion.info
        Log.i(OptimizeBenchLog.TAG, "MAGI_VERSION " + ver.logLine() + " engine=rebuild")
        Log.i("MAGI", "アプリ版 " + ver.compact())
        val ver = runCatching { com.magi.app.v6.engine.AppVersion.info.compact() }.getOrDefault("?")
        Log.i(
            "MAGI",
            "最適化 開始 version=$ver engine=rebuild budget=${budgetSec}s workers=$workers " +
                "native=${com.magi.app.v6.NativeBridge.available} gate=${com.magi.app.v6.NativeGate.usable}",
        )
        Log.i("MAGI", "探索フェーズ: rebuild-pipeline")

        val budgetMs = budgetSec.coerceAtLeast(1) * 1000L
        OptimizeBenchLog.beginRun(
            engine = OptimizeBenchLog.ENGINE_REBUILD,
            seed = seed,
            workers = workers.coerceIn(1, 16),
            budgetMs = budgetMs,
        )
        val post = (budgetMs * 0.08).toLong().coerceIn(5_000L, 25_000L)
        val residual = (budgetMs * 0.12).toLong().coerceIn(3_000L, 40_000L)
        return try {
            FullOptimizePipeline.optimize(
            state = state,
            scheduleIn = schedule,
            options = FullOptimizePipeline.Options(
                budgetMs = budgetMs,
                postReserveMs = post,
                seed = seed,
                workers = workers.coerceIn(1, 16),
                autoNative = true, // 差分は C++ SaChunk.deltaApply。パリティ NG 時は Kotlin 退化
                generateInitialIfNeeded = true,
                hardResidualMs = residual,
            ),
            shouldStop = shouldStop,
            onProgress = onProgress,
            )
        } catch (t: Throwable) {
            android.util.Log.e("MAGI", "RebuildOptimizeEntry crashed", t)
            val rep = runCatching {
                com.magi.app.v6.UnifiedViolationChecker.check(state, schedule)
            }.getOrElse {
                // minimal empty report if checker also fails
                throw t
            }
            com.magi.app.v6.ScheduleRunResult(schedule = schedule, report = rep)
        }
    }
}
