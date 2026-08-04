# main 接続

```kotlin
ObjectiveWeightsSource.install(
    hardKeys = listOf("covU", "shiftU", "exact", "illegal", "lockBreak", "c3n", "c1", "groupViol"),
    softWeights = mapOf("covO" to 10.0, "pref" to 3.0, /* ... */),
)

val bridge = EngineWiring.forMain(
    problem = problemFrom(state),
    evaluate = { sch -> UnifiedViolationChecker.check(state, sch) },
    better = ::betterReport,
    legacyCellFix = LegacyCellFix { focus, board, problem, rng -> findFix(focus, ...) },
    structural = ScheduleImprover { sch, dl -> runBlockCycle(state, sch, dl) },
    c1 = ScheduleImprover { sch, dl -> runC1(state, sch, dl) },
    c3 = ScheduleImprover { sch, dl -> runC3(state, sch, dl) },
    personal = ScheduleImprover { sch, dl -> runPersonal(state, sch, dl) },
)

val art = bridge.optimize(
    initial = state.schedule,
    totalBudgetMs = 300_000L,
    postReserveMs = 25_000L,
    seed = runSeed,
    shouldStop = { isCancelled() },
)
// art.schedule / art.report / art.alternatives
```

## P0（main 側）
- 生書き込み禁止（Move + tryTransition のみ）
- boost で best を更新しない
- static 成果物 → RunArtifacts
