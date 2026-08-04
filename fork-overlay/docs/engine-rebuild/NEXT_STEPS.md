# 次の作業（main 接続）

## 今すぐ（このパッケージ）

- [x] 契約核 Move / Session / betterReport
- [x] G1–G4 + Builtin 修復
- [x] domain Evaluator 配線
- [x] MainOptimizeBridge
- [x] ContractPropertyTests

## magi 本体リポジトリで

1. `src/**/*.kt` を `com.magi.app.v6.engine` に配置（`domain/` は本番型があるなら除外）
2. P0: strongPerturbFlat / BlockFill / LateOperators boost（`patches/`）
3. `MainOptimizeBridge` に Checker・find*Fix・Hotfix を注入
4. `optimize()` から bridge.optimize を呼ぶ
5. ContractPropertyTests + 固定 seed 1 worker 非劣化
6. 実測 ms を記録

## やってはいけない

- ValidBridge を alternatives に載せる
- 重みの無断変更
- 未計測の 20% 高速宣言
