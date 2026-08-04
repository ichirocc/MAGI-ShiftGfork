# 契約修正メモ（実装候補の精査結果）

## 1. WindowFill の部分化

**問題**: 窓内の wish lock を `continue` で飛ばし、残セルだけ書くと「部分 WindowFill」になり、
適用時スキップと同型の意味になる。

**修正**: 窓 `[start, start+len)` に lock が1つでもあれば **Move 全体を null**。
既に目標 shift のセルは no-op として writes から除く（正規化であり部分適用ではない）。

DayLns も、指定職員が lock なら手全体を却下。

## 2. ANNEAL 却下後に戻せない

**問題**: `tryTransition(ANNEAL)` は受理前提で `commit`（version++）する。
温度判定の前にこれを呼ぶと却下できない。

**修正**:
- 温度判定は必ず `tryMetropolis`（provisional apply → 却下時 `revertSnap`、**version は進まない**）
- `tryTransition(ANNEAL)` は Metropolis/外部受理が決まった後の適用専用

## 3. ExactCount FORBID_WORSEN の不一致

**問題**: 変更後 `count != lo` なら全て拒否 → もともと外れていたピンの修復近傍まで潰す。

**修正**: `regressesSatisfiedExactPins`
- before==lo かつ after!=lo のときだけ拒否
- 充足していなかったピンは触ってよい

IMMUTABLE は従来どおり「ピンシフトが載ったセルを書き換えない」。
