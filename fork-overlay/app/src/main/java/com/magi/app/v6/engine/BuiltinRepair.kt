package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.preferred
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.dayDemand
import com.magi.app.v6.shiftDemand
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
        // covU/covO は shiftDemand の不足・過多セルを直接狙う（乱択日は最後）
        when (focus) {
            "covU", "shiftU", "groupViol" -> {
                proposeShiftCoverageUnder(board, rng)?.let { return it }
                proposeCoverage(board, rng)?.let { return it }
                val d = rng.nextInt(problem.T.coerceAtLeast(1))
                C1PrecisionMoves.fillCoverageDay(board, problem, d, dayDemandAt(problem, d), rng)?.let { return it }
                C1PrecisionMoves.fillGroupDay(board, problem, d, rng)?.let { return it }
            }
            "covO", "c2" -> {
                proposeShiftCoverageOver(board, rng)?.let { return it }
                proposeCoverageOver(board, rng)?.let { return it }
                val d = rng.nextInt(problem.T.coerceAtLeast(1))
                C1PrecisionMoves.trimCoverageDay(board, problem, d, dayDemandAt(problem, d), rng)?.let { return it }
            }
            "c1" -> {
                ConstraintPolishers.propose(focus, board, problem, rng)?.let { return it }
            }
        }
        if (focus != null) {
            ConstraintPolishers.propose(focus, board, problem, rng)?.let { return it }
        }
        return proposeRandom(board, rng)
    }

    private fun dayDemandAt(problem: Problem, d: Int): Int {
        val dd = problem.dayDemand
        return if (d in dd.indices) dd[d] else 0
    }

    private fun shiftNeed(problem: Problem, d: Int, k: Int): Int {
        val sd = problem.shiftDemand ?: return 0
        if (d !in sd.indices) return 0
        val row = sd[d]
        return if (k in row.indices) row[k] else 0
    }

    /** シフト別需要の不足 (d,k) を直接埋める */
    private fun proposeShiftCoverageUnder(board: BoardView, rng: Random): Move? {
        val sd = problem.shiftDemand
        if (sd == null) {
            android.util.Log.d("MAGI_FIX", "covU shiftDemand=null -> fallback")
            return null
        }
        val have = Array(problem.T) { IntArray(problem.K) }
        for (s in 0 until problem.S) {
            for (d in 0 until problem.T) {
                val k = board.current[s][d]
                if (k in 0 until problem.K) have[d][k]++
            }
        }
        val defs = ArrayList<IntArray>() // [d, k, deficit]
        for (d in 0 until problem.T) {
            if (d !in sd.indices) continue
            val row = sd[d]
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                if (need > 0 && have[d][k] < need) {
                    defs.add(intArrayOf(d, k, need - have[d][k]))
                }
            }
        }
        if (defs.isEmpty()) {
            android.util.Log.d("MAGI_FIX", "covU no shift deficits")
            return null
        }
        android.util.Log.d("MAGI_FIX", "covU deficits=${defs.size}")
        defs.shuffle(rng)
        defs.sortByDescending { it[2] }
        for (def in defs) {
            val d = def[0]
            val k = def[1]
            val staffs = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, d) && problem.canDo(s, k) && board.current[s][d] != k
            }.shuffled(rng)
            val ordered = staffs.sortedBy { s ->
                val cur = board.current[s][d]
                if (cur !in 0 until problem.K) 1
                else {
                    val needCur = shiftNeed(problem, d, cur)
                    val haveCur = have[d][cur]
                    when {
                        needCur > 0 && haveCur > needCur -> 0
                        needCur <= 0 -> 1
                        else -> 2
                    }
                }
            }
            for (s in ordered) {
                android.util.Log.d("MAGI_FIX", "covU propose s=$s d=$d ->k=$k from=${board.current[s][d]}")
                return buildSingleMove(board, problem, s, d, k, "g2.covU.shift")
            }
            // 単一セルでは足りない: 同日の過剰シフトから玉突き（2人交換）
            proposeCovUChainOnDay(board, rng, d, k, have)?.let { return it }
            // 多段 cascade（空き日へ押し出し）
            for (s in ordered.take(6)) {
                StructuralMoves.cascadeToCell(board, problem, s, d, k, rng, maxDepth = 3, source = "g2.covU.cascade")
                    ?.let {
                        android.util.Log.d("MAGI_FIX", "covU cascade s=$s d=$d ->k=$k")
                        return it
                    }
            }
        }
        return null
    }

    /**
     * 同日玉突き: 不足シフト k を埋めつつ、過剰シフトから1人を k へ、
     * 可能なら元の担当者を過剰側へ入れ替える（長さ2のチェーン）。
     */
    private fun proposeCovUChainOnDay(
        board: BoardView,
        rng: Random,
        day: Int,
        needK: Int,
        have: Array<IntArray>,
    ): Move? {
        val sd = problem.shiftDemand ?: return null
        val overKs = (0 until problem.K).filter { k ->
            k != needK && day in sd.indices && k < sd[day].size &&
                have[day][k] > sd[day][k]
        }.shuffled(rng)
        if (overKs.isEmpty()) {
            // 過剰が無くても、needK 以外で canDo の人を needK へ・元を REST 寄りへ
            val donors = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, day) &&
                    problem.canDo(s, needK) &&
                    board.current[s][day] != needK
            }.shuffled(rng)
            for (s in donors) {
                StructuralMoves.cascadeToCell(
                    board, problem, s, day, needK, rng, maxDepth = 4, source = "g2.covU.cascade2",
                )?.let { return it }
            }
            return null
        }
        for (ok in overKs) {
            val receivers = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, day) &&
                    problem.canDo(s, needK) &&
                    board.current[s][day] == ok
            }.shuffled(rng)
            for (s1 in receivers) {
                // s1: ok → needK（これだけで不足解消・過剰も減る）
                buildSingleMove(board, problem, s1, day, needK, "g2.covU.chain1")?.let {
                    android.util.Log.d("MAGI_FIX", "covU chain1 s=$s1 d=$day $ok->$needK")
                    return it
                }
            }
            // 2-swap: s1 が needK 以外、s2 が ok
            val needers = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, day) &&
                    problem.canDo(s, needK) &&
                    board.current[s][day] != needK &&
                    board.current[s][day] != ok
            }.shuffled(rng)
            val donors = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, day) && board.current[s][day] == ok
            }.shuffled(rng)
            for (s1 in needers) {
                val cur1 = board.current[s1][day]
                for (s2 in donors) {
                    if (s1 == s2) continue
                    // s1 → needK, s2 → cur1（玉突き交換）
                    if (!problem.canDo(s2, cur1)) continue
                    if (!problem.canDo(s1, needK)) continue
                    val writes = intArrayOf(s1, day, needK, s2, day, cur1)
                    android.util.Log.d("MAGI_FIX", "covU chain2 s1=$s1->$needK s2=$s2->$cur1 d=$day")
                    return MoveNormalizer.normalize(
                        board.version,
                        writes,
                        "chain_day",
                        "g2.covU.chain2",
                        problem.S,
                        problem.T,
                        problem.K,
                    )
                }
            }
        }
        return null
    }

    /** シフト別需要の過多から別シフトへ（休を含む通常シフト） */
    private fun proposeShiftCoverageOver(board: BoardView, rng: Random): Move? {
        val sd = problem.shiftDemand ?: return null
        val have = Array(problem.T) { IntArray(problem.K) }
        for (s in 0 until problem.S) {
            for (d in 0 until problem.T) {
                val k = board.current[s][d]
                if (k in 0 until problem.K) have[d][k]++
            }
        }
        val overs = ArrayList<IntArray>() // [d,k,excess]
        for (d in 0 until problem.T) {
            if (d !in sd.indices) continue
            val row = sd[d]
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                if (have[d][k] > need) overs.add(intArrayOf(d, k, have[d][k] - need))
            }
        }
        if (overs.isEmpty()) return null
        overs.shuffle(rng)
        overs.sortByDescending { it[2] }
        for (ov in overs) {
            val d = ov[0]
            val k = ov[1]
            val staffs = (0 until problem.S).filter { s ->
                !problem.wishLocked(s, d) && board.current[s][d] == k
            }.shuffled(rng)
            for (s in staffs) {
                val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                if (alt.isEmpty()) continue
                val prefer = alt.sortedBy { nk ->
                    val need = shiftNeed(problem, d, nk)
                    val hv = if (nk in 0 until problem.K) have[d][nk] else 0
                    if (need > hv) 0 else 1
                }
                return buildSingleMove(board, problem, s, d, prefer[0], "g2.covO.shift")
            }
        }
        return null
    }

    private fun proposeCoverage(board: BoardView, rng: Random): Move? {
        // shiftDemand が無い場合の日次需要フォールバック
        val need = problem.dayDemand
        val have = IntArray(problem.T)
        for (i in 0 until problem.S) {
            for (j in 0 until problem.T) {
                val k = board.current[i][j]
                if (k in 0 until problem.K) have[j]++
            }
        }
        val days = (0 until problem.T).filter {
            it < need.size && need[it] > 0 && have[it] < need[it]
        }.shuffled(rng)
        for (d in days) {
            val staffs = (0 until problem.S).filter {
                !problem.wishLocked(it, d)
            }.shuffled(rng)
            for (s in staffs) {
                val work = ShiftKinds.preferOnDuty(problem, s)
                if (work.isEmpty()) continue
                val sh = work[rng.nextInt(work.size)]
                if (board.current[s][d] == sh) continue
                return buildSingleMove(board, problem, s, d, sh, "g2.covU")
            }
        }
        return null
    }

    private fun proposeCoverageOver(board: BoardView, rng: Random): Move? {
        val need = problem.dayDemand
        val have = IntArray(problem.T)
        for (i in 0 until problem.S) {
            for (j in 0 until problem.T) {
                val k = board.current[i][j]
                if (k in 0 until problem.K) have[j]++
            }
        }
        val days = (0 until problem.T).filter {
            it < need.size && need[it] > 0 && have[it] > need[it]
        }.shuffled(rng)
        for (d in days) {
            val staffs = (0 until problem.S).filter {
                !problem.wishLocked(it, d)
            }.shuffled(rng)
            for (s in staffs) {
                val cur = board.current[s][d]
                val alt = problem.allowedShiftsForStaff(s).filter { it != cur }
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
        val cells = ArrayList<IntArray>()
        for (i in 0 until problem.S) {
            for (j in 0 until problem.T) {
                val pref = problem.preferred[i][j]
                if (pref >= 0 && !problem.wishLocked(i, j) && board.current[i][j] != pref && problem.canDo(i, pref)) {
                    cells.add(intArrayOf(i, j, pref))
                }
            }
        }
        if (cells.isEmpty()) return null
        val pick = cells[rng.nextInt(cells.size)]
        return buildSingleMove(board, problem, pick[0], pick[1], pick[2], "g2.wish")
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
