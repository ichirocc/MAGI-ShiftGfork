# 温度管理（完成仕様）

## 部品

| 型 | 役割 |
|----|------|
| Metropolis | 純関数の受理判定（Session と共有） |
| TemperatureController | T 減衰・再加熱・ANNEAL/LAHC 相 |
| LahcHistory | 履歴閾値リング |
| G1LocalAnnealer | 上記を駆動 |

## スケジュール

1. bootstrap: T = t0（または suggestT0(Δ)）
2. 各試行: tryMetropolis(T) → onTrial → T *= alpha（adaptive 可）
3. 停滞 + 低受理率 → reheat（T = max(T, t0 * reheatFactor)）+ 軽 perturb
4. best.hard == 0 → LAHC 相（T≈0、履歴閾値）
5. LAHC 長期停滞 → 短時間 SA に戻して再加熱

## 契約

- 却下時 version 不変（tryMetropolis / tryLahc）
- best は betterReport のみ
- 再加熱・perturb は current 用
