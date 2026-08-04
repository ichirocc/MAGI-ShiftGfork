package com.magi.app.v6.engine

import com.magi.app.v6.Problem

data class G3Params(
    val deadlineMs: Long,
    val shouldStop: () -> Boolean = { false },
    /** 各 G3 ステップ結果を Session に取り込む */
    val absorb: (SearchSessionFull, G3StepResult) -> SearchSessionFull = { s, r ->
        if (!r.improved || r.schedule == null || r.report == null) s
        else {
            s.replaceBestIfBetter(r.schedule, r.report)
            s
        }
    },
)

interface G3Backend {
    fun runC1(session: SearchSessionFull, deadlineMs: Long): G3StepResult
    fun runC3(session: SearchSessionFull, deadlineMs: Long): G3StepResult
    fun runPersonal(session: SearchSessionFull, deadlineMs: Long): G3StepResult
    fun runStructural(session: SearchSessionFull, deadlineMs: Long): G3StepResult
}


class G3FamilyPolish(
    private val backend: G3Backend,
    private val problem: Problem? = null,
    private val rng: java.util.Random = java.util.Random(0L),
) {
    fun run(session0: SearchSessionFull, params: G3Params): Pair<SearchSessionFull, StopReason> {
        var session = session0
        if (problem != null) {
            ConstraintPolishService(problem, rng).polishAll(session, params.deadlineMs)
        }
        repeat(4) {
            if (params.shouldStop() || System.currentTimeMillis() >= params.deadlineMs) {
                return session to StopReason.DEADLINE
            }
            var any = false
            val steps = listOf(
                backend::runStructural,
                backend::runC1,
                backend::runC3,
                backend::runPersonal,
            )
            for (step in steps) {
                if (params.shouldStop() || System.currentTimeMillis() >= params.deadlineMs) {
                    return session to StopReason.DEADLINE
                }
                val result = step(session, params.deadlineMs)
                if (result.improved) {
                    any = true
                    session = params.absorb(session, result)
                }
            }
            if (!any) return session to StopReason.FIXED_POINT
        }
        return session to StopReason.DEADLINE
    }
}
