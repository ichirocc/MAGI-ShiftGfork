# Android 会話バブル対応

## 実装

| ファイル | 役割 |
|----------|------|
| BubbleSupport.kt | チャンネル・ショートカット・MessagingStyle・BubbleMetadata |
| BubbleActivity.kt | バブル展開 UI |
| OptimizationWorker.kt | 開始・1.5s 進捗・完了で BubbleSupport 呼び出し |
| scripts/ensure-bubble-manifest.sh | Manifest に Activity が無ければ挿入 |

## 成立条件

1. 長寿命会話ショートカット
2. MessagingStyle + setShortcutId + Person
3. BubbleMetadata → BubbleActivity
4. allowEmbedded / resizeableActivity / documentLaunchMode=always
5. IMPORTANCE_HIGH + setAllowBubbles(true)
6. POST_NOTIFICATIONS 実行時許可
7. 端末のバブル許可

## 動作

最適化開始でバブル通知、約1.5秒間隔で進捗、完了で結果。タップで BubbleActivity。
FGS 通知とは別 ID (NID_BUBBLE=4103)。
