package com.magi.app.v6.engine

/**
 * 完成パイプラインの宣言的記述（実行は EngineFacade / SchedulerService）。
 *
 * Stage0  Normalize / Sanity          … main 既存
 * Stage1  Seed (hf66/hf67 betterのみ) … main 既存
 * Stage2  G1 → G2 → G4.considerStrict … SchedulerService
 * Stage3  G3 families + onFamilyDone reseed
 * Stage4  RunArtifacts (alternatives = STRICT elite only)
 */
object EnginePipeline {
    const val VERSION: String = "rebuild-1.0.0-complete"

    val stages: List<String> = listOf(
        "normalize",
        "seed",
        "g1_local_annealer",
        "g2_focus_repair",
        "g3_family_polish",
        "g4_diversify_archive",
        "emit_run_artifacts",
    )
}
