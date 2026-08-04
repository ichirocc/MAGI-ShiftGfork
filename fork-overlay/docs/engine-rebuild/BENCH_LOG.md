# 本番精度・速度比較ログ（MAGI_BENCH）

## 目的

再構築エンジン（rebuild）とフォーク元（upstream）の **精度（hard / weighted）と速度（elapsedMs）** を、同じ入力・同じ seed で比較できるようにする。

## ログ形式（1行1イベント）

```
MAGI_BENCH engine=rebuild phase=g1 hard=3 soft=12 total=15 weighted=42.5 elapsedMs=12045 iters=8000 seed=42 workers=4 budgetMs=300000
MAGI_BENCH_SUMMARY engine=rebuild hard=0 soft=8 total=8 weighted=12.0 elapsedMs=298101 seed=42 workers=4 budgetMs=300000 phases=8
MAGI_BENCH_COMPARE hardDelta=-1 weightedDelta=-3.5 elapsedMsDelta=-12000 rebuildHard=0 upstreamHard=1 winnerAccuracy=rebuild winnerSpeed=rebuild
```

- Logcat タグ: `MAGI_BENCH`
- 操作ログ: `ViolationReport.logs` に同内容が転記される（rebuild 完了時）

## 取得方法

```bash
adb logcat -s MAGI_BENCH:I
```

## A/B 実行

```kotlin
val ab = OptimizeAbRunner.runBoth(state, schedule, budgetSec = 60, seed = 42L, workers = 1)
// ab.compareLine に COMPARE 行
```

## 解釈

| フィールド | 意味 |
|------------|------|
| hardDelta | rebuild - upstream（負なら rebuild の方が HARD 少ない＝精度上有利） |
| weightedDelta | 同上（weightedScore） |
| elapsedMsDelta | 負なら rebuild の方が速い |
| winnerAccuracy | hard 優先、同点なら weighted |
| winnerSpeed | elapsedMs が小さい方 |

## フェーズ名（rebuild）

`begin` → `start` → `g1` → `g2` → `g3` → `g4` → `pipeline` → `hard_residual?` → `done` → `summary`
