# 次作業チェックリスト（MAGI-ShiftGfork）

## 完了済み
- [x] 再構築エンジン G1–G4 配線
- [x] REBUILD_ENGINE 全ビルドで true
- [x] RebuildOptimizeEntry 常時 enabled
- [x] autoNative=false（JNI 強制終了回避）
- [x] ANNEAL/Metropolis HARD ゲート
- [x] 重み監査ログ MAGI_WEIGHTS
- [x] アプリ版ログ MAGI_VERSION
- [x] Android バブル
- [x] CI green（Overlay / SDK / V6）

## 次（優先）
1. 実機で操作ログに「再構築エンジン」「探索フェーズ: rebuild-…」が出ることを確認
2. 同じ 10×31 データで HARD 改善推移を V5 SA と比較（MAGI_BENCH）
3. Native パリティ安定後に autoNative=true を段階再開
4. 希望固定 c3n 構造壁の PROVEN 早期打ち切りと SOFT 再配分

## 実機確認の見方
| ログ | 経路 |
|------|------|
| 探索フェーズ: V5 SA | 上流（古い APK の可能性） |
| 探索フェーズ: rebuild-… / 再構築エンジン | 新エンジン（正しい） |
