# MAGI Engine Rebuild（本番用）

勤務表最適化エンジン。試験・デモエントリは含まない。

## 入口
```kotlin
ObjectiveWeightsSource.install(hardKeys, softWeights)

val bridge = EngineWiring.forMain(
    problem = problemFromState(state),
    evaluate = { sch -> UnifiedViolationChecker.check(state, sch) },
    better = ::betterReport,
    legacyCellFix = ...,
    structural = ...,
    c1 = ...,
    c3 = ...,
    personal = ...,
)
val art = bridge.optimize(
    initial = state.schedule,
    totalBudgetMs = 300_000L,
    postReserveMs = 25_000L,
    seed = runSeed,
    shouldStop = { isCancelled() },
)
```

## パイプライン
G1 → G2 → G3 → G4 → PathRelink → RunArtifacts

## ドキュメント
- `docs/CONNECT_MAIN.md`
- `docs/WIRING.md`
- `docs/NEXT_ON_MAIN.md`
