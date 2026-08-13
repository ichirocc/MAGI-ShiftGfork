package com.magi.app.v6.engine

import com.magi.app.v6.Problem

/**
 * HARD 主族（covU / c3n）の軽量再集計。
 *
 * フル Checker の代替ではない。差分ホットパスの breakdown に載せ、
 * RSI / CoverageFocus / 進捗ログが族を見失わないようにする。
 * 意味論は [Evaluator] / [Problem.covUCell] / c3check(forbidden) に揃える。
 */
object HardFamilyDelta {

    data class Counts(val covU: Int, val c3n: Int)

    fun count(problem: Problem, schedule: Array<IntArray>): Counts {
        if (problem.S <= 0 || problem.T <= 0 || problem.K <= 0) return Counts(0, 0)
        return Counts(
            covU = countCovU(problem, schedule),
            c3n = countC3n(problem, schedule),
        )
    }

    fun countCovU(problem: Problem, schedule: Array<IntArray>): Int {
        val S = problem.S
        val T = problem.T
        val K = problem.K
        var covU = 0
        for (j in 0 until T) {
            for (k in 0 until K) {
                var got = 0
                for (i in 0 until S) {
                    if (i < schedule.size && j < schedule[i].size && schedule[i][j] == k) got++
                }
                covU += problem.covUCell(k, j, got)
            }
        }
        return covU
    }

    /** forbidden c3n の #fire 件数（Evaluator.c3check(..., forbidden=true) と同式） */
    fun countC3n(problem: Problem, schedule: Array<IntArray>): Int {
        val S = problem.S
        val T = problem.T
        var sub = 0
        for (c in problem.cons3n) {
            val seq = c.seq
            val D = seq.size
            if (D == 0 || D > T) continue
            val first = seq[0]
            for (i in 0 until S) {
                if (i >= schedule.size) continue
                val row = schedule[i]
                var j = 0
                while (j <= T - D) {
                    if (j < row.size && row[j] == first) {
                        var z = 0
                        var l = 1
                        while (l < D) {
                            if (j + l < row.size && row[j + l] == seq[l]) z++
                            l++
                        }
                        if (z == D - 1) sub++
                    }
                    j++
                }
            }
        }
        return sub
    }

    fun breakdown(counts: Counts): Map<String, Int> = buildMap {
        if (counts.covU > 0) put("covU", counts.covU)
        if (counts.c3n > 0) put("c3n", counts.c3n)
    }
}
