# MAGI-ShiftGfork — 最適化エンジン再構築

勤務表最適化（MAGI）を **Move 契約 / G1–G4 / Native tryWrites** で再構築した成果物です。

## 今すぐ実行（JVM）

依存: **Java 17+**、初回は Kotlin コンパイラを自動取得（または PATH 上の `kotlinc`）

```bash
git clone https://github.com/ichirocc/MAGI-ShiftGfork.git
cd MAGI-ShiftGfork
chmod +x scripts/*.sh
./scripts/run-all.sh
```

個別:

```bash
./scripts/verify-native-host.sh   # g++ が必要
./scripts/ci-smoke.sh            # 決定性・golden・並列スモーク
```

環境変数:

| 変数 | 既定 | 意味 |
|------|------|------|
| `MAGI_BUDGET_MS` | 2000 | スモーク探索予算 |
| `MAGI_SEEDS` | 3 | 並列比較シード数 |

## Android 統合（フォーク元 + overlay）

```bash
git clone https://github.com/ichirocc/magi7ichiro-fork.git app-base
cp -a fork-overlay/. app-base/
cd app-base
./gradlew :app:assembleDebug
```

CI では **Android Overlay Build** が同じ手順を実行します。

debug ビルドは `BuildConfig.REBUILD_ENGINE=true` で再構築エンジンを既定 ON。

## 構成

| パス | 説明 |
|------|------|
| `src/` | 再構築エンジン（Kotlin） |
| `scripts/` | `run-all.sh` / `ci-smoke.sh` / `verify-native-host.sh` |
| `native-cpp/` | ホスト検証用 C++ |
| `fork-overlay/` | Android 差分（Worker / JNI / engine） |
| `docs/` | 設計・制約・配線 |

## CI

- Engine JVM Check
- V6 Engine Check
- Android Overlay Build（assembleDebug + native .so）
- Android SDK / Native Parity / Release Build

## リポジトリの分離

| リポジトリ | 役割 |
|------------|------|
| **magi7ichiro-fork** | 本番アプリ本体（従来 V6）。再構築エンジンは含まない |
| **MAGI-ShiftGfork** | 再構築エンジン + `fork-overlay`（実験・移行用） |

`magi7ichiro-fork` への本エンジンのマージは取り外済みです。統合する場合は本リポの `fork-overlay` を明示的に適用してください。
