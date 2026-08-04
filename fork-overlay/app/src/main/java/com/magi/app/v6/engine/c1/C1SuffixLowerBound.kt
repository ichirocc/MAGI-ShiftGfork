package com.magi.app.v6.engine.c1

import com.magi.app.v6.Problem
import com.magi.app.v6.dayDemand
import com.magi.app.v6.engine.ShiftKinds

object C1SuffixLowerBound {
    const val MAX_EXACT_CELLS = 262_144L

    fun dayDeficits(problem: Problem, schedule: Array<IntArray>): IntArray {
        val def = IntArray(problem.T)
        val have = IntArray(problem.T)
        for (s in 0 until problem.S) {
            for (d in 0 until problem.T) {
                if (!ShiftKinds.isRest(schedule[s][d])) have[d]++
            }
        }
        val demand = problem.dayDemand
        for (d in 0 until problem.T) {
            val need = if (d < demand.size) demand[d] else 0
            if (need > have[d]) def[d] = need - have[d]
        }
        return def
    }

    fun totalDeficit(problem: Problem, schedule: Array<IntArray>): Int =
        dayDeficits(problem, schedule).sum()

    fun suffixDeficitFrom(deficits: IntArray, day: Int): Int {
        var s = 0
        for (d in day until deficits.size) s += deficits[d]
        return s
    }

    fun useExact(problem: Problem): Boolean =
        problem.S.toLong() * problem.T.toLong() <= MAX_EXACT_CELLS
}
