package com.magi.app.v6.engine.c1

import com.magi.app.v6.Problem
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
        val demand = coverageDemand(problem)
        for (d in 0 until problem.T) {
            val need = if (d < demand.size) demand[d] else 0
            if (need > have[d]) def[d] = need - have[d]
        }
        return def
    }

    /** domain は dayDemand メンバ、本番は拡張をリフレクションなしで need1 集計にフォールバック */
    private fun coverageDemand(problem: Problem): IntArray {
        return runCatching {
            val m = problem.javaClass.methods.firstOrNull { it.name == "getDayDemand" && it.parameterCount == 0 }
            if (m != null) return@runCatching m.invoke(problem) as IntArray
            val f = problem.javaClass.getField("dayDemand")
            f.get(problem) as IntArray
        }.getOrElse {
            // 本番 Problem: need1[k][j]
            runCatching {
                val need1 = problem.javaClass.getMethod("getNeed1").invoke(problem) as Array<IntArray>
                val restIdx = problem.javaClass.getMethod("getRestIdx").invoke(problem) as Int
                val t = problem.T
                val kmax = need1.size
                val out = IntArray(t)
                for (j in 0 until t) {
                    var sum = 0
                    for (k in 0 until kmax) {
                        if (k == restIdx) continue
                        val row = need1[k]
                        if (j < row.size && row[j] > 0) sum += row[j]
                    }
                    out[j] = sum
                }
                out
            }.getOrDefault(IntArray(problem.T))
        }
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
