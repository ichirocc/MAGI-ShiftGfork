# CI レーン（断捨離後）

## 有効ワークフロー（5）

| レーン | Workflow | 起動 |
|--------|----------|------|
| L3 製品 | Android Overlay Build | `fork-overlay/**` |
| L1 エンジン | Engine JVM Check | `src/**`, scripts, native-cpp |
| Native | Native Parity Check | native / cpp 変更 |
| Release | Release Build | tag `v*` / 手動 |
| メンテ | Cleanup Artifacts | schedule / 手動 |

無効: Android SDK, V6 Engine Check（手動 disable）

## 推奨ブランチ保護（main）

Required status checks:

1. **Android Overlay Build**（必須）
2. Engine JVM Check（任意だが `src/` 変更時は見る）

Engine だけ緑でマージしないこと（MirrorCore 型世界は Overlay のみが検証する）。

## パリティ失敗時

`MAGI_PARITY` ログに hard/soft 差と schedule sample が出る。
`NativeGate` が閉じ、Kotlin のみで継続する（クラッシュしない）。
