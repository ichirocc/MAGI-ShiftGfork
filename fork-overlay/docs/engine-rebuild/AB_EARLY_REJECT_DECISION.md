# A/B 反映: G1 早期棄却ポリシー

## 仮想 A/B 結果（2026-08-06）
- 差分 vs フル: 約 14× 高速（妥当）
- earlyReject cold-worse ON: soft 品質が悪化傾向（worse 3 / better 1）

## 採用
| 規則 | 既定 | 理由 |
|------|------|------|
| C++ hard 増加 → 即棄却 | ON | Metropolis と同契約・品質不変 |
| T&lt;0.05 かつ packed 悪化 → 棄却 | **OFF** | A/B で品質劣化 |
| nativeTryWrites ANNEAL の exp&lt;0.5 | **廃止** | 偽 Metropolis が探索を歪める |

## フラグ
`G1Params.earlyRejectHardIncrease=true`
`G1Params.earlyRejectColdWorse=false`
