package com.magi.app.v6.engine.integration

import com.magi.app.model.MagiState
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.NativeBridge
import com.magi.app.v6.NativeEval
import com.magi.app.v6.Problem
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.betterReport
import com.magi.app.v6.engine.EngineWiring
import com.magi.app.v6.engine.ObjectiveWeightsSource
import com.magi.app.v6.engine.SearchProgressListener
import com.magi.app.v6.engine.adapters.LegacyCellFix
import com.magi.app.v6.engine.adapters.ScheduleImprover
import com.magi.app.v6.engine.DeltaEvaluateHook
import com.magi.app.v6.engine.nativex.NativeBridgeProbe
import com.magi.app.v6.engine.nativex.NativeWritesProbe

object V6RebuildPort {

    fun ensureWeights(softWeights: Map<String, Double>? = null) {
        synchronized(this) {
            val hard = MirrorKeys.hard
            val soft = softWeights ?: MirrorKeys.soft.associateWith { 1.0 }
            ObjectiveWeightsSource.install(hard, soft)
        }
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
        ensureWeights(softWeights)
        val problem = Problem(state)
        val evaluate: (Array<IntArray>) -> ViolationReport = { sch ->
            UnifiedViolationChecker.check(state, sch)
        }
        val fix = legacyCellFix
            ?: if (useLegacyFixes) MainLegacyFix.create(problem, schedule) else null

        var handle = 0L
        val probe: NativeWritesProbe? = when {
            nativeProbe != null -> nativeProbe
            autoNative && NativeBridge.available -> {
                handle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
                if (handle != 0L) NativeBridgeProbe(handle) else null
            }
            else -> null
        }

        try {
            val art = EngineWiring.forMain(
                problem = problem,
                evaluate = evaluate,
                better = ::betterReport,
                legacyCellFix = fix,
                structural = structural,
                c1 = c1,
                c3 = c3,
                personal = personal,
                deltaHook = deltaHook,
                workers = workers,
                nativeProbe = probe,
                progressListener = progressListener,
                requireWeights = true,
            ).optimize(
                initial = schedule,
                totalBudgetMs = budgetMs,
                postReserveMs = postReserveMs,
                seed = seed,
                shouldStop = shouldStop,
            )
            return ScheduleRunResult(schedule = art.schedule, report = art.report)
        } finally {
            if (handle != 0L) runCatching { NativeBridge.nativeDestroyProblem(handle) }
        }
    }
}
