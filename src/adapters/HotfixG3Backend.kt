package com.magi.app.v6.engine.adapters

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import com.magi.app.v6.engine.G3Backend
import com.magi.app.v6.engine.G3StepResult
import com.magi.app.v6.engine.BuiltinScheduleImprover
import com.magi.app.v6.engine.SearchSessionFull

/**
 * 既存後処理を G3 に載せるアダプタの完成形インタフェース。
 *
 * main では V6HotfixPasses / C1RepairOperators 等を呼び、
 * **keep-best で session より良い盤だけ** を session に反映する。
 *
 * Session は tryTransition(STRICT) 経由が理想だが、既存パスが
 * Array<IntArray> を直接返す場合は replaceIfBetter を使う。
 */
fun interface ScheduleImprover {
    /**
     * @param schedule 現 best のコピー（書き換えてよい）
     * @param deadlineMs 壁時計締切
     * @return 改善したか
     */
    fun improve(schedule: Array<IntArray>, deadlineMs: Long): Boolean
}

class HotfixG3Backend(
    private val better: (com.magi.app.v6.ViolationReport, com.magi.app.v6.ViolationReport) -> Boolean,
    private val evaluate: (Array<IntArray>) -> com.magi.app.v6.ViolationReport,
    private val structural: ScheduleImprover = ScheduleImprover { _, _ -> false },
    private val c1: ScheduleImprover = ScheduleImprover { _, _ -> false },
    private val c3: ScheduleImprover = ScheduleImprover { _, _ -> false },
    private val personal: ScheduleImprover = ScheduleImprover { _, _ -> false },
) : G3Backend {

    override fun runStructural(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runImprover(session, deadlineMs, structural)

    override fun runC1(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runImprover(session, deadlineMs, c1)

    override fun runC3(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runImprover(session, deadlineMs, c3)

    override fun runPersonal(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runImprover(session, deadlineMs, personal)

    private fun runImprover(
        session: SearchSessionFull,
        deadlineMs: Long,
        improver: ScheduleImprover,
    ): G3StepResult {
        if (System.currentTimeMillis() >= deadlineMs) return G3StepResult.NONE
        val cand = SearchSessionFull.deepCopy(session.best)
        val before = session.bestReport
        if (!improver.improve(cand, deadlineMs)) return G3StepResult.NONE
        val after = evaluate(cand)
        if (!better(after, before)) return G3StepResult.NONE
        return G3StepResult(improved = true, schedule = cand, report = after)
    }
}

/**
 * main 配線例:
 *
 * ```kotlin
 * HotfixG3Backend(
 *   better = ::betterReport,
 *   evaluate = { UnifiedViolationChecker.check(state, it) },
 *   c1 = ScheduleImprover { sch, dl -> runC1Cluster(state, sch, dl) },
 *   c3 = ScheduleImprover { sch, dl -> runC3Cluster(state, sch, dl) },
 *   personal = ScheduleImprover { sch, dl -> runRangeAptFair(state, sch, dl) },
 *   structural = ScheduleImprover { sch, dl -> runBlockAndCycle(state, sch, dl) },
 * )
 * ```
 */
object HotfixWiring {
    fun withBuiltins(
        problem: com.magi.app.v6.Problem,
        evaluate: (Array<IntArray>) -> com.magi.app.v6.ViolationReport,
        better: (com.magi.app.v6.ViolationReport, com.magi.app.v6.ViolationReport) -> Boolean,
        structural: ScheduleImprover? = null,
        c1: ScheduleImprover? = null,
        c3: ScheduleImprover? = null,
        personal: ScheduleImprover? = null,
    ): HotfixG3Backend {
        val rng = java.util.Random(0L)
        return HotfixG3Backend(
            better = better,
            evaluate = evaluate,
            structural = structural ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.STRUCTURAL, rng,
            ),
            c1 = c1 ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.C1, java.util.Random(1L),
            ),
            c3 = c3 ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.C3, java.util.Random(2L),
            ),
            personal = personal ?: BuiltinScheduleImprover(
                problem, evaluate, better, BuiltinScheduleImprover.Mode.PERSONAL, java.util.Random(3L),
            ),
        )
    }
}

