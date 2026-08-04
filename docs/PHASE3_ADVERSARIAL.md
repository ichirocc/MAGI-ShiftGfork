# Phase 3 — 敵対的再設計

## 脆弱性（意図的攻撃パターン）

| 入力・状況 | 壊れるもの |
|------------|------------|
| 希望セル最大化 | SA/ALNS 無効手率上昇、同秒で soft 浅い |
| c3n が pin で構造残 | RSI 壁粘着、時間溶融 |
| hard=0 が早い | 並列 early cancel が soft を殺す |
| c1 重い + boost | 局所 c1 が他族を犠牲 |
| workers 過大 | 仮説薄まり、最悪値悪化しうる |

## データ構造（高速化）

- 業務上限 S≤30, T≤31 → ULong 日/職員マスクが常用可能
- rowMask[i][k], dayMask[j][k], wishLock[i]
- 1 セル更新 O(1)、c3n/cov/c1 は popcount（実装は MISSING）

## メタヒューリスティクス統合

- G1: SA + LAHC（深い局所）
- G2: 適応フォーカス + destroy（VNS 的な大近傍）
- G4: 盆地間（bridge は出力しない）
- G3: STRICT 仕上げ
- 単一の「新魔法」ではなく契約共有の相乗
