# 本番配線 v3.1.0

```
ObjectiveWeightsSource.install(hard, soft)
        │
EngineWiring.forMain(problem, evaluate=Checker, hotfixes?)
        │
MainOptimizeBridge
  ├─ FocusFixProvider = Legacy? ?: FocusAware
  └─ G3Backend = Hotfix? ?: FullyWired
        │
EngineFacade → SchedulerService
  G1 → G2 → G3(ConstraintPolish+VNS+ALNS + backend, absorbG3Step)
     → G4 → PathRelink → RunArtifacts
```

唯一の公開入口: `EngineWiring.forMain` → `optimize(...)`
