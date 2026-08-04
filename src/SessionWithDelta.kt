package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import java.util.Random

/**
 * DeltaEvaluateHook 付きセッション。
 * 実体は SearchSessionFull（deltaHook 委譲）。重複実装を廃止して一本化。
 */
fun SessionWithDelta(
    problem: Problem,
    initial: Array<IntArray>,
    evaluate: (Array<IntArray>) -> ViolationReport,
    better: (ViolationReport, ViolationReport) -> Boolean,
    hook: DeltaEvaluateHook,
    exactCountPolicy: ExactCountPolicy = ExactCountPolicy.FORBID_WORSEN,
): SearchSessionFull = SearchSessionFull(
    problem = problem,
    initial = initial,
    evaluate = evaluate,
    better = better,
    exactCountPolicy = exactCountPolicy,
    deltaHook = hook,
)
