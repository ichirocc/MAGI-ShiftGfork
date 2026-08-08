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
import com.magi.app.v6.engine.ProblemGuards
import com.magi.app.v6.engine.WeightAuditLog
import com.magi.app.v6.engine.SearchProgress
import com.magi.app.v6.engine.SearchProgressListener
import com.magi.app.v6.engine.AppVersion
import com.magi.app.v6.engine.OptimizeBenchLog
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
        val autoNative: Boolean = false,
        val generateInitialIfNeeded: Boolean = true,
        val hardResidualMs: Long = 50_000L,
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
        ObjectiveWeightsSource.install(
            MirrorKeys.hard,
            MirrorKeys.weights.filterKeys { it !in MirrorKeys.hard },
        )
        WeightAuditLog.logTable()

        android.util.Log.i(OptimizeBenchLog.TAG, "MAGI_VERSION ${AppVersion.info.logLine()} engine=rebuild pipeline=FullOptimize")
        val wall0 = System.currentTimeMillis()
        val schedule0 = ensureInitial(state, scheduleIn, options.generateInitialIfNeeded)
        val problem = Problem(state)
        // フル評価を C++ に載せる（Evaluator.fullEvalParts が参照）
        var fullEvalHandle = 0L
        if (options.autoNative && com.magi.app.v6.NativeBridge.available) {
            fullEvalHandle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
            if (fullEvalHandle != 0L) {
                com.magi.app.v6.NativeFullEval.attach(fullEvalHandle)
                com.magi.app.v6.NativeFullEval.assertParitySample(problem, schedule0)
            }
        }
        val evaluate: (Array<IntArray>) -> ViolationReport = { sch ->
            UnifiedViolationChecker.check(state, sch)
        }
        if (!ProblemGuards.isRunnable(problem) || !ProblemGuards.scheduleShapeOk(problem, schedule0)) {
            android.util.Log.e("MAGI", "FullOptimizePipeline: problem/schedule not runnable")
            val rep = runCatching { evaluate(schedule0) }.getOrElse {
                runCatching { evaluate(scheduleIn) }.getOrThrow()
            }
            return ScheduleRunResult(schedule = scheduleIn, report = rep)
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
            // 探索前に 1 盤面パリティ。不一致なら native を閉じ probe=null
            if (!com.magi.app.v6.NativeParityGate.assertOrDisable(problem, schedule0, "pipeline-start")) {
                android.util.Log.w("MAGI", "native disabled after parity fail; Kotlin only")
                null
            } else {
                handle = runCatching { NativeEval.createHandle(problem) }.onFailure {
                    android.util.Log.e("MAGI", "NativeEval.createHandle failed", it)
                }.getOrDefault(0L)
                if (handle != 0L) NativeBridgeProbe(handle) else null
            }
        } else null

        val listener = onProgress?.let { cb -> SearchProgressListener { p -> cb(p) } }

        try {
            val startReport = evaluate(schedule0)
            OptimizeBenchLog.phase(
                engine = OptimizeBenchLog.ENGINE_REBUILD,
                phase = "start",
                report = startReport,
                elapsedMs = System.currentTimeMillis() - wall0,
                seed = options.seed,
                workers = options.workers,
                budgetMs = options.budgetMs,
            )
            onProgress?.invoke(
                SearchProgress("start", startReport, 0L, System.currentTimeMillis() - wall0, schedule0),
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
                nativeProbe = probe, // 並列本体は Kotlin-only。probe は G1 refine / RSI 以降で使用
                progressListener = listener,
                requireWeights = true,
            )

            // 並列時はグローバル NativeFullEval を外す（handle 競合・SIGSEGV 防止）
            val parallel = options.workers > 1
            if (parallel) {
                com.magi.app.v6.NativeFullEval.detach()
                android.util.Log.i("MAGI", "NativeFullEval detached for parallel workers=${options.workers}")
            }
            android.util.Log.i("MAGI", "STAGE pipeline-bridge-optimize begin workers=${options.workers}")
            var art = bridge.optimize(
                initial = schedule0,
                totalBudgetMs = options.budgetMs,
                postReserveMs = options.postReserveMs,
                seed = baseSeed,
                shouldStop = shouldStop,
            )
            if (parallel && fullEvalHandle != 0L && com.magi.app.v6.NativeGate.usable) {
                com.magi.app.v6.NativeFullEval.attach(fullEvalHandle)
            }
            android.util.Log.i("MAGI", "STAGE pipeline-bridge-optimize end hard=${art.report.hard}")
            OptimizeBenchLog.phase(
                engine = OptimizeBenchLog.ENGINE_REBUILD,
                phase = "pipeline",
                report = art.report,
                elapsedMs = System.currentTimeMillis() - wall0,
                seed = baseSeed,
                workers = options.workers,
                budgetMs = options.budgetMs,
            )

            // 残差用 native（並列中は外していた handle を付け直す）
            val residualProbe: com.magi.app.v6.engine.nativex.NativeWritesProbe? =
                if (com.magi.app.v6.NativeBridge.available && com.magi.app.v6.NativeGate.usable) {
                    if (fullEvalHandle != 0L) {
                        com.magi.app.v6.NativeFullEval.attach(fullEvalHandle)
                    }
                    if (handle != 0L) {
                        com.magi.app.v6.engine.nativex.NativeBridgeProbe(handle)
                    } else if (fullEvalHandle != 0L) {
                        runCatching {
                            handle = com.magi.app.v6.NativeEval.createHandle(problem)
                            if (handle != 0L) com.magi.app.v6.engine.nativex.NativeBridgeProbe(handle) else null
                        }.getOrNull()
                    } else null
                } else null
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
                    // 残差は単一スレッド。並列メインでもここで native を付け直す
                    nativeProbe = residualProbe,
                    progressListener = listener,
                    requireWeights = true,
                ).optimize(
                    initial = art.schedule,
                    // 残差は探索寄り（post を薄く）→ RSI/ALNS に時間を回す
                    totalBudgetMs = options.hardResidualMs,
                    postReserveMs = (options.hardResidualMs / 10).coerceAtLeast(1L),
                    seed = baseSeed xor 0x11A11D11L,
                    shouldStop = shouldStop,
                )
                OptimizeBenchLog.phase(
                    engine = OptimizeBenchLog.ENGINE_REBUILD,
                    phase = "residual",
                    report = residual.report,
                    elapsedMs = System.currentTimeMillis() - wall0,
                    seed = baseSeed,
                    workers = 1,
                    budgetMs = options.hardResidualMs,
                )
                android.util.Log.i(
                    "MAGI",
                    "STAGE residual-done hard=${art.report.hard}->${residual.report.hard} " +
                        "soft=${art.report.soft}->${residual.report.soft}",
                )
                onProgress?.invoke(
                    SearchProgress(
                        "residual",
                        residual.report,
                        0L,
                        System.currentTimeMillis() - wall0,
                        residual.schedule,
                    ),
                )
                if (better(residual.report, art.report)) {
                    art = residual
                }
            }

            val finalReport = evaluate(art.schedule)
        WeightAuditLog.logContribution("final", finalReport)
            val elapsed = System.currentTimeMillis() - wall0
            OptimizeBenchLog.phase(
                engine = OptimizeBenchLog.ENGINE_REBUILD,
                phase = "done",
                report = finalReport,
                elapsedMs = elapsed,
                seed = baseSeed,
                workers = options.workers,
                budgetMs = options.budgetMs,
            )
            OptimizeBenchLog.summary(
                OptimizeBenchLog.Summary(
                    engine = OptimizeBenchLog.ENGINE_REBUILD,
                    hard = finalReport.hard,
                    soft = finalReport.soft,
                    total = finalReport.total,
                    weighted = finalReport.weightedScore,
                    elapsedMs = elapsed,
                    seed = baseSeed,
                    workers = options.workers,
                    budgetMs = options.budgetMs,
                    phases = OptimizeBenchLog.snapshot().filter { it.engine == OptimizeBenchLog.ENGINE_REBUILD },
                ),
            )
            val doneIters = OptimizeBenchLog.snapshot()
                .filter { it.engine == OptimizeBenchLog.ENGINE_REBUILD }
                .sumOf { it.iters }
            onProgress?.invoke(
                SearchProgress("done", finalReport, doneIters, elapsed, art.schedule),
            )
            return ScheduleRunResult(
                schedule = art.schedule,
                report = finalReport.copy(
                    logs = finalReport.logs + OptimizeBenchLog.drainMirrorLogs(),
                ),
            )
        } finally {
            com.magi.app.v6.NativeFullEval.detach()
            android.util.Log.i("MAGI_FULL", com.magi.app.v6.NativeFullEval.stats())
            if (fullEvalHandle != 0L) {
                runCatching { com.magi.app.v6.NativeBridge.nativeDestroyProblem(fullEvalHandle) }
            }
            if (handle != 0L) runCatching { NativeBridge.nativeDestroyProblem(handle) }
        }
    }
}
