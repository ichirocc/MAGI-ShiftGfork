package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.nativex.NativeWritesProbe
import com.magi.app.v6.engine.nativex.tryTransitionStrictWithOptionalNativeSkip
import java.util.Random

/**
 * フォーク元 RSI の単純化版。
 * 最大違反族に集中し、失敗が続けば次点族へ回転。すべて Move + STRICT。
 */
class SimpleRsi(
    private val problem: Problem,
    private val session: SearchSessionFull,
    private val fixProvider: FocusFixProvider = FocusAwareFixProvider(problem),
) {
    data class Params(
        val budgetMs: Long,
        val shouldStop: () -> Boolean = { false },
        val infeasible: Set<String> = emptySet(),
        val nativeProbe: NativeWritesProbe? = null,
        val maxIters: Long = 0L,
        val failRotate: Int = 40,
    )

    private val hardPrefer = setOf("groupViol", "c3n", "covU", "pref", "covO", "c2")

    fun run(params: Params, rng: Random): Long {
        if (!ProblemGuards.isRunnable(problem)) return 0L
        if (params.budgetMs <= 0L && params.maxIters <= 0L) return 0L
        val failRotate = params.failRotate.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + params.budgetMs.coerceAtLeast(0L)
        var iters = 0L
        var focus = pickFocus(session.currentReport, params.infeasible) ?: return 0L
        var fails = 0
        while (System.currentTimeMillis() < deadline && !params.shouldStop()) {
            if (params.maxIters > 0L && iters >= params.maxIters) break
            iters++
            val move = fixProvider.propose(focus, session, problem, rng)
            if (move == null) {
                fails++
                if (fails >= failRotate) {
                    focus = pickFocus(session.currentReport, params.infeasible + focus) ?: return iters
                    fails = 0
                }
                continue
            }
            val before = familyCount(session.currentReport, focus)
            val r = session.tryTransitionStrictWithOptionalNativeSkip(move, params.nativeProbe)
            if (r is TransitionResult.Rejected) {
                fails++
            } else {
                val after = familyCount(session.currentReport, focus)
                fails = if (after >= before) fails + 1 else 0
            }
            if (fails >= failRotate) {
                focus = pickFocus(session.currentReport, params.infeasible + setOf(focus))
                    ?: pickFocus(session.currentReport, params.infeasible)
                    ?: return iters
                fails = 0
            }
        }
        return iters
    }

    fun pickFocus(report: ViolationReport, exclude: Set<String>): String? {
        var best: String? = null
        var bestScore = 0.0
        for ((k, v) in report.breakdown) {
            if (v <= 0 || k in exclude) continue
            val s = v * (if (k in hardPrefer) 1000.0 else 1.0)
            if (s > bestScore) {
                bestScore = s
                best = k
            }
        }
        return best
    }

    private fun familyCount(report: ViolationReport, key: String): Int =
        report.breakdown[key] ?: 0
}
