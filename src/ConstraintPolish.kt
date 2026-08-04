package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.constraintConfig
import com.magi.app.v6.groupDemand
import com.magi.app.v6.skillMatrix
import com.magi.app.v6.staffGroup
import java.util.Random

/**
 * 制約キーごとの違反研磨（STRICT 1 手）。
 * 全 breakdown キーに対応する専用 proposers。
 */
object ConstraintPolishers {

    /** 研磨対象の全キー（評価器と対応） */
    val ALL_KEYS: List<String> = listOf(
        // HARD first
        "covU", "shiftU", "exact", "illegal", "lockBreak", "c3n", "c1", "groupViol",
        // SOFT
        "covO", "range", "low", "high", "wish", "pref", "bal", "fair",
        "weekly", "c3", "c3m", "c3mn", "c2", "apt",
    )

    fun propose(
        key: String,
        board: BoardView,
        problem: Problem,
        rng: Random,
    ): Move? = when (key) {
        "covU", "shiftU" -> polishCovU(board, problem, rng)
        "covO" -> polishCovO(board, problem, rng)
        "exact" -> polishExact(board, problem, rng)
        "range", "low" -> polishLow(board, problem, rng)
        "high" -> polishHigh(board, problem, rng)
        "illegal" -> polishIllegal(board, problem, rng)
        "lockBreak" -> polishLockBreak(board, problem, rng)
        "wish", "pref" -> polishWish(board, problem, rng)
        "bal", "fair" -> polishBalance(board, problem, rng)
        "weekly" -> polishWeekly(board, problem, rng)
        "c3" -> polishC3(board, problem, rng)
        "c3m" -> polishC3m(board, problem, rng)
        "c3mn" -> polishC3mn(board, problem, rng)
        "c3n" -> polishC3n(board, problem, rng)
        "c1" -> polishC1(board, problem, rng)
        "c2" -> polishC2(board, problem, rng)
        "apt" -> polishApt(board, problem, rng)
        "groupViol" -> polishGroup(board, problem, rng)
        else -> null
    }

    // ---- individual polishers ----

    private fun polishCovU(board: BoardView, problem: Problem, rng: Random): Move? {
        val have = dayWorkCount(board, problem)
        for (d in (0 until problem.T).shuffled(rng)) {
            if (problem.dayDemand[d] <= 0 || have[d] >= problem.dayDemand[d]) continue
            for (s in (0 until problem.S).shuffled(rng)) {
                if (problem.wishLocked(s, d) || board.current[s][d] > 0) continue
                val work = ShiftKinds.preferOnDuty(problem, s)
                if (work.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, work[rng.nextInt(work.size)], "polish.covU")
            }
        }
        return null
    }

