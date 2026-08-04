# Phase 1 — 解体・診断レポート

基準: main `3.335.0-run-scoped-outputs`

## 1.1 構成要素の分解

### 入口
| 部品 | 役割 |
|------|------|
| handleOptimize → V6NativeOptimizer.optimize | 本最適化 |
| SmartInitial / GreedyMirror | 初期解 |
| runPostOptimization | 後処理研磨 |
| FixSuggester | 手動提案（自動探索に混ぜない） |

### 本探索ブランド
| 名前 | 構成要素 |
|------|----------|
| V5 | 並列 SA、温度 α 冷却、任意 LAHC、Conductor 再加熱、Native チャンク |
| ALNS | destroy/repair、演算子重み、SA/GreatDeluge/Lam、Tabu、GLS |
| RSI / RSI+ | 最大違反族フォーカス、+ は seed→仮説→refine 長量子 |
| PORTFOLIO | 8 役割 epoch 再配属、elite、距離、relink |
| AUTO | ≤30s V5 / ≤210s RSI→ALNS / それ以上 PORTFOLIO |

### 近傍
Single, SwapDays, BlockFill, LNS, destroy 集合, focus fix, Rect/Chain/Block, 玉突き, strongPerturb

### 評価
UnifiedViolationChecker（正式）, Evaluator, DeltaEvaluator, Native fullEval  
採否: betterReport = HARD → weightedScore → total

### 後処理
前段 HF80/67/66/Hungarian → 巡回 C1/C3/Range/Block/Apt/Fair → 後段 weekly/Joint LNS 等（台帳上 16 段級）

## 1.2 コスト vs 精度寄与（設計・実測ログに基づく）

| 部品 | 時間 | 精度寄与 | 判定 |
|------|------|----------|------|
| Native SA チャンク | 高（有効） | 高 | 残す |
| pin 未検査近傍 | 高（浪費 30%+） | 負 | 撤廃 |
| RSI+ 長量子 | 高 | 高（worker 秒過半） | G2 長モード |
| 短量子 ALNS 役割 | 中 | 低〜中 | 統合・計測縮退 |
| 後処理 keep-best | 中 | 中（入口依存） | G3 ファサード |
| c1 boost | 低 | 局所+/全体リスク | 撤廃 |
| PORTFOLIO×AUTO 二重配分 | 境界コスト高 | 中 | Scheduler 1 本 |
| 評価重み二重定義 | — | ドリフト時致命 | 単一生成源 |
| static 成果物 | — | 混線 | RunArtifacts |

## 1.3 ボトルネック・空回り

1. 合法性を採点で却下（生成前に落とすべき）
2. 同型探索器の多重実装と時間哲学の重複
3. 0 改善パスの固定費
4. 構造的 HARD へのフォーカス粘着
5. hard=0 早期キャンセルが soft 改善を殺す可能性

## 1.4 廃止・統合対象

| 対象 | 処置 | 理由 |
|------|------|------|
| strongPerturbFlat 生書き込み | 廃止 | wish pin 破り |
| BlockFill 適用時 lock スキップ | 廃止 | 偽原子性 |
| c1 boost | 廃止 | betterReport 外 |
| V5/ALNS/RSI/PORTFOLIO 別エンジン | G1–G4 に統合 | 重複 |
| AUTO エンジン切替 | Scheduler prior | 探索器ではない |
| static last* を正本に | RunArtifacts | 所有権 |
| 後処理順序の無断変更 | 禁止 | 精度後退 |
| staffPacked / 重みの勝手な変更 | 禁止 | 意味変更 |
| 既定 OFF 実験の本線化 | 禁止 | A/B 否決済 |
