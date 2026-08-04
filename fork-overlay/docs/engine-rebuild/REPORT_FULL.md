# 最適化アルゴリズム 構造解体・破壊的再構築 — 総合報告

指示書の出力フォーマット 1–4 に対応する。

---

## 1. 解体・診断レポート

詳細: `PHASE1_DIAGNOSIS.md`

要点:
- 本探索は V5/ALNS/RSI/PORTFOLIO の多重ブランドで、採否は betterReport に寄っている一方、生書き込み・boost・無効近傍が効率を削る。
- 廃止: perturb 生書き込み、BlockFill 部分適用、c1 boost、static 正本、別エンジンとしてのブランド維持。
- 統合: G1–G4 + Scheduler。能力核（Native SA、C1 flow、Range、BitScan）は残す。

---

## 2. 新アルゴリズム設計書

詳細: `DESIGN.md`, `PHASE2_PRUNE.md`, `PHASE3_ADVERSARIAL.md`

### パイプライン

```
Stage0  Normalize / Sanity
Stage1  Seed（hf67 は betterReport 時のみ）
Stage2  Deadline(275s) → G1 | G2 | G4
Stage3  予約 25s → G3.* STRICT
Stage4  Explain（StopReason）
```

### 評価エンジン

- 正式: UnifiedViolationChecker + betterReport
- 探索: Delta/Native（同一重み・接続は MISSING）
- 遷移: SearchSessionFull（合法性再検証・原子 writes）

### 公理（短縮）

合法性不変 / 悪化は current のみ / best は betterReport / 正規化原子性 / wish≠回数ピン / bridge≠出力

---

## 3. リファクタリング済みコード

パス: `/home/workdir/artifacts/magi-engine-rebuild/src/`

| ファイル | 内容 |
|----------|------|
| BoardView.kt | 盤面ビュー |
| Move.kt | Move, 列挙, RunArtifacts |
| UndoStack.kt | 原子 revert |
| MoveBuilders.kt | 正規化生成 |
| SearchSessionFull.kt | tryTransition / Metropolis / LAHC |
| G1LocalAnnealer.kt | 旧 V5 |
| G2FocusRepair.kt | 旧 RSI/ALNS |
| G3FamilyPolish.kt | 後処理ファサード |
| G4Diversify.kt | elite / bridge |
| SchedulerService.kt | 275+25 配分 |

接続: `patches/CONNECT_MAIN.md`

注意: 既存 main の Problem 等に依存。単体ではアプリ全体を置換しない。P0 パッチ（生書き込み・boost）を main 側に当ててから段階委譲する。

---

## 4. 性能比較（Benchmarking）

詳細: `PHASE4_SOA_BENCHMARK.md`

| 項目 | 結果 |
|------|------|
| 実行速度 main vs 新 | **未計測**（プロトコルのみ定義） |
| 解の精度 | **未計測**（非劣化ゲート定義済み） |
| 20% 短縮 | **努力目標。達成宣言なし** |

実証可能な品質主張（設計上）:
- 無効手事前排除により同秒の有効遷移が増える方向
- keep-best + 番兵 + betterReport 一本化で最終悪化経路を塞ぐ方向
- 実数は CI/実機の後にのみ記載する

---

## Phase 完了状況

| Phase | 状態 |
|-------|------|
| 1 解体 | 文書完了 |
| 2 断捨離 | 方針・コード契約完了。main 適用は CONNECT |
| 3 敵対再設計 | 文書 + Session/G1–G4 完了。ビット実装は MISSING |
| 4 SOA | コード完了。ベンチ実証は未実施 |
