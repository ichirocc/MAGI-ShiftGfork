# 技術的負債 修正ログ

| 項目 | 処置 |
|------|------|
| G3 Noop 既定 | 廃止。backend 必須 |
| hard キー二重 | ObjectiveWeightsSource.hardKeys() |
| CoverageDeltaHook | フル評価と明記 |
| maxTriesPerKey | 削除 |
| focus 未使用警告 | @Suppress |
| identity 制約マップ | WeakHashMap + ConstraintExtras |
| reseed | replaceBestIfBetter 正式 API |
| WiringDoc | 削除 |
| SessionWithDelta | @Deprecated |
