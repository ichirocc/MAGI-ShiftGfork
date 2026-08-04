package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.adapters.ScheduleImprover
import java.util.Random

/**
 * ScheduleImprover の実装本体（Hotfix 未接続時の既定）。
 * 盤面コピー上で Session を立て、STRICT 研磨・構造手を回して改善を書き戻す。
 */
class BuiltinScheduleImprover(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val mode: Mode,
    private val rng: Random = Random(0L),
) : ScheduleImprover {

    enum class Mode { STRUCTURAL, C1, C3, PERSONAL, ALL }

    override fun improve(schedule: Array<IntArray>, deadlineMs: Long): Boolean {
        if (System.currentTimeMillis() >= deadlineMs) return false
        val before = evaluate(schedule)
        val session = SearchSessionFull(problem, schedule, evaluate, better)
        val struct = StructuralMoveFactory(problem, rng)
        val paperBudget = minOf(deadlineMs, System.currentTimeMillis() + 800L)

        when (mode) {
            Mode.STRUCTURAL -> {
                var i = 0
                while (System.currentTimeMillis() < paperBudget && i++ < 120) {
                    val m = struct.anyStructural(session)
                        ?: PaperNeighborhoods.findEjectionToVacancy(
                            session, problem, rng.nextInt(problem.T), rng.nextInt(problem.S), 4, rng,
                        )
                        ?: continue
                    if (m.baseVersion == session.version) {
                        session.tryTransition(m, TransitionMode.STRICT)
                    }
                }
                VnsPolish(problem, rng, kMax = 4).run(session, paperBudget)
            }
            Mode.C1 -> {
                ConstraintPolishService(problem, rng).polishAll(session, paperBudget)
                // c1 / group / cov 重点
                repeat(40) {
                    if (System.currentTimeMillis() >= deadlineMs) return@repeat
                    val focus = "c1"
                    val m = ConstraintPolishers.propose(focus, session, problem, rng)
                        ?: ConstraintPolishers.propose("covU", session, problem, rng)
                        ?: ConstraintPolishers.propose("groupViol", session, problem, rng)
                        ?: return@repeat
                    if (m.baseVersion == session.version) {
                        session.tryTransition(m, TransitionMode.STRICT)
                    }
                }
            }
            Mode.C3 -> {
                repeat(50) {
                    if (System.currentTimeMillis() >= deadlineMs) return@repeat
                    val m = ConstraintPolishers.propose("c3", session, problem, rng)
                        ?: ConstraintPolishers.propose("c3n", session, problem, rng)
                        ?: ConstraintPolishers.propose("c3m", session, problem, rng)
                        ?: PaperNeighborhoods.kempeDay(
                            session, problem, rng.nextInt(problem.T),
                            1, minOf(2, problem.K - 1), 3, rng,
                        )
                        ?: return@repeat
                    if (m.baseVersion == session.version) {
                        session.tryTransition(m, TransitionMode.STRICT)
                    }
                }
            }
            Mode.PERSONAL -> {
                repeat(50) {
                    if (System.currentTimeMillis() >= deadlineMs) return@repeat
                    val m = ConstraintPolishers.propose("exact", session, problem, rng)
                        ?: ConstraintPolishers.propose("pref", session, problem, rng)
                        ?: ConstraintPolishers.propose("bal", session, problem, rng)
                        ?: ConstraintPolishers.propose("weekly", session, problem, rng)
                        ?: return@repeat
                    if (m.baseVersion == session.version) {
                        session.tryTransition(m, TransitionMode.STRICT)
                    }
                }
            }
            Mode.ALL -> {
                ConstraintPolishEngine(problem, rng).run(session, paperBudget)
                VnsPolish(problem, rng).run(session, minOf(deadlineMs, System.currentTimeMillis() + 400L))
            }
        }

        val after = session.bestReport
        if (!better(after, before)) return false
        // 書き戻し
        val best = session.best
        for (i in schedule.indices) {
            for (j in schedule[i].indices) schedule[i][j] = best[i][j]
        }
        return true
    }
}

/**
 * Hotfix 未接続時の G3Backend 完成形:
 * BuiltinG3（Session 直接）+ 家族別 Improver。
 */
class FullyWiredG3Backend(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val rng: Random = Random(0L),
) : G3Backend {

    private val direct = BuiltinG3Backend(problem, better, rng)
    private val structuralImp = BuiltinScheduleImprover(
        problem, evaluate, better, BuiltinScheduleImprover.Mode.STRUCTURAL, rng,
    )
    private val c1Imp = BuiltinScheduleImprover(
        problem, evaluate, better, BuiltinScheduleImprover.Mode.C1, Random(rng.nextLong()),
    )
    private val c3Imp = BuiltinScheduleImprover(
        problem, evaluate, better, BuiltinScheduleImprover.Mode.C3, Random(rng.nextLong()),
    )
    private val personalImp = BuiltinScheduleImprover(
        problem, evaluate, better, BuiltinScheduleImprover.Mode.PERSONAL, Random(rng.nextLong()),
    )

    override fun runStructural(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runBoth(session, deadlineMs, direct::runStructural, structuralImp)

    override fun runC1(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runBoth(session, deadlineMs, direct::runC1, c1Imp)

    override fun runC3(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runBoth(session, deadlineMs, direct::runC3, c3Imp)

    override fun runPersonal(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        runBoth(session, deadlineMs, direct::runPersonal, personalImp)

    private fun runBoth(
        session: SearchSessionFull,
        deadlineMs: Long,
        directFn: (SearchSessionFull, Long) -> G3StepResult,
        improver: ScheduleImprover,
    ): G3StepResult {
        var best = directFn(session, deadlineMs)
        if (System.currentTimeMillis() >= deadlineMs) return best
        val cand = SearchSessionFull.deepCopy(session.best)
        val before = session.bestReport
        if (improver.improve(cand, deadlineMs)) {
            val after = evaluate(cand)
            val prevRep = best.report
            if (better(after, before) && (prevRep == null || better(after, prevRep))) {
                best = G3StepResult(true, cand, after)
            }
        }
        return best
    }
}

/**
 * Delta 差し替え口の既定実装。
 * 勤務表制約はセル1つで複数族が動くため、正確性優先で常にフル評価する。
 * 真の差分評価は main の DeltaEvaluator を SessionWithDelta に渡すこと。
 */
class CoverageDeltaHook(
    private val full: (Array<IntArray>) -> ViolationReport,
) : DeltaEvaluateHook {
    constructor(@Suppress("UNUSED_PARAMETER") problem: Problem, full: (Array<IntArray>) -> ViolationReport) : this(full)

    override fun evaluateAfterMove(
        schedule: Array<IntArray>,
        writes: IntArray,
        undoSnap: IntArray,
    ): ViolationReport = full(schedule)
}

/** G2 既定: FocusAware（乱択のみではない） */
fun defaultFocusFixProvider(problem: Problem): FocusFixProvider =
    FocusAwareFixProvider(problem)
