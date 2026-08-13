package com.magi.app.v6.engine.integration

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.betterReport
import com.magi.app.v6.engine.BuiltinScheduleImprover
import com.magi.app.v6.engine.EngineFacade
import com.magi.app.v6.engine.ObjectiveWeightsSource
import com.magi.app.v6.engine.ProblemGuards
import com.magi.app.v6.engine.WeightAuditLog
import com.magi.app.v6.engine.EngineOptions
import com.magi.app.v6.engine.FocusAwareFixProvider
import com.magi.app.v6.engine.FocusFixProvider
import com.magi.app.v6.engine.FullyWiredG3Backend
import com.magi.app.v6.engine.G3Backend
import com.magi.app.v6.engine.RunArtifacts
import com.magi.app.v6.engine.SearchProgressListener
import com.magi.app.v6.engine.adapters.FocusFixAdapter
import com.magi.app.v6.engine.adapters.HotfixG3Backend
import com.magi.app.v6.engine.adapters.LegacyCellFix
import com.magi.app.v6.engine.adapters.ScheduleImprover
import java.util.Random

/**
 * 本番 optimize の単一ブリッジ。
 *
 * - fix: LegacyCellFix があれば優先、なければ FocusAware
 * - g3: Hotfix 注入があれば HotfixG3Backend、なければ FullyWiredG3Backend
 */
class MainOptimizeBridge(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean = ::betterReport,
    private val legacyCellFix: LegacyCellFix? = null,
    private val structural: ScheduleImprover? = null,
    private val c1: ScheduleImprover? = null,
    private val c3: ScheduleImprover? = null,
    private val personal: ScheduleImprover? = null,
    private val deltaHook: com.magi.app.v6.engine.DeltaEvaluateHook? = null,
    private val workers: Int = 1,
    private val nativeProbe: com.magi.app.v6.engine.nativex.NativeWritesProbe? = null,
    private val progressListener: SearchProgressListener? = null,
) {
    fun optimize(
        initial: Array<IntArray>,
        totalBudgetMs: Long = 300_000L,
        postReserveMs: Long = 25_000L,
        seed: Long = 0L,
        shouldStop: () -> Boolean = { false },
        infeasibleFamilies: Set<String> = emptySet(),
        provenHardFloor: Int = 0,
        workers: Int = this.workers,
        progressListener: SearchProgressListener? = this.progressListener,
    ): RunArtifacts {
        ObjectiveWeightsSource.ensureDefaults()
        WeightAuditLog.logTable()
        if (!ProblemGuards.isRunnable(problem)) {
            val rep = evaluate(initial)
            return com.magi.app.v6.engine.RunArtifacts(schedule = initial, report = rep)
        }
        // ホットパス: Native 差分のみ（フル評価は hook 内の失敗/パリティ時）
        val effectiveDelta = deltaHook ?: run {
            val probe = nativeProbe
            if (probe is com.magi.app.v6.engine.nativex.NativeBridgeProbe &&
                com.magi.app.v6.NativeGate.usable &&
                com.magi.app.v6.NativeBridge.available
            ) {
                val hook = com.magi.app.v6.engine.nativex.NativeDeltaEvaluateHook(problem, probe, evaluate)
                android.util.Log.i("MAGI_DELTA", "hotpath=native-delta-only probe=ok")
                hook
            } else {
                android.util.Log.w(
                    "MAGI_DELTA",
                    "hotpath=full-eval (native delta unavailable usable=${com.magi.app.v6.NativeGate.usable})",
                )
                null
            }
        }
        val facade = EngineFacade(
            problem = problem,
            evaluate = evaluate,
            better = better,
            g3Backend = buildG3(seed),
            fixProvider = buildFixProvider(),
        )
        val art = facade.optimize(
            initial = initial,
            options = EngineOptions(
                totalBudgetMs = totalBudgetMs,
                postReserveMs = postReserveMs,
                seed = seed,
                shouldStop = shouldStop,
                infeasibleFamilies = infeasibleFamilies,
                provenHardFloor = provenHardFloor,
                deltaHook = effectiveDelta,
                workers = workers,
                nativeProbe = nativeProbe,
                progressListener = progressListener,
            ),
        )
        if (effectiveDelta is com.magi.app.v6.engine.nativex.NativeDeltaEvaluateHook) {
            android.util.Log.i("MAGI_DELTA", "end ${effectiveDelta.statsLine()}")
        }
        return art
    }

    private fun buildFixProvider(): FocusFixProvider {
        val aware = FocusAwareFixProvider(problem)
        val legacy = legacyCellFix ?: return aware
        val adapter = FocusFixAdapter(problem, legacy)
        return FocusFixProvider { focus, board, prob, rng ->
            adapter.propose(focus, board, prob, rng) ?: aware.propose(focus, board, prob, rng)
        }
    }

    private fun buildG3(seed: Long): G3Backend {
        val hasHotfix = structural != null || c1 != null || c3 != null || personal != null
        if (!hasHotfix) {
            return FullyWiredG3Backend(problem, evaluate, better, Random(seed xor 0xC3L))
        }
        return HotfixG3Backend(
            better = better,
            evaluate = evaluate,
            structural = structural ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.STRUCTURAL, Random(seed xor 1L),
            ),
            c1 = c1 ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.C1, Random(seed xor 2L),
            ),
            c3 = c3 ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.C3, Random(seed xor 3L),
            ),
            personal = personal ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.PERSONAL, Random(seed xor 4L),
            ),
        )
    }
}
