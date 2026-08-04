package com.magi.app.v6.engine.integration

import com.magi.app.model.MagiState
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.engine.OptimizeBenchLog
import kotlinx.coroutines.runBlocking

/**
 * 同一入力で rebuild と upstream を連続実行し、精度・速度の優劣をログに残す。
 *
 * 本番検証用（重い）。UI からはデバッグメニュー等で呼ぶ想定。
 *
 * ```
 * val cmp = OptimizeAbRunner.runBoth(state, schedule, budgetSec = 60, seed = 42)
 * // Logcat: MAGI_BENCH_COMPARE hardDelta=... winnerAccuracy=... winnerSpeed=...
 * ```
 */
object OptimizeAbRunner {

    data class AbResult(
        val rebuild: ScheduleRunResult,
        val upstream: ScheduleRunResult?,
        val rebuildMs: Long,
        val upstreamMs: Long?,
        val compareLine: String,
    )

    fun runBoth(
        state: MagiState,
        schedule: Array<IntArray>,
        budgetSec: Int,
        seed: Long,
        workers: Int = 1,
        runUpstream: Boolean = true,
        shouldStop: () -> Boolean = { false },
    ): AbResult {
        OptimizeBenchLog.clear()
        val copy1 = schedule.map { it.copyOf() }.toTypedArray()
        val t0 = System.currentTimeMillis()
        val rebuild = RebuildOptimizeEntry.optimize(
            state = state,
            schedule = copy1,
            budgetSec = budgetSec,
            seed = seed,
            workers = workers,
            shouldStop = shouldStop,
        )
        val rebuildMs = System.currentTimeMillis() - t0
        val rebuildSum = OptimizeBenchLog.Summary(
            engine = OptimizeBenchLog.ENGINE_REBUILD,
            hard = rebuild.report.hard,
            soft = rebuild.report.soft,
            total = rebuild.report.total,
            weighted = rebuild.report.weightedScore,
            elapsedMs = rebuildMs,
            seed = seed,
            workers = workers,
            budgetMs = budgetSec * 1000L,
            phases = OptimizeBenchLog.snapshot().filter { it.engine == OptimizeBenchLog.ENGINE_REBUILD },
        )

        var upstream: ScheduleRunResult? = null
        var upstreamMs: Long? = null
        var upstreamSum: OptimizeBenchLog.Summary? = null
        if (runUpstream) {
            OptimizeBenchLog.beginRun(
                OptimizeBenchLog.ENGINE_UPSTREAM, seed, workers, budgetSec * 1000L,
            )
            val copy2 = schedule.map { it.copyOf() }.toTypedArray()
            val t1 = System.currentTimeMillis()
            val action = runBlocking {
                V6FinalPort.handleOptimize(
                    state = state,
                    schedule = copy2,
                    secondsRaw = budgetSec,
                    workers = workers,
                    softPolish = false,
                    requestedAlgorithm = V6Algorithm.AUTO,
                    allowImpossible = true,
                )
            }
            upstreamMs = System.currentTimeMillis() - t1
            val rep = action.report
            OptimizeBenchLog.phase(
                engine = OptimizeBenchLog.ENGINE_UPSTREAM,
                phase = "done",
                report = rep,
                elapsedMs = upstreamMs,
                seed = seed,
                workers = workers,
                budgetMs = budgetSec * 1000L,
            )
            upstream = ScheduleRunResult(action.schedule, rep)
            upstreamSum = OptimizeBenchLog.Summary(
                engine = OptimizeBenchLog.ENGINE_UPSTREAM,
                hard = rep.hard,
                soft = rep.soft,
                total = rep.total,
                weighted = rep.weightedScore,
                elapsedMs = upstreamMs,
                seed = seed,
                workers = workers,
                budgetMs = budgetSec * 1000L,
                phases = OptimizeBenchLog.snapshot().filter { it.engine == OptimizeBenchLog.ENGINE_UPSTREAM },
            )
            OptimizeBenchLog.summary(upstreamSum)
        }

        val line = if (upstreamSum != null) {
            OptimizeBenchLog.compare(rebuildSum, upstreamSum)
        } else {
            "MAGI_BENCH_COMPARE skipped_upstream=1 rebuildHard=${rebuild.report.hard} rebuildMs=$rebuildMs"
        }
        return AbResult(rebuild, upstream, rebuildMs, upstreamMs, line)
    }
}
