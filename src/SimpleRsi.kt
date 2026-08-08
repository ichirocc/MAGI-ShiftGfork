package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.nativex.NativeWritesProbe
import com.magi.app.v6.engine.nativex.tryTransitionStrictWithOptionalNativeSkip
import java.util.Random

/**
 * RSI: HARD 族を優先して STRICT 修復。
 * breakdown が空・全 exclude でも hardPrefer を巡回して 0 iters 即終了しない。
 * デバッグ: tag MAGI_RSI
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

    private val hardPrefer = listOf("groupViol", "c3n", "covU", "pref", "covO", "c2", "low", "high")

    fun run(params: Params, rng: Random): Long {
        if (!ProblemGuards.isRunnable(problem)) {
            println("[MAGI_RSI] skip not-runnable")
            return 0L
        }
        if (params.budgetMs <= 0L && params.maxIters <= 0L) {
            println("[MAGI_RSI] skip budget=0")
            return 0L
        }
        val failRotate = params.failRotate.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + params.budgetMs.coerceAtLeast(0L)
        var iters = 0L
        var accepts = 0L
        var rejects = 0L
        var nullMoves = 0L
        var rotates = 0
        var focus = pickFocus(session.currentReport, params.infeasible, rng)
            ?: hardPrefer.firstOrNull { it !in params.infeasible }
            ?: "covU"
        var fails = 0
        var rotateIdx = 0
        val hard0 = session.currentReport.hard
        val soft0 = session.currentReport.soft
        println("[MAGI_RSI] start focus=$focus hard=$hard0 soft=$soft0 budgetMs=${params.budgetMs} native=${params.nativeProbe != null}")
        var lastBeat = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline && !params.shouldStop()) {
            if (params.maxIters > 0L && iters >= params.maxIters) break
            iters++
            val move = fixProvider.propose(focus, session, problem, rng)
            if (move == null) {
                nullMoves++
                fails++
                if (fails >= failRotate) {
                    val prev = focus
                    focus = rotateFocus(session.currentReport, params.infeasible, focus, rotateIdx++, rng)
                    rotates++
                    fails = 0
                    println("[MAGI_RSI] rotate(nullMoves) $prev -> $focus")
                }
                continue
            }
            val before = familyCount(session.currentReport, focus)
            val hardBefore = session.currentReport.hard
            val r = session.tryTransitionStrictWithOptionalNativeSkip(move, params.nativeProbe)
            if (r is TransitionResult.Rejected) {
                rejects++
                fails++
            } else {
                accepts++
                val after = familyCount(session.currentReport, focus)
                val hardAfter = session.currentReport.hard
                fails = when {
                    hardAfter < hardBefore -> 0
                    after < before -> 0
                    else -> fails + 1
                }
                if (hardAfter < hardBefore) {
                    println("[MAGI_RSI] HARD improve $hardBefore -> $hardAfter focus=$focus")
                }
            }
            if (fails >= failRotate) {
                val prev = focus
                focus = rotateFocus(session.currentReport, params.infeasible, focus, rotateIdx++, rng)
                rotates++
                fails = 0
                println("[MAGI_RSI] rotate(fails) $prev -> $focus")
            }
            val now = System.currentTimeMillis()
            if (now - lastBeat >= 3_000L) {
                lastBeat = now
                println("[MAGI_RSI] beat focus=$focus iters=$iters acc=$accepts rej=$rejects hard=${session.currentReport.hard}")
            }
        }
        println("[MAGI_RSI] end iters=$iters acc=$accepts rej=$rejects hard=$hard0->${session.currentReport.hard}")
        return iters
    }

    fun pickFocus(report: ViolationReport, exclude: Set<String>, rng: Random = Random()): String? {
        var best: String? = null
        var bestScore = 0.0
        for ((k, v) in report.breakdown) {
            if (v <= 0 || k in exclude) continue
            val hardBoost = if (k in hardPrefer || k.startsWith("cov") || k == "c3n") 1000.0 else 1.0
            val s = v * hardBoost
            if (s > bestScore) {
                bestScore = s
                best = k
            }
        }
        if (best != null) return best
        if (report.hard > 0) {
            val cand = hardPrefer.filter { it !in exclude }
            if (cand.isNotEmpty()) return cand[rng.nextInt(cand.size)]
        }
        return null
    }

    private fun rotateFocus(
        report: ViolationReport,
        exclude: Set<String>,
        current: String,
        idx: Int,
        rng: Random,
    ): String {
        val next = pickFocus(report, exclude + current, rng)
        if (next != null) return next
        val pool = hardPrefer.filter { it !in exclude && it != current }.ifEmpty { hardPrefer }
        return pool[idx.mod(pool.size)]
    }

    private fun familyCount(report: ViolationReport, key: String): Int =
        report.breakdown[key] ?: 0
}
