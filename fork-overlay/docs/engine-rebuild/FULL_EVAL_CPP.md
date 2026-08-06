# フル評価の C++ 移行

## 対象
| 処理 | 実装 |
|------|------|
| `Evaluator.fullEval` / `fullEvalParts` | C++ `nativeFullEval` 優先 |
| SA/LAHC/ALNS チャンク内 | 既存 C++（delta + チャンク末 full 照合） |
| `UnifiedViolationChecker`（セル位置・内訳） | **Kotlin のまま**（UI/診断用） |

## 流れ
1. `NativeFullEval.attach(handle)`（最適化開始）
2. `Evaluator.fullEvalParts` → `NativeFullEval.tryParts` → `nativeFullEval`
3. 失敗時のみ Kotlin フル評価
4. `detach()` + 統計ログ `MAGI_FULL`

## 正本
- 数値スコア: C++（パリティ一致時）
- 不一致: `NativeParityGate` が native を閉じ、Kotlin のみ
- レポート詳細: 常に Kotlin Checker
