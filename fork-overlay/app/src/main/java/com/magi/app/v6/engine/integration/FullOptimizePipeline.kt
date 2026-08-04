package com.magi.app.v6.engine.integration

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.NativeBridge
import com.magi.app.v6.NativeEval
import com.magi.app.v6.Problem
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.SmartInitialScheduler
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.betterReport
import com.magi.app.v6.engine.BuiltinScheduleImprover
import com.magi.app.v6.engine.EngineWiring
import com.magi.app.v6.engine.ObjectiveWeightsSource
import com.magi.app.v6.engine.SearchProgress
import com.magi.app.v6.engine.SearchProgressListener
import com.magi.app.v6.engine.adapters.ScheduleImprover
import com.magi.app.v6.engine.c1.C1JointLnsEngine
import com.magi.app.v6.engine.nativex.NativeBridgeProbe
import com.magi.app.v6.engine.nativex.NativeWritesProbe
import java.util.Random

/**
 * 勤務表最適化の完全実行経路（初期解 → G1–G4 → HARD 残差 → 最終評価）。
 *
 * フォーク元の Checker / Problem / MirrorKeys / SmartInitial を使い、
 * 探索本体は再構築エンジン（Move 契約）に載せる。
 */
object FullOptimizePipeline {

    data class Options(
        val budgetMs: Long = 300_000L,
        val postReserveMs: Long = 25_000L,
        val seed: Long = 0L,
        val workers: Int = 1,
        val autoNative: Boolean = true,
        val generateInitialIfNeeded: Boolean = true,
        val hardResidualMs: Long = 40_000L,
    )

    fun isBlankSchedule(schedule: Array<IntArray>): Boolean {
        if (schedule.isEmpty()) return true
        return schedule.all { row -> row.isEmpty() || row.all { it < 0 } }
    }

    fun ensureInitial(state: MagiState, schedule: Array<IntArray>, generate: Boolean): Array<IntArray> {
        if (!generate || !isBlankSchedule(schedule)) return schedule
        val gen = runCatching { SmartInitialScheduler.generate(state) }.getOrNull()
        return gen?.schedule ?: schedule
    }

    fun optimize(
        state: MagiState,
        scheduleIn: Array<IntArray>,
        options: Options = Options(),
        shouldStop: () -> Boolean = { false },
        onProgress: ((SearchProgress) -> Unit)? = null,
    ): ScheduleRunResult {
        ObjectiveWeightsSource.install(MirrorKeys.hard, MirrorKeys.weights)

        val schedule0 = ensureInitial(state, scheduleIn, options.generateInitialIfNeeded)
        val problem = Problem(state)
        val evaluate: (Array<IntArray>) -> ViolationReport = { sch ->
            UnifiedViolationChecker.check(state, sch)
        }
        val better = ::betterReport
        val baseSeed = if (options.seed != 0L) options.seed else System.nanoTime()
        val rng = Random(baseSeed)

        val c1: ScheduleImprover = C1JointLnsEngine(problem, evaluate, better, Random(rng.nextLong()))
        val structural: ScheduleImprover = BuiltinScheduleImprover(
            problem, evaluate, better, BuiltinScheduleImprover.Mode.STRUCTURAL, Random(rng.nextLong()),
        )
        val c3: ScheduleImprover = BuiltinScheduleImprover(
            problem, evaluate, better, BuiltinScheduleImprover.Mode.C3, Random(rng.nextLong()),
        )
        val personal: ScheduleImprover = BuiltinScheduleImprover(
            problem, evaluate, better, BuiltinScheduleImprover.Mode.PERSONAL, Random(rng.nextLong()),
        )
        val fix = MainLegacyFix.create(problem, schedule0)

        var handle = 0L
        val probe: NativeWritesProbe? = if (options.autoNative && NativeBridge.available) {
            handle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
            if (handle != 0L) NativeBridgeProbe(handle) else null
        } else null

        val listener = onProgress?.let { cb -> SearchProgressListener { p -> cb(p) } }

        try {
            onProgress?.invoke(
                SearchProgress("start", evaluate(schedule0), 0L, 0L, schedule0),
            )

            val bridge = EngineWiring.forMain(
                problem = problem,
                evaluate = evaluate,
                better = better,
                legacyCellFix = fix,
                structural = structural,
                c1 = c1,
                c3 = c3,
                personal = personal,
                workers = options.workers.coerceIn(1, 16),
                nativeProbe = probe,
                progressListener = listener,
                requireWeights = true,
            )

            var art = bridge.optimize(
                initial = schedule0,
                totalBudgetMs = options.budgetMs,
                postReserveMs = options.postReserveMs,
                seed = baseSeed,
                shouldStop = shouldStop,
            )

            if (!shouldStop() && art.report.hard > 0 && options.hardResidualMs > 0L) {
                onProgress?.invoke(
                    SearchProgress("hard_residual", art.report, 0L, 0L, art.schedule),
                )
                val residual = EngineWiring.forMain(
                    problem = problem,
                    evaluate = evaluate,
                    better = better,
                    legacyCellFix = fix,
                    structural = structural,
                    c1 = c1,
                    c3 = c3,
                    personal = personal,
                    workers = 1,
                    nativeProbe = probe,
                    progressListener = listener,
                    requireWeights = true,
                ).optimize(
                    initial = art.schedule,
                    totalBudgetMs = options.hardResidualMs,
                    postReserveMs = (options.hardResidualMs / 5).coerceAtLeast(1L),
                    seed = baseSeed xor 0x11A11D11L,
                    shouldStop = shouldStop,
                )
                if (better(residual.report, art.report)) {
                    art = residual
                }
            }

            val finalReport = evaluate(art.schedule)
            onProgress?.invoke(
                SearchProgress("done", finalReport, 0L, 0L, art.schedule),
            )
            return ScheduleRunResult(schedule = art.schedule, report = finalReport)
        } finally {
            if (handle != 0L) runCatching { NativeBridge.nativeDestroyProblem(handle) }
        }
    }
}
