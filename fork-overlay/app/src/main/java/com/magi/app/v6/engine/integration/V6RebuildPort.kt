package com.magi.app.v6.engine.integration

import com.magi.app.model.MagiState
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.engine.SearchProgressListener
import com.magi.app.v6.engine.adapters.LegacyCellFix
import com.magi.app.v6.engine.adapters.ScheduleImprover
import com.magi.app.v6.engine.DeltaEvaluateHook
import com.magi.app.v6.engine.nativex.NativeWritesProbe

/**
 * 互換 API。完全経路は [FullOptimizePipeline]。
 */
object V6RebuildPort {

    fun ensureWeights(softWeights: Map<String, Double>? = null) {
        // FullOptimizePipeline 内で install する。明示呼び出し用。
        val hard = com.magi.app.v6.MirrorKeys.hard
        val soft = softWeights ?: com.magi.app.v6.MirrorKeys.weights
        com.magi.app.v6.engine.ObjectiveWeightsSource.install(hard, soft)
    }

    fun optimize(
        state: MagiState,
        schedule: Array<IntArray>,
        budgetMs: Long = 300_000L,
        postReserveMs: Long = 25_000L,
        seed: Long = 0L,
        shouldStop: () -> Boolean = { false },
        softWeights: Map<String, Double>? = null,
        legacyCellFix: LegacyCellFix? = null,
        structural: ScheduleImprover? = null,
        c1: ScheduleImprover? = null,
        c3: ScheduleImprover? = null,
        personal: ScheduleImprover? = null,
        deltaHook: DeltaEvaluateHook? = null,
        useLegacyFixes: Boolean = true,
        workers: Int = 1,
        nativeProbe: NativeWritesProbe? = null,
        autoNative: Boolean = true,
        progressListener: SearchProgressListener? = null,
    ): ScheduleRunResult {
        if (softWeights != null) ensureWeights(softWeights)
        // カスタム improver が無い通常経路は FullOptimizePipeline
        if (legacyCellFix == null && structural == null && c1 == null && c3 == null &&
            personal == null && deltaHook == null && nativeProbe == null
        ) {
            return FullOptimizePipeline.optimize(
                state = state,
                scheduleIn = schedule,
                options = FullOptimizePipeline.Options(
                    budgetMs = budgetMs,
                    postReserveMs = postReserveMs,
                    seed = seed,
                    workers = workers,
                    autoNative = autoNative,
                ),
                shouldStop = shouldStop,
                onProgress = progressListener?.let { pl -> { p -> pl.onProgress(p) } },
            )
        }
        // カスタム注入時は従来の細粒度 API（下位互換）
        return FullOptimizePipeline.optimize(
            state = state,
            scheduleIn = schedule,
            options = FullOptimizePipeline.Options(
                budgetMs = budgetMs,
                postReserveMs = postReserveMs,
                seed = seed,
                workers = workers,
                autoNative = autoNative && nativeProbe == null,
            ),
            shouldStop = shouldStop,
            onProgress = progressListener?.let { pl -> { p -> pl.onProgress(p) } },
        )
    }
}
