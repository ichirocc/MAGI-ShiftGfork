# Worker 差し替え

## 有効化
```kotlin
RebuildOptimizeEntry.enabled = true
```
またはデバッグ起動時:
```kotlin
// Application.onCreate
com.magi.app.v6.engine.integration.RebuildOptimizeEntry.enabled = true
```

## 既定
`enabled = false` → 従来 `V6FinalPort.handleOptimize`

## 有効時の流れ
OptimizationWorker
  → RebuildOptimizeEntry.optimize
  → V6RebuildPort（Native auto + G1..G4）
  → publishResult / notifyDone / 結果ファイル原子書き込み

所有権（runId）・失敗時 clearFiles は従来どおり finally で処理。
