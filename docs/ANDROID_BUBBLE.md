# Android 会話バブル対応（MAGI-ShiftGfork）

## 目的
バックグラウンド最適化（OptimizationWorker）の進捗・完了を、他アプリの上に浮かぶ会話バブルで表示する。

## 構成

| ファイル | 役割 |
|----------|------|
| `BubbleSupport.kt` | チャンネル・長寿命ショートカット・MessagingStyle・BubbleMetadata |
| `BubbleActivity.kt` | バブル展開 UI（進捗／結果） |
| `OptimizationWorker.kt` | 開始・1.5s 間引き進捗・完了で `BubbleSupport` 呼び出し（既存配線） |
| `AndroidManifest.bubble.xml` | Activity 宣言のマージ用スニペット |

## 成立条件（Android 11+）

1. 長寿命会話ショートカット（`pushShortcut`）
2. MessagingStyle + `setShortcutId` + Person
3. `BubbleMetadata` → `BubbleActivity`
4. Activity: `allowEmbedded` / `resizeableActivity` / `documentLaunchMode=always`
5. 通知チャンネル `IMPORTANCE_HIGH` + `setAllowBubbles(true)`
6. 実行時 `POST_NOTIFICATIONS`（API 33+）

## ホストアプリへの適用

1. overlay の `work/BubbleSupport.kt` / `BubbleActivity.kt` をコピー
2. Manifest に `AndroidManifest.bubble.xml` の Activity をマージ
3. ユーザー設定で通知・バブルを許可

## 通知 ID

| ID | 用途 |
|----|------|
| FGS 進捗（Worker 側） | 前景サービス必須 |
| `BubbleSupport.NID_BUBBLE` (4103) | 会話バブル用（別通知） |
