# Kotlin / C++ 棲み分け契約（MAGI-ShiftGfork）

最終更新: 2026-08-06  
版: 3.346.0-cpp-full-eval 以降

---

## 1. 原則（1行）

| 層 | 言語 | 一言 |
|----|------|------|
| **契約・状態・UI・診断** | Kotlin | 正しさの正本 |
| **数値スコアのホットパス** | C++ | 速さのみ（正しさは Kotlin と一致必須） |

C++ は「加速器」であり「仕様の所有者」ではない。

---

## 2. Kotlin が担うもの（正本）

| 領域 | 代表 | 理由 |
|------|------|------|
| 問題・盤面モデル | `Problem`, `MagiState`, schedule | アプリデータモデル |
| Move / Session 契約 | `Move`, `SearchSessionFull`, `tryTransition` | version・undo・best 更新 |
| 採否規則 | `betterReport`, HARD ゲート, STRICT/ANNEAL/LAHC | 仕様・監査可能 |
| 希望ピン / canDo | `wishLocked`, `canDo` | 合法性の正本 |
| 違反の**場所と説明** | `UnifiedViolationChecker` → `ViolationReport` | UI・診断ログ |
| 最適化パイプライン | `FullOptimizePipeline`, G1–G4, Worker | オーケストレーション |
| 設定・トグル永続化 | `OptimizeToggleStore`, ViewModel | Android ライフサイクル |
| パリティ監視 | `NativeParityGate`, `NativeFullEval` 統計 | 不一致時に C++ を閉じる |
| Native 利用可否 | `NativeGate`, `NativeBridge.available` | 退化の制御 |

**禁止（Kotlin 側）**

- C++ のスコアだけで `best` を更新する（必ず `betterReport` / Session 経由）
- 希望ピンを C++ 結果で黙って上書きする

---

## 3. C++ が担うもの（加速）

| 領域 | 代表 JNI / 構造 | 理由 |
|------|-----------------|------|
| フル数値評価 | `nativeFullEval` ← `fullEvalParts` | hard/soft の高速算出 |
| 差分数値評価 | `SaChunk.deltaApply`, `nativeDeltaEval`, `nativeTryWrites` | 1手試算 |
| SA 冷却チャンク | `nativeSaChunk` | 並列チェーン |
| LAHC / ALNS / Polish チャンク | `nativeLahc*`, `nativeAlns*`, `nativePolish*` | 同系統ホットパス |

**入力:** 平坦化した Problem + schedule（`NativeEval.createHandle`）  
**出力:** hard / soft / packed score、必要なら盤面フラット配列  
**出さない:** セル別違反マップ、日本語診断、UI 状態

**禁止（C++ 側）**

- best の所有（Kotlin Session のみ）
- ログの正本（診断は Kotlin）
- パリティ不一致のまま探索を継続（`NativeGate.disable`）

---

## 4. データの流れ

```text
[UI / Worker]
    → Kotlin Pipeline（予算・G1–G4・停止）
        → Session.tryTransition / tryMetropolis
            → （任意）C++ delta / tryWrites で早期棄却・ヒント
            → Kotlin Checker で ViolationReport（受理時の正本）
            → betterReport でのみ best 更新

[Evaluator.fullEvalParts]
    → NativeFullEval.tryParts → C++ nativeFullEval
    → 失敗時のみ Kotlin Evaluator 本体
```

---

## 5. パリティ契約

1. 同一 Problem・同一盤面で  
   `C++ hard/soft == Kotlin Evaluator.fullEvalParts`
2. 1回でも不一致 → そのプロセスでは native OFF（Kotlin のみ）
3. Checker の `total` / 内訳件数と Evaluator soft は**別指標**（混同しない）

---

## 6. 退化マトリクス

| 状況 | 動作 |
|------|------|
| `.so` なし / ABI 不一致 | 全パス Kotlin |
| パリティ NG | `NativeGate.disable` → Kotlin |
| JNI 例外 | 捕捉して Kotlin（プロセスを落とさない） |
| 差分ドリフト（delta ≠ full） | その手は full で再評価 or reject |

---

## 7. 今後の変更ルール

| やりたいこと | どこに書く |
|--------------|------------|
| 新しい制約の**意味・表示** | Kotlin Checker + weights |
| その制約の**スコア高速化** | C++ `fullEvalParts` / `deltaApply` に**同じ式**を移植 |
| 探索メタ戦略（温度・予算） | Kotlin のみ |
| 近傍の数学的定義 | まず Kotlin、ホットなら C++ に複製 |

**式を変えるときは必ず Kotlin と C++ を同時に直し、パリティ CI を通す。**

---

## 8. ファイル対応表（概要）

| Kotlin | C++ |
|--------|-----|
| `Evaluator.kt` | `magi_native.cpp` `fullEvalParts` |
| `DeltaEvaluator` 相当 | `SaChunk.deltaApply` |
| `SaOptimizer` 制御 | `nativeSaChunk` 等 |
| `SearchSessionFull` | （状態は持たない。試算のみ） |
| `UnifiedViolationChecker` | なし（移植しない） |
| `NativeBridge` / `NativeFullEval` | JNI 境界 |

---

## 9. 一言まとめ

> **Kotlin = 何が正しいか。C++ = 同じ計算を速くする。**  
> 速さと正しさが衝突したら、正しさ（Kotlin + パリティ）を取る。
