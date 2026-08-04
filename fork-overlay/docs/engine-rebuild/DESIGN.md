# 大破壊的再構築 — 設計（会話履歴反映）

## 公理

1. 合法性は全遷移で不変（canDo / feasible wish pin）
2. 目的悪化は current のみ（ANNEAL/LAHC）
3. best / elite / 出力は betterReport のみ
4. 原子性 = 正規化 writes 全体（適用時スキップ禁止）
5. C3n は目的 HARD（静的違法ではない）
6. wish pin ≠ 回数ピン（ExactCountPolicy）
7. ValidBridge ≠ 出力 elite
8. Scheduler 初期 = 探索 275s + 後処理 25s（300s 時）

## 断捨離

| 廃止 | 理由 |
|------|------|
| strongPerturbFlat 生書き込み | pin 破り |
| BlockFill 適用時スキップ | 偽原子性 |
| c1 boost | 規約外採否 |
| V5/ALNS/RSI/PORTFOLIO 別エンジン | G1–G4 に畳む |
| static 成果物を正本に | RunArtifacts |
| 秒の均等化 | 実測否決 |

## 分解 → 再配置

| 旧 | 新 |
|----|-----|
| V5 SA/LAHC | G1 |
| RSI/ALNS/GLS | G2 |
| 後処理16段 | G3.* ファサード（中身は既存） |
| Elite/Relink | G4 |
| AUTO/PORTFOLIO 配分 | SchedulerService |

## 精度・速度

- 精度: keep-best + 番兵 + 1w seed 非劣化 + 並列最悪値
- 速度: 無効手排除 + Native writes バッチ + ビット（MISSING 参照）
- D0–D3 は非劣化を保証しにいく。畳み込みは測定まで「同等」と書かない
