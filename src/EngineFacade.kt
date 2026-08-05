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
    val fixedItersG1: Long = 0L,
    val fixedItersG2: Long = 0L,
    val skipPostProcess: Boolean = false,
)

/**
 * 単一入口。SchedulerService（G1→RSI→ALNS→VNS→G3→G4）へ配線する。
 *
 * 入口ガード:
 * - ProblemGuards（S/T/K・盤面形状）
 * - ObjectiveWeightsSource.ensureDefaults（重み未 install 時）
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
        ObjectiveWeightsSource.ensureDefaults()
        if (!ProblemGuards.isRunnable(problem)) {
            val empty = Array(0) { IntArray(0) }
            val rep = evaluate(initial)
            return RunArtifacts(
                schedule = initial,
                report = rep,
                stopReason = StopReason.CANCELLED,
            )
        }
        val shaped = if (ProblemGuards.scheduleShapeOk(problem, initial)) {
            initial
        } else {
            // 形状不正: 休埋めで正規化できる場合のみ。不可なら early return。
            normalizeShape(problem, initial) ?: run {
                val rep = evaluate(initial)
                return RunArtifacts(schedule = initial, report = rep, stopReason = StopReason.CANCELLED)
            }
        }
        val rng = Random(options.seed)
        val g3 = g3Backend ?: FullyWiredG3Backend(problem, evaluate, better, Random(options.seed xor 0xC3L))
        val fix = fixProvider ?: FocusAwareFixProvider(problem)
        return scheduler.optimize(
            initial = shaped,
            profile = ScheduleProfile(
                options.totalBudgetMs.coerceAtLeast(1L),
                options.postReserveMs.coerceIn(0L, options.totalBudgetMs.coerceAtLeast(1L)),
            ),
            rng = rng,
            shouldStop = options.shouldStop,
            infeasible = options.infeasibleFamilies,
            g3Backend = g3,
            fixProvider = fix,
            deltaHook = options.deltaHook,
            workers = options.workers.coerceIn(1, 16),
            baseSeed = options.seed,
            nativeProbe = options.nativeProbe,
            progressListener = options.progressListener,
            fixedItersG1 = options.fixedItersG1,
            fixedItersG2 = options.fixedItersG2,
            skipPostProcess = options.skipPostProcess,
        )
    }

    private fun normalizeShape(problem: Problem, initial: Array<IntArray>): Array<IntArray>? {
        if (problem.S <= 0 || problem.T <= 0) return null
        val rest = 0  // OFF/rest as shift index 0 (休は通常シフト)
        return Array(problem.S) { s ->
            IntArray(problem.T) { d ->
                val row = initial.getOrNull(s)
                val v = row?.getOrNull(d)
                if (v != null && v in 0 until problem.K) v else rest
            }
        }
    }
}
