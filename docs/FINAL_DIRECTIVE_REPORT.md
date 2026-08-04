# 最適化アルゴリズム 構造解体・破壊的再構築 — 最終報告

基準: 会話全体のコードトレース・敵対検証・実装成果（`magi-engine-rebuild`）  
対象: 最新 main 系勤務表最適化（SA / LAHC / ALNS・RSI / LateOperators / Hotfix / Native）

---

## 1. 解体・診断レポート

### 1.1 構成要素の分解

| 領域 | main 上の実体 | 役割 |
|------|---------------|------|
| 初期解 | hf66/hf67 系シード | 実行可能寄りの初期盤 |
| 近傍 | Single / Swap2 / WindowFill / DayLns / Block / Relink | 局所・構造変更 |
| 温度 | SA 幾何冷却・再加熱・複数 Annealing 変種 | 悪化受理制御 |
| 局所探索 | SA→LAHC、RSI/ALNS、V6LateOperators boost | 修復・多様化 |
| 後処理 | Hotfix C1/C3/Personal/Structural | 家族別磨き |
| 評価 | UnifiedViolationChecker / Delta / Native | hard→weighted→total |
| 状態 | static lastAlternatives 等 | Run 間で所有権が曖昧 |

### 1.2 ボトルネック・重複・空回り

| 問題 | 影響 | 判定 |
|------|------|------|
| `strongPerturbFlat` 等の**直接書き込み** | wish pin 破壊、原子性なし | **P0 廃止** |
| `opBlockFill` の lock 未検査 | 同上 | **P0 廃止** |
| LateOperators の **C1 boost（better 以外採否）** | 目的関数契約破壊 | **P0 廃止** |
| SA / LAHC / 複数冷却の重複経路 | 時間配分の空回り | **統合** |
| static 成果物 | UI・後処理で競合 | **RunArtifacts 化** |
| Kotlin/C++ 重み二重管理 | パリティ崩れ | **ObjectiveWeightsSource 単一化** |
| 全評価の頻発 | 速度 | **Delta/Native は同一 Move 契約の下で維持** |

### 1.3 精度貢献の見立て（論理プロファイル）

- **高**: 合法性不変（wish/canDo）・辞書式 betterReport・C1/coverage 修復・差分評価  
- **中**: SA 再加熱、Window/Day LNS、elite アーカイブ  
- **低〜負**: boost 採否、lock 無視摂動、重複スケジューラ、static 状態  

### 1.4 廃止・統合対象

| 対象 | 処置 | 理由 |
|------|------|------|
| 生書き込み摂動 / BlockFill 無検査 | 廃止 | 契約破壊 |
| better 以外の受理 | 廃止 | 目的悪化の恒久化 |
| 複数 Annealing 実装 | G1 に統合 | 重複 |
| G3Noop を本番既定 | BuiltinG3 に置換 | 空回り |
| Random のみの G2 | FocusAware に置換 | 寄与の低い近傍 |
| static alternatives | RunArtifacts | 所有権 |

---

## 2. 新アルゴリズム設計書

### 2.1 安全核（不変条件）

1. **合法性は全遷移で不変**（wish pin / canDo / 正規化済み writes）  
2. **目的悪化は current のみ**（ANNEAL/LAHC）。**best・elite・出力は betterReport のみ**  
3. **Move は原子的**（生成時に lock 除外済み。適用時スキップ禁止）  
4. **ExactCount FORBID_WORSEN** = 充足ピンの回帰のみ拒否  
5. **version は commit 成功時のみ進行**（Metropolis 却下は revert）

### 2.2 パイプライン

```
Stage0  Problem + Evaluator（重み表）
Stage1  Seed（本番: 既存 seed）
Stage2  G1 LocalAnnealer（SA → hard=0 で LAHC、再加熱）
Stage3  G2 FocusRepair（残差 focus + FocusAware / Legacy adapter）
Stage4  G3 FamilyPolish（Builtin STRICT 近傍 or Hotfix adapter）
Stage5  G4 Diversify（STRICT elite のみ出力候補）
Stage6  RunArtifacts（schedule, report, alternatives, stopReason）
```

予算: 探索 ≈ total−25s、後処理 25s（契約として再現後に適応化可）

### 2.3 評価エンジン

