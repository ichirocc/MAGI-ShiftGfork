package com.magi.app.v6.engine
import com.magi.app.v6.dayDemand
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.preferred

import com.magi.app.v6.Problem
import com.magi.app.v6.skillMatrix
import com.magi.app.v6.ViolationReport
import java.util.Random

/**
 * focus 別の修復手生成（G2 既定）。
 * 本番 find*Fix が無いスタンドアロンでも動く実装。
 */
class FocusAwareFixProvider(
    private val problem: Problem,
) : FocusFixProvider {

    override fun propose(focus: String?, board: BoardView, problem: Problem, rng: Random): Move? {
        // C1 日次一括（高精度・低評価回数）を coverage 系で優先
        when (focus) {
            "covU", "shiftU", "c1", "groupViol" -> {
                val d = rng.nextInt(problem.T)
                val need = problem.dayDemand[d]
                C1PrecisionMoves.fillCoverageDay(board, problem, d, need, rng)?.let { return it }
                C1PrecisionMoves.fillGroupDay(board, problem, d, rng)?.let { return it }
            }
            "covO", "c2" -> {
                val d = rng.nextInt(problem.T)
                val need = problem.dayDemand[d]
                C1PrecisionMoves.trimCoverageDay(board, problem, d, need, rng)?.let { return it }
            }
        }
        if (focus != null) {
            ConstraintPolishers.propose(focus, board, problem, rng)?.let { return it }
        }
        return proposeRandom(board, rng)
    }

    private fun proposeCoverage(board: BoardView, rng: Random): Move? {
        val need = problem.dayDemand
        val have = IntArray(problem.T)
        for (i in 0 until problem.S) {
            for (j in 0 until problem.T) if (board.current[i][j] > 0) have[j]++
        }
        val days = (0 until problem.T).filter { need[it] > 0 && have[it] < need[it] }.shuffled(rng)
        for (d in days) {
            val staffs = (0 until problem.S).filter {
                !problem.wishLocked(it, d) && board.current[it][d] == 0
            }.shuffled(rng)
            for (s in staffs) {
                val work = ShiftKinds.preferOnDuty(problem, s)
                if (work.isEmpty()) continue
                val sh = work[rng.nextInt(work.size)]
                return buildSingleMove(board, problem, s, d, sh, "g2.covU")
            }
        }
        return null
    }

    private fun proposeCoverageOver(board: BoardView, rng: Random): Move? {
        // 過多日: 別シフトへ（休含む）
        val need = problem.dayDemand
        val have = IntArray(problem.T)
        for (i in 0 until problem.S) {
            for (j in 0 until problem.T) if (board.current[i][j] > 0) have[j]++
        }
        val days = (0 until problem.T).filter { need[it] > 0 && have[it] > need[it] }.shuffled(rng)
        for (d in days) {
            val staffs = (0 until problem.S).filter {
                !problem.wishLocked(it, d) && board.current[it][d] > 0
            }.shuffled(rng)
            for (s in staffs) {
                val cur = board.current[s][d]
                val alt = problem.allowedShiftsForStaff(s).filter { it != cur } // 休含む
                if (alt.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "g2.covO")
            }
        }
        return null
    }

    private fun proposeExact(board: BoardView, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[s][k]
                val hi = problem.rangeHi[s][k]
                if (lo == Int.MIN_VALUE || lo != hi) continue
                var c = 0
                for (d in 0 until problem.T) if (board.current[s][d] == k) c++
                if (c == lo) continue
                if (c < lo) {
                    val days = (0 until problem.T).filter {
                        !problem.wishLocked(s, it) && board.current[s][it] != k && problem.canDo(s, k)
                    }.shuffled(rng)
                    if (days.isNotEmpty()) {
                        return buildSingleMove(board, problem, s, days[0], k, "g2.exact+")
                    }
                } else {
                    val days = (0 until problem.T).filter {
                        !problem.wishLocked(s, it) && board.current[s][it] == k
                    }.shuffled(rng)
                    for (d in days) {
                        val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                        if (alt.isEmpty()) continue
                        return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "g2.exact-")
                    }
                }
            }
        }
        return null
    }

    private fun proposeRange(board: BoardView, rng: Random): Move? = proposeExact(board, rng)

    private fun proposeBalance(board: BoardView, rng: Random): Move? {
        val work = IntArray(problem.S) { s ->
            (0 until problem.T).count { board.current[s][it] > 0 }
        }
        val hi = work.indices.maxByOrNull { work[it] } ?: return null
        val lo = work.indices.minByOrNull { work[it] } ?: return null
        if (work[hi] <= work[lo] + 1) return null
        val days = (0 until problem.T).filter {
            !problem.wishLocked(hi, it) && board.current[hi][it] > 0 &&
                !problem.wishLocked(lo, it) && board.current[lo][it] == 0
        }.shuffled(rng)
        if (days.isEmpty()) return null
        val d = days[0]
        val sh = board.current[hi][d]
        if (!problem.canDo(lo, sh) || !problem.canDo(hi, ShiftKinds.REST)) return null
        // two-cell: give work to lo, clear hi — as one Move
        return Move(
            board.version,
            intArrayOf(hi, d, 0, lo, d, sh),
            "swap_balance",
            "g2.bal",
        )
    }

    private fun proposeWish(board: BoardView, rng: Random): Move? {
        val cells = ArrayList<Pair<Int, Int>>()
        for (i in 0 until problem.S) for (j in 0 until problem.T) {
            val pref = problem.preferred[i][j]
            if (pref >= 0 && !problem.wishLocked(i, j) && board.current[i][j] != pref && problem.canDo(i, pref)) {
                cells.add(i to j)
            }
        }
        if (cells.isEmpty()) return null
        val (s, d) = cells[rng.nextInt(cells.size)]
        return buildSingleMove(board, problem, s, d, problem.preferred[s][d], "g2.wish")
    }


    private fun proposeBreakStreak(board: BoardView, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            var run = 0
            for (d in 0 until problem.T) {
                if (board.current[s][d] > 0) {
                    run++
                    if (run >= 3 && !problem.wishLocked(s, d) && problem.canDo(s, ShiftKinds.REST)) {
                        return buildSingleMove(board, problem, s, d, ShiftKinds.REST, "g2.streak")
                    }
                } else run = 0
            }
        }
        return proposeRandom(board, rng)
    }

    private fun proposeApt(board: BoardView, rng: Random): Move? {
        val skill = problem.skillMatrix
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in (0 until problem.T).shuffled(rng)) {
                if (problem.wishLocked(s, d)) continue
                val sh = board.current[s][d]
                if (sh in 0 until problem.K && !skill[s][sh]) {
                    val alt = problem.allowedShiftsForStaff(s).filter { it >= 0 && it < skill[s].size && skill[s][it] }
                    if (alt.isEmpty()) continue
                    return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "g2.apt")
                }
            }
        }
        return proposeRandom(board, rng)
    }

    private fun proposeRandom(board: BoardView, rng: Random): Move? {
        val s = rng.nextInt(problem.S)
        val d = rng.nextInt(problem.T)
        if (problem.wishLocked(s, d)) return null
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) return null
        return buildSingleMove(board, problem, s, d, allowed[rng.nextInt(allowed.size)], "g2.rnd")
    }
}

