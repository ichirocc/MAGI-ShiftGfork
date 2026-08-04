# MAGI-ShiftGfork — 最適化エンジン再構築

勤務表最適化（MAGI）を Move 契約 / G1–G4 / Native tryWrites で再構築した成果物です。

## 構成
- `src/` エンジン
- `docs/` 設計ドキュメント
- `scripts/` ci-smoke / verify-native-host
- `native-cpp/` ホスト用 C++
- `fork-overlay/` Android フォーク差分

## 検証
```bash
./scripts/ci-smoke.sh
./scripts/verify-native-host.sh
```

## 統合
```bash
cp -a fork-overlay/. /path/to/android-magi/
./gradlew :app:assembleDebug
```
