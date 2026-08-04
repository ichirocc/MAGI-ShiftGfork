# main リポジトリでの次工程

## 必須（P0）
1. ソースを `com.magi.app.v6.engine` に配置
2. `EngineWiring.forMain(problem, evaluate = Checker)` で接続
3. 生書き込み禁止パッチ
   - `SaOptimizer.strongPerturbFlat` → Move + tryTransition
   - `opBlockFill` → wish lock + 正規化 writes
   - boost → best は betterReport のみ
4. static alternatives を `RunArtifacts` に置換

## 検証
```text
固定 seed 1 worker: hard/weighted が旧実装以下にならない
並列: 中央値・p95・最悪
Contract: pin / atomic / best 非劣化
```

## 任意
- Native DeltaEvaluator を evaluate 内に接続
- C++ 重み表と `ObjectiveWeightsSource.parityHash` を CI 照合
