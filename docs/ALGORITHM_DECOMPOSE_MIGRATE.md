# フォーク元アルゴリズム分解と移行

## 分解（本質だけ）

```
AUTO 予算
  ├─ V5 SA          → 温度付き近傍
  ├─ RSI            → 最大違反族へ集中
  ├─ ALNS           → destroy/repair + 重み学習
  ├─ RSI++          → Seed→RSI→ALNS→Polish 連鎖
  └─ PORTFOLIO      → 異種並列ロール
```

## 再構築への写像（改善付き）

| 元 | 移行先 | 改善 |
|----|--------|------|
| V5 SA | G1 LocalAnnealer | Move 契約・LAHC |
| RSI | SimpleRsi | hard 優先スコア・失敗回転 |
| ALNS | AlnsPolish | STRICT best、少数 destroy |
| Polish | VnsPolish + G3 | VNS k-reset |
| Elite/Relink | G4 | STRICT のみ |
| Late boost | （廃止） | 例外採否を排除 |

## 実行順

```
G1 SA/LAHC → SimpleRsi → AlnsPolish → VnsPolish → G3 Family → G4 Relink
```
