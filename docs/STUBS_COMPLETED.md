# スタブ・未完成の完了ログ

| 項目 | 処置 |
|------|------|
| DeltaEvaluateHook | SearchSessionFull に配線。EngineOptions → Scheduler まで貫通 |
| SessionWithDelta | SearchSessionFull のファクトリに一本化（重複実装廃止） |
| KotlinOnlyTransitionBridge | tryMetropolis/tryLahc を正しい引数で完成 |
| NativeWriteBatch | lahcThreshold 付き完成契約 |
| LegacyFixFactory | main find* 接続用ファクトリ |
| DeltaEvaluatorHook | 差分評価アダプタ契約 |
| EngineAliases | 実 typealias のみ |
| V6RebuildPort / ProblemExtensions | **フォーク専用**（スタンドアロンからは除外） |
| MainLegacyFix | フォークで findCovO/C2/Range/Apt を自動配線 |

スタンドアロン rebuild は EXIT:0 でコンパイル可能。