/**
 * セッション上で STRICT 近傍を回す G3 実装（Noop 置換）。
 */
class BuiltinG3Backend(
    private val problem: Problem,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val rng: Random = Random(0L),
) : G3Backend {
    private val struct = StructuralMoveFactory(problem, rng)

    override fun runStructural(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        polish(session, deadlineMs, mode = Mode.STRUCTURAL)

    override fun runC1(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        polish(session, deadlineMs, mode = Mode.DAY)

    override fun runC3(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        polish(session, deadlineMs, mode = Mode.WINDOW)

    override fun runPersonal(session: SearchSessionFull, deadlineMs: Long): G3StepResult =
        polish(session, deadlineMs, mode = Mode.PERSONAL)

    private enum class Mode { STRUCTURAL, DAY, WINDOW, PERSONAL }

    private fun polish(session: SearchSessionFull, deadlineMs: Long, mode: Mode): G3StepResult {
        if (System.currentTimeMillis() >= deadlineMs) return G3StepResult.NONE
        val beforeHard = session.bestReport.hard
        val beforeSoft = session.bestReport.weightedScore
        var guard = 0
        while (System.currentTimeMillis() < deadlineMs && guard++ < 400) {
            val move = when (mode) {
                Mode.STRUCTURAL -> proposeSwapOrBlock(session)
                Mode.DAY -> proposeC1Day(session) ?: proposeDayLns(session)
                Mode.WINDOW -> proposeWindow(session)
                Mode.PERSONAL -> proposePersonal(session)
            } ?: continue
            session.tryTransition(move, TransitionMode.STRICT)
        }
        return G3StepResult.of(session, beforeHard, beforeSoft)
    }

    private fun proposeSwapOrBlock(session: SearchSessionFull): Move? {
        when (rng.nextInt(8)) {
            0 -> struct.anyStructural(session)?.let { return it }
            1 -> struct.guidedBlockTransfer(session)?.let { return it }
            2 -> struct.guidedBlockExchange(session)?.let { return it }
            3 -> {
                val d = rng.nextInt(problem.T)
                struct.tripleCycle(session, d)?.let { return it }
            }
            4 -> PaperNeighborhoods.findEjectionToVacancy(
                session, problem, rng.nextInt(problem.T), rng.nextInt(problem.S), 4, rng,
            )?.let { return it }
            5 -> {
                val d = rng.nextInt(problem.T)
                val a = 1 + rng.nextInt(maxOf(1, problem.K - 1))
                val b = (a % maxOf(1, problem.K - 1)) + 1
                PaperNeighborhoods.kempeDay(session, problem, d, a, b, 3, rng)?.let { return it }
            }
            6 -> PaperNeighborhoods.ruinRecreateDay(
                session, problem, rng.nextInt(problem.T), 3, rng,
            )?.let { return it }
            else -> {
                if (problem.T >= 2) {
                    val s = rng.nextInt(problem.S)
                    val d1 = rng.nextInt(problem.T)
                    var d2 = rng.nextInt(problem.T)
                    if (d1 == d2) d2 = (d2 + 1) % problem.T
                    return PaperNeighborhoods.horizontalSwap(session, problem, s, d1, d2)
                        ?: buildSwapDaysMove(session, problem, s, d1, d2, "g3.struct.swap")
                }
            }
        }
        return proposeWindow(session)
    }

    private fun proposeC1Day(session: SearchSessionFull): Move? {
        val d = rng.nextInt(problem.T)
        val need = problem.dayDemand[d]
        return C1PrecisionMoves.fillCoverageDay(session, problem, d, need, rng)
            ?: C1PrecisionMoves.trimCoverageDay(session, problem, d, need, rng)
            ?: C1PrecisionMoves.fillGroupDay(session, problem, d, rng)
            ?: C1PrecisionMoves.dayTripleRepair(session, problem, d, rng)
    }

    private fun proposeWindow(session: SearchSessionFull): Move? {
        val s = rng.nextInt(problem.S)
        val len = 2 + rng.nextInt(3)
        if (problem.T < len) return null
        val start = rng.nextInt(problem.T - len + 1)
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) return null
        return buildWindowFillMove(
            session, problem, s, start, len, allowed[rng.nextInt(allowed.size)], "g3.window",
        )
    }

    private fun proposeDayLns(session: SearchSessionFull): Move? {
        val day = rng.nextInt(problem.T)
        val n = 2 + rng.nextInt(minOf(3, problem.S.coerceAtLeast(2)))
        val buf = IntArray(n * 2)
        var k = 0
        var tries = 0
        while (k < n * 2 && tries++ < n * 8) {
            val s = rng.nextInt(problem.S)
            if (problem.wishLocked(s, day)) continue
            val allowed = problem.allowedShiftsForStaff(s)
            if (allowed.isEmpty()) continue
            buf[k++] = s
            buf[k++] = allowed[rng.nextInt(allowed.size)]
        }
        if (k < 4) return null
        return buildDayLnsMove(session, problem, day, buf.copyOf(k), "g3.day")
    }

    private fun proposePersonal(session: SearchSessionFull): Move? {
        val fix = FocusAwareFixProvider(problem)
        return fix.propose("exact", session, problem, rng)
            ?: fix.propose("wish", session, problem, rng)
            ?: fix.propose("bal", session, problem, rng)
    }
}
