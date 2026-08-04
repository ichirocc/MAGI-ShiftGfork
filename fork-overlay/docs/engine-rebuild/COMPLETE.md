# 不足補完（完成）

## 追加したもの
| ファイル | 役割 |
|----------|------|
| `V6RebuildPort` | MagiState → EngineWiring の単一入口 |
| `ProblemEngineExtensions` | main Problem に canDo/wishLocked/dayDemand 等 |
| `engine/config/ConstraintConfig` | 研磨ヒント用（Checker が正） |
| `PackedScore` | main の Double weightedScore 対応 |

## 呼び出し
```kotlin
val result = V6RebuildPort.optimize(
    state = state,
    schedule = schedule.copy2D(),
    budgetMs = 300_000L,
    seed = runSeed,
    shouldStop = { isCancelled() },
)
```

## まだ後続でよいもの
- Native/Delta の Move バッチ評価
- C1JointLNS 本文のエンジン内再実装（Improver 注入で代替可）
- 並列 SA
- V6FinalPort からの既定切替トグル