    private fun polishCovO(board: BoardView, problem: Problem, rng: Random): Move? {
        // 過多日: 別シフトへ付け替え（休含む）
        val have = dayWorkCount(board, problem)
        for (d in (0 until problem.T).shuffled(rng)) {
            if (problem.dayDemand[d] <= 0 || have[d] <= problem.dayDemand[d]) continue
            for (s in (0 until problem.S).shuffled(rng)) {
                if (problem.wishLocked(s, d) || board.current[s][d] == 0) continue
                val cur = board.current[s][d]
                val alt = problem.allowedShiftsForStaff(s).filter { it != cur }
                if (alt.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.covO")
            }
        }
        return null
    }

    private fun polishExact(board: BoardView, problem: Problem, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[s][k]
                val hi = problem.rangeHi[s][k]
                if (lo == Int.MIN_VALUE || lo != hi) continue
                val c = (0 until problem.T).count { board.current[s][it] == k }
                if (c == lo) continue
                if (c < lo) {
                    val d = (0 until problem.T).firstOrNull {
                        !problem.wishLocked(s, it) && board.current[s][it] != k && problem.canDo(s, k)
                    } ?: continue
                    return buildSingleMove(board, problem, s, d, k, "polish.exact+")
                } else {
                    val d = (0 until problem.T).firstOrNull {
                        !problem.wishLocked(s, it) && board.current[s][it] == k
                    } ?: continue
                    val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                    if (alt.isEmpty()) continue
                    return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.exact-")
                }
            }
        }
        return null
    }

    private fun polishLow(board: BoardView, problem: Problem, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[s][k]
                if (lo == Int.MIN_VALUE || problem.rangeHi[s][k] == lo) continue
                val c = (0 until problem.T).count { board.current[s][it] == k }
                if (c >= lo) continue
                val d = (0 until problem.T).shuffled(rng).firstOrNull {
                    !problem.wishLocked(s, it) && board.current[s][it] != k && problem.canDo(s, k)
                } ?: continue
                return buildSingleMove(board, problem, s, d, k, "polish.low")
            }
        }
        return null
    }

    private fun polishHigh(board: BoardView, problem: Problem, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            for (k in 0 until problem.K) {
                val hi = problem.rangeHi[s][k]
                if (hi == Int.MAX_VALUE) continue
                val c = (0 until problem.T).count { board.current[s][it] == k }
                if (c <= hi) continue
                val d = (0 until problem.T).shuffled(rng).firstOrNull {
                    !problem.wishLocked(s, it) && board.current[s][it] == k
                } ?: continue
                val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                if (alt.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.high")
            }
        }
        return null
    }

    private fun polishIllegal(board: BoardView, problem: Problem, rng: Random): Move? {
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in (0 until problem.T).shuffled(rng)) {
                if (problem.wishLocked(s, d)) continue
                val sh = board.current[s][d]
                if (sh in 0 until problem.K && problem.canDo(s, sh)) continue
                val alt = problem.allowedShiftsForStaff(s)
                if (alt.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.illegal")
            }
        }
        return null
    }

    private fun polishLockBreak(board: BoardView, problem: Problem, rng: Random): Move? {
        // lock は Session が守る。ここは preferred を満たすよう非 lock を直すのみ
        return polishWish(board, problem, rng)
    }

    private fun polishWish(board: BoardView, problem: Problem, rng: Random): Move? {
        val cells = ArrayList<Pair<Int, Int>>()
        for (i in 0 until problem.S) for (j in 0 until problem.T) {
            val pref = problem.preferred[i][j]
            if (pref >= 0 && !problem.wishLocked(i, j) &&
                board.current[i][j] != pref && problem.canDo(i, pref)
            ) {
                cells.add(i to j)
            }
        }
        if (cells.isEmpty()) return null
        val (s, d) = cells[rng.nextInt(cells.size)]
        return buildSingleMove(board, problem, s, d, problem.preferred[s][d], "polish.wish")
    }

    private fun polishBalance(board: BoardView, problem: Problem, rng: Random): Move? {
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
        return Move(board.version, intArrayOf(hi, d, 0, lo, d, sh), "swap_balance", "polish.bal")
    }

    private fun polishWeekly(board: BoardView, problem: Problem, rng: Random): Move? {
        // 7日周期平準化: 多い曜日のシフトを少ない曜日へ付け替え
        val s = rng.nextInt(problem.S)
        val k = rng.nextInt(problem.K)
        val wdCnt = IntArray(7)
        for (d in 0 until problem.T) {
            if (board.current[s][d] == k) wdCnt[d % 7]++
        }
        val hi = wdCnt.indices.maxByOrNull { wdCnt[it] } ?: return null
        val lo = wdCnt.indices.minByOrNull { wdCnt[it] } ?: return null
        if (wdCnt[hi] - wdCnt[lo] <= 0) return null
        val hiDays = (0 until problem.T).filter {
            it % 7 == hi && board.current[s][it] == k && !problem.wishLocked(s, it)
        }
        val loDays = (0 until problem.T).filter {
            it % 7 == lo && board.current[s][it] != k && !problem.wishLocked(s, it) &&
                problem.canDo(s, k)
        }
        if (hiDays.isEmpty() || loDays.isEmpty()) return null
        val dHi = hiDays[rng.nextInt(hiDays.size)]
        val dLo = loDays[rng.nextInt(loDays.size)]
        val shLo = board.current[s][dLo]
        if (!problem.canDo(s, shLo)) return null
        // 入れ替え: hi日の k と lo日の内容を交換
        return Move(
            board.version,
            intArrayOf(s, dHi, shLo, s, dLo, k),
            "weekly",
            "polish.weekly.level",
        )
    }

    private fun polishC3(board: BoardView, problem: Problem, rng: Random): Move? {
        val maxRun = problem.constraintConfig().maxConsecutiveWork
        if (maxRun <= 0) return null
        for (s in (0 until problem.S).shuffled(rng)) {
            var run = 0
            for (d in 0 until problem.T) {
                if (board.current[s][d] > 0) {
                    run++
                    if (run > maxRun && !problem.wishLocked(s, d) && problem.canDo(s, ShiftKinds.REST)) {
                        return buildSingleMove(board, problem, s, d, ShiftKinds.REST, "polish.c3")
                    }
                } else run = 0
            }
        }
        return null
    }

    private fun polishC3m(board: BoardView, problem: Problem, rng: Random): Move? {
        // 短い休みの前後を休シフトで延ばす
        val minOff = problem.constraintConfig().minConsecutiveOff
        if (minOff <= 1) return null
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in 1 until problem.T - 1) {
                if (board.current[s][d] == 0 && board.current[s][d - 1] > 0 && board.current[s][d + 1] > 0) {
                    // isolated off — make next day off too if possible
                    if (!problem.wishLocked(s, d + 1) && board.current[s][d + 1] > 0 && problem.canDo(s, ShiftKinds.REST)) {
                        return buildSingleMove(board, problem, s, d + 1, ShiftKinds.REST, "polish.c3m")
                    }
                }
            }
        }
        return null
    }

    private fun polishC3mn(board: BoardView, problem: Problem, rng: Random): Move? =
        polishC3(board, problem, rng)

    private fun polishC3n(board: BoardView, problem: Problem, rng: Random): Move? {
        val cfg = problem.constraintConfig()
        val night = cfg.nightShiftId
        if (night !in 0 until problem.K) return null
        val forbid = cfg.forbiddenAfterNight
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in 0 until problem.T - 1) {
                if (board.current[s][d] != night) continue
                if (board.current[s][d + 1] !in forbid) continue
                if (problem.wishLocked(s, d + 1)) continue
                // next day → 休シフト等
                val alt = problem.allowedShiftsForStaff(s).filter { it !in forbid && it != night }
                val sh = when {
                    problem.canDo(s, ShiftKinds.REST) -> ShiftKinds.REST
                    alt.isNotEmpty() -> alt[rng.nextInt(alt.size)]
                    else -> continue
                }
                return buildSingleMove(board, problem, s, d + 1, sh, "polish.c3n")
            }
        }
        return null
    }

    private fun polishC1(board: BoardView, problem: Problem, rng: Random): Move? {
        val groups = problem.staffGroup()
        for (d in (0 until problem.T).shuffled(rng)) {
            if (problem.dayDemand[d] <= 0) continue
            val present = HashSet<Int>()
            for (i in 0 until problem.S) if (board.current[i][d] > 0) present.add(groups[i])
            val needG = if (problem.S >= 2) 2 else 1
            if (present.size >= needG) continue
            // add staff from missing group
            for (s in (0 until problem.S).shuffled(rng)) {
                if (groups[s] in present) continue
                if (problem.wishLocked(s, d) || board.current[s][d] > 0) continue
                val work = ShiftKinds.preferOnDuty(problem, s)
                if (work.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, work[rng.nextInt(work.size)], "polish.c1")
            }
        }
        return null
    }

    private fun polishC2(board: BoardView, problem: Problem, rng: Random): Move? {
        // 同一日・同一シフトの過剰を別シフトへ
        for (d in (0 until problem.T).shuffled(rng)) {
            val cnt = IntArray(problem.K)
            for (i in 0 until problem.S) {
                val sh = board.current[i][d]
                if (sh in 1 until problem.K) cnt[sh]++
            }
            for (k in 1 until problem.K) {
                val need = problem.shiftDemand?.getOrNull(d)?.getOrNull(k)
                    ?: if (problem.dayDemand[d] > 0) 1 else 0
                if (need <= 0 || cnt[k] <= need + 1) continue
                for (s in (0 until problem.S).shuffled(rng)) {
                    if (problem.wishLocked(s, d) || board.current[s][d] != k) continue
                    val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                    if (alt.isEmpty()) continue
                    return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.c2")
                }
            }
        }
        return polishCovO(board, problem, rng)
    }

    private fun polishApt(board: BoardView, problem: Problem, rng: Random): Move? {
        val skill = problem.skillMatrix()
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in (0 until problem.T).shuffled(rng)) {
                if (problem.wishLocked(s, d)) continue
                val sh = board.current[s][d]
                if (sh !in 0 until problem.K || skill[s][sh]) continue
                val alt = problem.allowedShiftsForStaff(s).filter { it < skill[s].size && skill[s][it] }
                if (alt.isEmpty()) continue
                return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], "polish.apt")
            }
        }
        return null
    }

    private fun polishGroup(board: BoardView, problem: Problem, rng: Random): Move? {
        val groups = problem.staffGroup()
        val demand = problem.groupDemand()
        val nG = demand.firstOrNull()?.size ?: return null
        for (d in (0 until problem.T).shuffled(rng)) {
            val have = IntArray(nG)
            for (i in 0 until problem.S) {
                if (board.current[i][d] > 0) {
                    val g = groups[i]
                    if (g in 0 until nG) have[g]++
                }
            }
            for (g in 0 until nG) {
                if (demand[d][g] <= 0 || have[g] >= demand[d][g]) continue
                for (s in (0 until problem.S).shuffled(rng)) {
                    if (groups[s] != g) continue
                    if (problem.wishLocked(s, d) || board.current[s][d] > 0) continue
                    val work = ShiftKinds.preferOnDuty(problem, s)
                    if (work.isEmpty()) continue
                    return buildSingleMove(board, problem, s, d, work[rng.nextInt(work.size)], "polish.group")
                }
            }
        }
        return null
    }

    private fun dayWorkCount(board: BoardView, problem: Problem): IntArray {
        val have = IntArray(problem.T)
        for (i in 0 until problem.S) for (j in 0 until problem.T) {
            if (board.current[i][j] > 0) have[j]++
        }
        return have
    }
}
