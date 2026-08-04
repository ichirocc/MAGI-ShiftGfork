# 再考: Native / C1JointLNS / 並列 SA

## 共通原則
1. **合法性は全遷移で不変**（wish pin / canDo / 原子的 writes）
2. **best は betterReport のみ**
3. **Native は加速器**であり採否の主権は Kotlin

## 1. C++ Native (JNI)
| 旧 | 新 |
|----|-----|
| 生 schedule 書き込み | **writes バッチのみ** |
| strongPerturb 専用 | 禁止。Move 経由 |
| Problem handle 共有 | **ワーカー専用** |
| 信頼しきり | **Kotlin 番兵照合** → 不一致で Gate off |

実装本体は NDK (`magi_native_api.hpp.txt`)。Kotlin は `NativeMoveEngine` 契約。

## 2. C1 Joint LNS
| 旧 | 新 |
|----|-----|
| 内部 Move 型 + 直接盤面 | エンジン **Move + STRICT** |
| hardDebt 許容 bridge | STRICT のみ（bridge は G4） |
| suffix-DP 下界 | フック化（後続） |
| 単独オブジェクト | **ScheduleImprover** → FullyWired G3 に配線 |

近傍: Direct / Swap2 / Rotate3 / SelfSwap / C1Precision 日次。

## 3. 並列 SA worker
| 旧 | 新 |
|----|-----|
| 共有 globalBest ロック | **Elite CAS + betterReport** |
| 共有 Native handle | 禁止（使うなら worker 専用） |
| 順序非決定を無視 | 1w=再現、Nw=統計 |

`ParallelSaCoordinator`: 独立 Session × G1、スライス時間で Elite に公開。

## 配線
- G3 c1 Improver → `C1JointLnsEngine`
- G1 並列化は Scheduler が `ParallelSaCoordinator` を呼ぶオプション（後続で G1 段置換）
- Native は `NativeMoveEngine` 実装を Session に注入（未リンク時 Null）

## 進捗（次の実装）
- [x] C1JointLnsEngine → FullyWired G3 c1 に配線
- [x] ParallelSaCoordinator → Scheduler G1（workers>=2）
- [x] EngineOptions.workers / seed 貫通
- [ ] NDK magi_try_writes 本体
- [ ] 1 worker 固定 seed 回帰テスト

## 進捗更新
- [x] native-cpp: g++ で libmagi_native.so ビルド、テスト OK（lock reject / STRICT improve）
- [x] ParallelBenchmark: w1/w4 の p50/p95
- [ ] Android NDK への組み込み（CMake externalNativeBuild）

## NDK 組み込み（フォーク）
- [x] `NativeBridge.nativeTryWrites` JNI 追加
- [x] `magi_native.cpp` に Move 契約パス（fullEvalCombined）
- [x] `NativeTryWritesBridge` アダプタ
- ビルド: 既存 `externalNativeBuild { cmake }` + NDK 26
