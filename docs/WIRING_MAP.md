# 本番配線図（MAGI-ShiftGfork）

```
OptimizationWorker / MagiViewModel
  └─ RebuildOptimizeEntry.enabled? (BuildConfig.REBUILD_ENGINE)
        ├─ true  → RebuildOptimizeEntry.optimize
        │            └─ FullOptimizePipeline
        │                 ├─ ObjectiveWeightsSource.install + WeightAuditLog.logTable
        │                 ├─ ProblemGuards
        │                 ├─ EngineWiring.forMain → MainOptimizeBridge
        │                 │     └─ EngineFacade
        │                 │          └─ SchedulerService
        │                 │               G1 → SimpleRsi → AlnsPolish → VnsPolish → G3 → G4
        │                 └─ WeightAuditLog.logContribution(final)
        └─ false → V6FinalPort.handleOptimize（上流）
```

## ガード配線

| 層 | 内容 |
|----|------|
| EngineFacade | ProblemGuards + ensureDefaults + 形状正規化 |
| MainOptimizeBridge | ensureDefaults + WeightAudit + ProblemGuards |
| FullOptimizePipeline | install weights + audit + ProblemGuards |
| SearchSessionFull | shape/requireRunnable in init、ANNEAL HARD ゲート |
| G1 / SimpleRsi | isRunnable + zero-budget |

## ログ配線

- `MAGI_VERSION` / `MAGI_BENCH` / `MAGI_WEIGHTS` / `MAGI_WEIGHT_CONTRIB` / `MAGI_WEIGHT_AUDIT`
- バブル: BubbleSupport（Worker）
