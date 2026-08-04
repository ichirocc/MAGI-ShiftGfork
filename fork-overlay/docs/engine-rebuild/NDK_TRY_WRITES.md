# nativeTryWrites（Move 契約）

## C++ / JNI
- `magi_native.cpp`: `Java_com_magi_app_v6_NativeBridge_nativeTryWrites`
- 既存 `MagiProblem` + `fullEvalCombined` を使用（本番評価と同系）
- wish pin / canDo / 重複セルを拒否
- STRICT / ANNEAL（決定的近似）/ LAHC 簡易

## Kotlin
```kotlin
NativeBridge.nativeTryWrites(handle, scheduleFlat, writes, mode, temp, lahcThr)
// → longArrayOf(status, score, hard, soft)
```

## ビルド
```bash
bash gradlew :app:externalNativeBuildDebug
# または assembleDebug（NDK + cmake 既存設定を利用）
```

## 注意
- ANNEAL の乱数 Metropolis は Kotlin `tryMetropolis` が正本。Native ANNEAL は高速近似。
- best 更新は常に Kotlin `betterReport` で再検証すること。
