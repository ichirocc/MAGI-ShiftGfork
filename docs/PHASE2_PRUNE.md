# Phase 2 — 断捨離（完全剪定）方針

## 撤廃する例外・重複パス

- 探索経路の `schedule[i][j] =` 直接代入
- 適用時に希望セルだけ飛ばす部分適用
- betterReport 外の boost
- 手書き hard→total ソート（alternatives）
- ブランドごとの独自採否比較

## 平坦化

- 採否・合法性は SearchSessionFull.tryTransition に集約
- 近傍は MoveBuilders のみ（ネストした「特別な書き込み」禁止）
- 時間会計は SchedulerService の Deadline のみ

## 制約・希望の扱い

- 処理途中の希望上書き禁止
- feasible wish pin = 全遷移で不変
- 評価は一元 betterReport（重み付きスコア）
- 回数ピン lo==hi は ExactCountPolicy（既定 FORBID_WORSEN）
