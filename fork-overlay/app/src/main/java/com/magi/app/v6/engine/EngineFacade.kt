package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import java.util.Random

data class EngineOptions(
    val totalBudgetMs: Long = 300_000L,
    val postReserveMs: Long = 25_000L,
    val seed: Long = 0L,
    val shouldStop: () -> Boolean = { false },
    val infeasibleFamilies: Set<String> = emptySet(),
    val deltaHook: DeltaEvaluateHook? = null,
    /** G1 並列ワーカー数。1=単一 Session（完全再現）、2..=ParallelSaCoordinator */
    val workers: Int = 1,
    val nativeProbe: com.magi.app.v6.engine.nativex.NativeWritesProbe? = null,
    val progressListener: SearchProgressListener? = null,
)

/**
 * 単一入口。既定で FullyWiredG3 + FocusAwareFix を配線する。
 */
class EngineFacade(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val g3Backend: G3Backend? = null,
    private val fixProvider: FocusFixProvider? = null,
) {
    private val scheduler = SchedulerService(problem, evaluate, better, PackedScore::of)

    fun optimize(initial: Array<IntArray>, options: EngineOptions = EngineOptions()): RunArtifacts {
        val rng = Random(options.seed)
        val g3 = g3Backend ?: FullyWiredG3Backend(problem, evaluate, better, Random(options.seed xor 0xC3L))
        val fix = fixProvider ?: FocusAwareFixProvider(problem)
        return scheduler.optimize(
            initial = initial,
            profile = ScheduleProfile(options.totalBudgetMs, options.postReserveMs),
            rng = rng,
            shouldStop = options.shouldStop,
            infeasible = options.infeasibleFamilies,
            g3Backend = g3,
            fixProvider = fix,
            deltaHook = options.deltaHook,
            workers = options.workers,
            baseSeed = options.seed,
            nativeProbe = options.nativeProbe,
            progressListener = options.progressListener,
        )
    }
}
