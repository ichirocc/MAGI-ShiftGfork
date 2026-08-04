# フォークを実行可能にする

## 1. Android SDK
```bash
cp local.properties.example local.properties
# sdk.dir=/path/to/Android/Sdk を編集
export ANDROID_HOME=/path/to/Android/Sdk
```

## 2. ビルド
```bash
cd magi-fork
bash gradlew :app:assembleDebug
```

## 3. 最適化の呼び出し
```kotlin
import com.magi.app.v6.engine.integration.V6RebuildPort
// または RebuildOptimizeEntry

val result = V6RebuildPort.optimize(
    state = state,
    schedule = schedule,
    budgetMs = 300_000L,
    seed = seed,
    shouldStop = { !coroutineContext.isActive },
)
// result.schedule / result.report
```

## 4. Worker 差し替え
`OptimizationWorker` の `V6FinalPort.handleOptimize` の代わりに:

```kotlin
if (RebuildOptimizeEntry.enabled) {
    RebuildOptimizeEntry.optimize(state, schedule, budgetSec, seed) { isStopped }
} else {
    V6FinalPort.handleOptimize(...)
}
```

## 検証済み（この環境）
- エンジン核 + スタンドアロン domain の kotlinc **EXIT:0**
- Gradle は SDK 未設定のため configure で停止（`local.properties` が必要）

## 配線済み
- `ProblemEngineExtensions` … canDo / wishLocked / dayDemand
- `MainLegacyFix` … findCovO/C2/Range/Apt
- `PackedScore` … Double weightedScore
- `V6RebuildPort` … Checker + EngineWiring
