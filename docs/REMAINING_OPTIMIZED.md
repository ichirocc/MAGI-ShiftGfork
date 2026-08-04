# 残作業の最適化 — 対応状況

## P1（実装済）
| # | 作業 | 実装 |
|---|------|------|
| 4 | Native 拒否スキップ | `NativeRejectSkipGate` + `tryTransitionStrictWithOptionalNativeSkip`（STRICT のみ、0.1% 不一致で許可、不一致増で自動閉鎖）G2 に配線 |
| 5 | C1 下界 | `C1SuffixLowerBound` を goal 優先度に統合 |
| 6 | 並列 p95 本番枠 | `ParallelBenchmark.runProduction(budgetMs=300_000)` / `compareWorkers` |

## P2（実装済）
| # | 作業 | 実装 |
|---|------|------|
| 7 | BuildConfig 切替 | `buildConfigField REBUILD_ENGINE` + `applyBuildConfigDefault()`（ViewModel init） |
| 8 | 途中 schedule スナップショット | `SearchProgress.schedule` + Worker 8s 退避 |
| 9 | docs 同期 | 本ファイル |

## P3（運用・手元）
| # | 作業 | 担当 |
|---|------|------|
| 10 | assembleDebug / NDK ロード | 手元 SDK |
| 11 | REBUILD_ENGINE=true で golden 比較 | 手元 |
| 12 | runProduction を短縮 budget で CI | CI 設定 |

## 使い方
```kotlin
// build.gradle.kts
buildConfigField("Boolean", "REBUILD_ENGINE", "true") // 段階ロールアウト

// 計測
ParallelBenchmark.runProduction(workers=4, seeds=3, budgetMs=30_000) // CI 短縮例
println(GlobalNativeSkipGate.gate.stats())
```
