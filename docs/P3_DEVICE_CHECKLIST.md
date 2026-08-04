# P3 実機 / SDK チェックリスト

## 1. ビルド
```bash
cd magi-fork
cp local.properties.example local.properties  # sdk.dir を設定
bash gradlew :app:assembleDebug
```
- [ ] `libmagi_native.so` が APK に含まれる
- [ ] 起動時 `NativeBridge.available == true`（ログ）

## 2. 再構築エンジン
```kotlin
// build.gradle.kts
buildConfigField("Boolean", "REBUILD_ENGINE", "true")
```
- [ ] ViewModel 起動後 `RebuildOptimizeEntry.enabled == true`
- [ ] Worker が phase start/g1/g2/g3/g4/done を出す
- [ ] 8 秒ごとに snapshot ファイルが更新される

## 3. Golden 比較
同一入力・同一 seed で:
| 経路 | hard | total | 時間 |
|------|------|-------|------|
| V6FinalPort (enabled=false) | | | |
| Rebuild (enabled=true) | | | |

- [ ] hard が悪化していない
- [ ] total が大幅悪化していない

## 4. Native skip
```
adb logcat | grep nativeSkip
// またはアプリ内で GlobalNativeSkipGate.gate.stats()
```
- [ ] mismatch rate が 0.1% 以下で enabled になる
- [ ] mismatch 急増時にゲートが閉じる

## 5. CI
```bash
MAGI_BUDGET_MS=2000 MAGI_SEEDS=3 ./scripts/ci-smoke.sh
# 夜間のみ
MAGI_BUDGET_MS=300000 MAGI_SEEDS=5 ./scripts/ci-smoke.sh  # 要 main 拡張
```
