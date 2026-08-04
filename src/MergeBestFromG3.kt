package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * G3 改善結果を Session に取り込む。
 * G3StepResult 経由（lastImproved 副作用は廃止）。
 */
fun absorbG3Step(
    previous: SearchSessionFull,
    result: G3StepResult,
): SearchSessionFull {
    if (!result.improved || result.schedule == null || result.report == null) return previous
    previous.replaceBestIfBetter(result.schedule, result.report)
    return previous
}

fun reseedSessionFromImproved(
    problem: Problem,
    previous: SearchSessionFull,
    schedule: Array<IntArray>?,
    report: ViolationReport?,
    evaluate: (Array<IntArray>) -> ViolationReport,
    better: (ViolationReport, ViolationReport) -> Boolean,
    onConsumed: () -> Unit = {},
): SearchSessionFull {
    if (schedule == null || report == null) return previous
    if (!better(report, previous.bestReport)) return previous
    onConsumed()
    if (previous.replaceBestIfBetter(schedule, report)) return previous
    return SearchSessionFull(problem, schedule, evaluate, better)
}
