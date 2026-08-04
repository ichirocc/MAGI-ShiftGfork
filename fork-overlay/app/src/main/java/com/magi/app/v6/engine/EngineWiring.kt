package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.betterReport
import com.magi.app.v6.engine.adapters.LegacyCellFix
import com.magi.app.v6.engine.adapters.ScheduleImprover
import com.magi.app.v6.engine.integration.MainOptimizeBridge

/**
 * 本番配線の唯一の入口。
 *
 * 必須:
 * 1. ObjectiveWeightsSource.install(...) を先に呼ぶ
 * 2. evaluate に本番 Checker を渡す
 */
object EngineWiring {

    const val VERSION = "rebuild-prod-3.3.0"

    fun stages(): List<String> = listOf(
        "g1_sa_lahc",
        "g2_focus_repair",
        "g3_constraint_polish_vns_alns",
        "g3_family_backend",
        "g4_elite",
        "path_relink",
        "run_artifacts",
    )

    /**
     * @param problem 本番 Problem（wishLock / canDo / range / demand 済み）
     * @param evaluate 本番 UnifiedViolationChecker 等
     */
    fun forMain(
        problem: Problem,
        evaluate: (Array<IntArray>) -> ViolationReport,
        better: (ViolationReport, ViolationReport) -> Boolean = ::betterReport,
        legacyCellFix: LegacyCellFix? = null,
        structural: ScheduleImprover? = null,
        c1: ScheduleImprover? = null,
        c3: ScheduleImprover? = null,
        personal: ScheduleImprover? = null,
        deltaHook: DeltaEvaluateHook? = null,
        workers: Int = 1,
        nativeProbe: com.magi.app.v6.engine.nativex.NativeWritesProbe? = null,
        progressListener: SearchProgressListener? = null,
        requireWeights: Boolean = true,
    ): MainOptimizeBridge {
        if (requireWeights) {
            check(ObjectiveWeightsSource.isReady()) {
                "Call ObjectiveWeightsSource.install(hardKeys, softWeights) before EngineWiring.forMain"
            }
        }
        return MainOptimizeBridge(
            problem = problem,
            evaluate = evaluate,
            better = better,
            legacyCellFix = legacyCellFix,
            structural = structural,
            c1 = c1,
            c3 = c3,
            personal = personal,
            deltaHook = deltaHook,
            workers = workers,
            nativeProbe = nativeProbe,
            progressListener = progressListener,
        )
    }
}
