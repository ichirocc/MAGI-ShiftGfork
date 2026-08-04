# 高精度・高速化・断捨離・移植ログ

## 断捨離
- ConstraintPolishService の VNS→ALNS→Fast カスケードを廃止
- 研磨入口を **Engine 一本 + C1 短バースト**に

## 移植（main C1/coverage 思想）
- `C1PrecisionMoves`
  - fillCoverageDay / trimCoverageDay（過多日を別勤務シフトへ変更・OFF禁止）
  - fillGroupDay（グループ不足一括）
  - dayTripleRepair（3セル1評価）

## 高速化の根拠
| 手段 | 効果 |
|------|------|
| 多セル1 Move | 評価回数をセル数分から1回へ |
| HARD時 C1 優先（G1 35%） | 無駄な soft 近傍を削減 |
| 研磨単一経路 | 重複近傍の二重実行を排除 |

## 精度の根拠
| 手段 | 効果 |
|------|------|
| 日次カバレッジ一括 | covU/covO/c1 を同時に押さえる |
| STRICT のみ best | 目的非劣化 |
| wish/canDo 生成時検査 | 違法手の評価コストゼロ |
