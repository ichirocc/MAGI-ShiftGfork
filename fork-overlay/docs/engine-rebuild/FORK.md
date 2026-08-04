# Engine Rebuild Fork Overlay

- `app/.../v6/engine/` … 再構築エンジン
- `engine/domain_ref/` … 参照用（main の Problem/Checker と衝突し得るため接続時は evaluate 注入を優先）

```kotlin
ObjectiveWeightsSource.install(hardKeys, softWeights)
EngineWiring.forMain(problem, evaluate = { Checker... }).optimize(initial, 300_000L, seed)
```
