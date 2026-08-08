package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.canDo
import com.magi.app.v6.wishLocked
import com.magi.app.v6.allowedShiftsForStaff
import java.util.Random

/**
 * 前後左右・過去未来の構造研磨（正規化 Move）。
 *
 * - 前/後（時系列）: 同一職員の隣接日スワップ
 * - 左/右（職員）: 同一日の2名スワップ
 * - 過去/未来: 禁止連続・連勤の両端どちらを動かすか
 */
object DirectionalPolish {

    fun horizontalAdjacent(board: BoardView, problem: Problem, rng: Random): Move? {
        if (problem.T < 2) return null
        for (s in (0 until problem.S).shuffled(rng)) {
            val starts = listOf(rng.nextInt(problem.T - 1), rng.nextInt(problem.T - 1))
            for (d in starts) {
                if (problem.wishLocked(s, d) || problem.wishLocked(s, d + 1)) continue
                val a = board.current[s][d]
                val b = board.current[s][d + 1]
                if (a == b) continue
                if (!problem.canDo(s, b) || !problem.canDo(s, a)) continue
                return Move(board.version, intArrayOf(s, d, b, s, d + 1, a), "hswap", "dir.front-back")
            }
        }
        return null
    }

    fun verticalAdjacent(board: BoardView, problem: Problem, rng: Random): Move? {
        if (problem.S < 2) return null
        for (d in (0 until problem.T).shuffled(rng)) {
            val s1 = rng.nextInt(problem.S)
            val s2 = (s1 + 1 + rng.nextInt((problem.S - 1).coerceAtLeast(1))) % problem.S
            if (s1 == s2) continue
            if (problem.wishLocked(s1, d) || problem.wishLocked(s2, d)) continue
            val a = board.current[s1][d]
            val b = board.current[s2][d]
            if (a == b) continue
            if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) continue
            return Move(board.version, intArrayOf(s1, d, b, s2, d, a), "vswap", "dir.left-right")
        }
        return null
    }

    /** 禁止連続: 未来日を優先して崩し、だめなら過去日 */
    fun forbiddenPastOrFuture(board: BoardView, problem: Problem, rng: Random): Move? {
        val cfg = problem.constraintConfig()
        val night = cfg.nightShiftId
        val forbid = cfg.forbiddenAfterNight
        if (night !in 0 until problem.K || forbid.isEmpty()) return null
        val forbidSet = forbid.toSet()
        for (s in (0 until problem.S).shuffled(rng)) {
            for (d in 0 until problem.T - 1) {
                if (board.current[s][d] != night) continue
                if (board.current[s][d + 1] !in forbidSet) continue
                reassign(board, problem, s, d + 1, forbidSet + night, rng, "dir.future")?.let { return it }
                reassign(board, problem, s, d, setOf(night), rng, "dir.past")?.let { return it }
            }
        }
        return null
    }

    /** 連勤超過: 未来端 → 過去端の順で休へ */
    fun consecutivePastOrFuture(board: BoardView, problem: Problem, rng: Random): Move? {
        val maxRun = problem.constraintConfig().maxConsecutiveWork
        if (maxRun <= 0) return null
        val rest = ShiftKinds.REST
        for (s in (0 until problem.S).shuffled(rng)) {
            var runStart = -1
            var run = 0
            for (d in 0 until problem.T) {
                val isWork = board.current[s][d] != rest
                if (isWork) {
                    if (run == 0) runStart = d
                    run++
                    if (run > maxRun) {
                        if (!problem.wishLocked(s, d) && problem.canDo(s, rest)) {
                            return buildSingleMove(board, problem, s, d, rest, "dir.future-run")
                        }
                        if (runStart >= 0 && !problem.wishLocked(s, runStart) && problem.canDo(s, rest)) {
                            return buildSingleMove(board, problem, s, runStart, rest, "dir.past-run")
                        }
                    }
                } else {
                    run = 0
                    runStart = -1
                }
            }
        }
        return null
    }

    private fun reassign(
        board: BoardView,
        problem: Problem,
        s: Int,
        d: Int,
        avoid: Set<Int>,
        rng: Random,
        source: String,
    ): Move? {
        if (problem.wishLocked(s, d)) return null
        val cur = board.current[s][d]
        val alt = problem.allowedShiftsForStaff(s).filter { it != cur && it !in avoid }
        if (alt.isEmpty()) return null
        return buildSingleMove(board, problem, s, d, alt[rng.nextInt(alt.size)], source)
    }

    fun proposeAny(board: BoardView, problem: Problem, rng: Random): Move? {
        val fns = listOf(
            ::forbiddenPastOrFuture,
            ::consecutivePastOrFuture,
            ::horizontalAdjacent,
            ::verticalAdjacent,
        ).shuffled(rng)
        for (fn in fns) {
            fn(board, problem, rng)?.let { return it }
        }
        return null
    }
}
