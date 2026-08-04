package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import com.magi.app.v6.engine.nativex.NativeWritesProbe
import com.magi.app.v6.engine.nativex.tryTransitionStrictWithOptionalNativeSkip
import java.util.Random

data class G2Params(
    val budgetMs: Long = 20_000L,
    val shouldStop: () -> Boolean = { false },
    val infeasibleFamilies: Set<String> = emptySet(),
    val nativeProbe: NativeWritesProbe? = null,
)

fun interface FocusFixProvider {
    fun propose(focus: String?, board: BoardView, problem: Problem, rng: Random): Move?
}

object RandomFocusFixProvider : FocusFixProvider {
    override fun propose(focus: String?, board: BoardView, problem: Problem, rng: Random): Move? {
        val s = rng.nextInt(problem.S)
        val d = rng.nextInt(problem.T)
        if (problem.wishLocked(s, d)) return null
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) return null
        val tag = focus?.let { "g2.$it" } ?: "g2.total"
        return buildSingleMove(board, problem, s, d, allowed[rng.nextInt(allowed.size)], tag)
    }
}

class G2FocusRepair(
    private val problem: Problem,
    private val session: SearchSessionFull,
    private val weights: Map<String, Double> = defaultFocusWeights(),
    private val fixProvider: FocusFixProvider = FocusAwareFixProvider(problem),
) {
    fun run(params: G2Params, rng: Random): Long {
        val start = System.nanoTime() / 1_000_000L
        var iters = 0L
        var sameFocusFails = 0
        var focus = pickFocus(session.currentReport, params.infeasibleFamilies)
        fun timeUp() =
            params.shouldStop() || (System.nanoTime() / 1_000_000L) - start >= params.budgetMs
        while (!timeUp()) {
            iters++
            val move = fixProvider.propose(focus, session, problem, rng)
            if (move == null) {
                sameFocusFails++
                if (sameFocusFails >= 32) {
                    focus = pickFocus(session.currentReport, params.infeasibleFamilies + setOfNotNull(focus))
                    sameFocusFails = 0
                }
                continue
            }
            val beforeCnt = familyCount(session.currentReport, focus)
            val r = session.tryTransitionStrictWithOptionalNativeSkip(move, params.nativeProbe)
            if (r is TransitionResult.Rejected) sameFocusFails++
            else {
                sameFocusFails = 0
                if (familyCount(session.currentReport, focus) >= beforeCnt) sameFocusFails++
            }
            if (sameFocusFails >= 48) {
                focus = pickFocus(session.currentReport, params.infeasibleFamilies)
                sameFocusFails = 0
            }
        }
        return iters
    }

    fun pickFocus(report: ViolationReport, exclude: Set<String>): String? {
        var bestKey: String? = null
        var bestScore = 0.0
        for ((k, v) in report.breakdown) {
            if (v <= 0 || k in exclude) continue
            val s = v * (weights[k] ?: 1.0)
            if (s > bestScore) { bestScore = s; bestKey = k }
        }
        if (bestKey == null && report.hard > 0 && "c3n" !in exclude) return "c3n"
        return bestKey
    }

    private fun familyCount(r: ViolationReport, family: String?): Int =
        if (family == null) r.total else r.breakdown[family] ?: 0

    companion object {
        fun defaultFocusWeights(): Map<String, Double> = mapOf(
            "c3n" to 100.0, "covU" to 100.0, "pref" to 100.0, "groupViol" to 100.0,
            "c1" to 15.0, "c3mn" to 15.0, "low" to 90.0, "high" to 45.0,
            "c3" to 3.0, "c3m" to 2.0, "c2" to 1.0, "apt" to 1.0,
            "fair" to 1.0, "weekly" to 1.0, "covO" to 1.0,
        )
    }
}

private fun setOfNotNull(x: String?): Set<String> = if (x == null) emptySet() else setOf(x)
