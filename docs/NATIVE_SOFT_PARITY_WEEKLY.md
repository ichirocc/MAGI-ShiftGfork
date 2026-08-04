# Native soft パリティ修正（weekly）

## 症状
```
C++ hard=1/soft=933 ≠ Kotlin hard=1/soft=1046
→ パリティ不一致のためネイティブ経路は使いません
```

## 原因
C++ `fullEvalParts` / SA delta の **weekly** が旧式のまま:

| 側 | 計算 |
|----|------|
| **旧 C++** | 職員×曜日に「勤務日（非休）」だけをカウント |
| **Kotlin 3.345.0** | 職員×**シフト**×曜日に各シフト（休含む）をカウントし L1 |

差分 soft ≈ 113 は、この weekly 定義差と一致し得る（Kotlin weekly=161 規模）。

## 修正
- `fullEvalParts`: シフト別 `wdk[K*7]` に統一
- `SaChunk` delta: `wd[S*K*7]` + `contribWeeklyK(i,k)` に統一

## 確認
再ビルド後、同一盤面で:
```
NativeEval.parityCheck → match=true
```
