# Phase 4 — SOA とベンチマーク

## サービス（単一責任）

| サービス | 責任 |
|----------|------|
| SearchSessionFull | 合法性・原子遷移・best |
| MoveBuilders | 正規化候補生成 |
| G1LocalAnnealer | 局所 SA/LAHC |
| G2FocusRepair | 残差フォーカス修復 |
| G3FamilyPolish | 族別 STRICT 研磨ファサード |
| G4Diversify | elite / bridge 分離 |
| SchedulerService | Deadline 配分のみ |
| RunArtifacts | 実行成果の正本 |

## ベンチマーク（実証プロトコル）

この環境では main との実測比較は未実施。以下をリポジトリ CI/実機で緑にして「実証」とする。

### 精度（悪化ゼロ）

1. 1 worker・固定 seed: betterReport(新, 旧) が偽（新が悪くない）
2. 並列 8 worker・複数 seed: 最悪 hard → 最悪 weighted が旧以下
3. pin 不変 property: 全遷移で feasible wish セル不変
4. best/elite/出力に betterReport 以外の更新経路なし

### 速度（同等以上、目標 20% は努力目標）

1. 同一 hard 到達 wall 時間
2. 300s 終了時の有効遷移数 / 無効手率
3. 手段: pin 事前拒否・Native 維持・0 改善 probe・配分重複排除

### コード品質

- 浅いネスト（遷移は prepare/commit/revert）
- モジュール分割（上記 SOA）
- CONNECT_MAIN.md で既存への段階接続

### 禁止

未計測の「20% 達成」「精度向上 N%」の宣言。
