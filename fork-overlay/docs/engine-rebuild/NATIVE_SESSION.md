# Session × Native tryWrites

```kotlin
val handle = NativeBridge.nativeCreateProblem(...)
val probe = NativeBridgeProbe(handle)

// 各 Move で:
session.tryTransitionWithNativeProbe(move, TransitionMode.STRICT, probe)
// → Native で高速試行後、必ず Kotlin tryTransition で番兵
```

- Native status=0 → 通常 Kotlin のみ
- Native accept → 同じ Move を Kotlin Session が再適用・再評価
- best は betterReport のみ（Native スコアでは更新しない）
