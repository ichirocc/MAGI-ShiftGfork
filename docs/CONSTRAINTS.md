# 制約移植一覧

Evaluator が算出する breakdown キー（main 名称に整合）。

## HARD

| キー | 内容 |
|------|------|
| covU | 日次人数不足 |
| shiftU | 日×シフト需要不足 |
| exact | lo==hi 回数ピン外れ |
| illegal | canDo 外 |
| lockBreak | wish lock 破壊 |
| c3n | 夜勤翌日の禁止シフト |
| c1 | 日次グループ多様性不足 |
| groupViol | グループ最低人数不足 |

## SOFT

| キー | 内容 |
|------|------|
| covO | 日次過多 |
| range / low / high | 回数範囲 |
| wish / pref | 希望不一致 |
| bal / fair | 勤務日数ばらつき |
| weekly | 7日周期（曜日）の勤務シフト平準化（各シフト種の曜日ばらつき）。任意で週勤務日数上限 |
| c3 | 連勤上限超過 |
| c3m | 連続休息不足 |
| c3mn | 夜勤絡み連勤 |
| c2 | シフト過剰配置 |
| apt | スキル不適合 |

## 注意

式はスタンドアロン実装。本番 MirrorKeys の係数・閾値と完全一致させるには
`ObjectiveWeightsSource` と `ConstraintConfig` を main から上書きすること。
