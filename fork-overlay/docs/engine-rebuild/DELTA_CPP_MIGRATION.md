# 差分評価の C++ 移行

## 方針
- **正本**: Kotlin `Evaluator` / `UnifiedViolationChecker`（最終採否・UI）
- **高速パス**: C++ `SaChunk.deltaApply`（探索中のスコア試算）

## API
| JNI | 用途 |
|-----|------|
| `nativeTryWrites` | writes 適用 + 差分スコア + 採否。受理時のみ盤面書戻し |
| `nativeDeltaEval` | 差分スコアのみ（盤面非破壊） |
| `nativeSaChunk` 等 | SA/LAHC/ALNS/Polish チャンク（既存） |

## Kotlin
- `NativeDeltaBridge.scoreAfterWrites` → 失敗時 null → Kotlin へ退化
- `NativeParityGate` で不一致時はプロセス内 native OFF

## 旧→新
| 旧 | 新 |
|----|-----|
| tryWrites で fullEval×2 | SaChunk 初期化1回 + deltaApply×セル数 |
| 共有 handle 並列 | ワーカー専用 handle |