- `Evaluator` → `ViolationReport`（hard / soft / weightedScore / breakdown）  
- `betterReport`: hard → weightedScore → total  
- `ObjectiveWeightsSource`: 単一重み表（本番は MirrorKeys で上書き）  
- Session は `evaluate` 関数のみを知り、制約の直接上書きはしない  

### 2.4 モジュール（SOA）

| サービス | 単一責任 |
|----------|----------|
| Move / MoveBuilders / Normalizer | 合法手の生成・正規化 |
| SearchSessionFull | tryTransition / Metropolis / LAHC |
| TemperatureController | 冷却・再加熱・相遷移 |
| G1–G4 | 探索戦略 |
| SchedulerService | 時間配分とオーケストレーション |
| EngineFacade | 単一入口 |
| BuiltinRepair | スタンドアロン G2/G3 |
| adapters | main 既存オペレータ接続 |

### 2.5 敵対設計への応答

| 攻撃 | 対策 |
|------|------|
| 希望日の部分破壊 | WindowFill は lock が1つでもあれば手全体却下 |
| 温度却下後の状態不整合 | provisional + revert、version 不変 |
| exact 修復の全拒否 | 回帰のみ FORBID_WORSEN |
| 局所停滞 | 再加熱 + perturb（current のみ） |
| 壁 hard 残り | G3 STRICT 家族磨き + reseed |

---

## 3. リファクタリング済みコード

パッケージ: `magi-engine-rebuild/`（GitHub ZIP 同梱）

### 主要パス

| パス | 内容 |
|------|------|
| `src/Move.kt` / `MoveBuilders.kt` / `SearchSessionFull.kt` | 契約核 |
| `src/TemperatureControl.kt` / `G1LocalAnnealer.kt` | SA/LAHC |
| `src/G2FocusRepair.kt` / `BuiltinRepair.kt` | 修復 |
| `src/G3FamilyPolish.kt` / `G4Diversify.kt` | 後処理・elite |
| `src/SchedulerService.kt` / `EngineFacade.kt` | SOA 入口 |
| `src/domain/*` | Problem / Evaluator / Bootstrap |
| `src/adapters/*` | main 接続アダプタ |
| `patches/*` | main 接続手順 |

実行:

```bash
bash run-kotlin.sh 42
```

---

## 4. 性能比較（Benchmarking）

### 4.1 スタンドアロン実測（本サンドボックス）

| 指標 | 結果 |
|------|------|
| Pure 契約試験 | undo / bit / metro / norm / eval = **PASS** |
| G1 デモ seed=42 | hard **3→0**、soft 125→1、wish pin **不変** |
| Facade フルパイプライン | stop=FIXED_POINT、bestHard=0 |
| コンパイル | kotlinc 1.9.25、class 出力成功 |

※ デモ問題（S=8,T=14）上の論理検証であり、本番インスタンスの wall-clock 20% 短縮は **未計測**。

### 4.2 main 対比（接続後 CI で実証する項目）

| 項目 | 合格条件 |
|------|----------|
| 精度 | 1 worker 固定 seed で betterReport(新, 旧) が偽（新が悪くない） |
| 精度 | 並列は中央値・p95・最悪 hard/weighted |
| 速度 | 同一 budget で探索イテレーション増、または同一解品質までの時間 ≤ 0.8× |
| 契約 | 全 Move で wish/canDo/原子性、best 更新は better のみ |
| パリティ | Native・Delta・Checker 同一盤面一致 |

プロトコル: `scripts/benchmark_protocol.sh`、手順: `docs/PHASE4_SOA_BENCHMARK.md`

### 4.3 速度・精度の設計上の根拠（主対比）

- **速度**: 重複 Annealing・boost 分岐・無検査書き込み後の修復コストを削除。差分評価は契約下で維持。  
- **精度**: 目的外受理を禁止したことで「一時的 soft 改善・hard 悪化」の固定を防止。pin 不変で実行可能性を維持。  

---

## 結論

破壊的再構築の成果は「探索能力の削除」ではなく、**直接書き込み・例外採否・static 状態・重複時間配分の断捨離**と、**Move/tryTransition への収容**である。有用な SA・差分評価・家族磨き・Relink 相当は G1–G4 と adapter に残し、精度・速度は同等以上を **契約とスタンドアロン実証**で支え、main 実測は接続 CI で完了させる。
