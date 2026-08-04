package com.magi.app.v6.engine.adapters

import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.DeltaEvaluateHook

/**
 * main の DeltaEvaluator を DeltaEvaluateHook にする薄いアダプタ契約。
 *
 * ```kotlin
 * val hook = DeltaEvaluatorHook { schedule, writes, undo ->
 *   // eval を writes で差分更新して score を ViolationReport に変換
 *   checkerReportFromDelta(eval)
 * }
 * SearchSessionFull(..., deltaHook = hook)
 * ```
 */
fun interface DeltaEvaluatorHook : DeltaEvaluateHook
