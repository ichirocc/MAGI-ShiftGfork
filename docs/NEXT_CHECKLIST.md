# 次作業チェックリスト（MAGI-ShiftGfork）

## 完了
- Kotlin/C++ 棲み分け契約
- 差分・フル数値評価の C++ 移行
- 並列 SA ワーカー専用 handle
- REBUILD 常時 ON
- G1 メトリクス分離（deltaOk / earlyReject / acceptHint）

## 次（優先）
1. 実機 A/B: G1 早期棄却 ON/OFF で HARD・時間比較
2. パリティ soft 差分の族別ログ
3. STRICT 研磨へ native skip 接続
4. 本番 10x31 で MAGI_FULL / MAGI_DELTA / MAGI_PARITY 収集
