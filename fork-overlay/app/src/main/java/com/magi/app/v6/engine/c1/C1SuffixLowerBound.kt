package com.magi.app.v6.engine.c1

import com.magi.app.v6.Problem
import com.magi.app.v6.engine.ShiftKinds

/**
 * 期間カバレッジ不足の保守的下界。
 *
 * 各日 d の不足 max(0, demand[d] - onDuty[d]) の合計を下界とする。
 * セル数が大きいときは exact DP を避け、この O(S*T) 集計に退避
 * （main の MAX_EXACT_LOWER_BOUND_CELLS と同趣旨）。
 *
 * 返り値が大きい goal から優先するソートキーに使う。
 */
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
        for (d in 0 until problem.T) {
            val need = problem.dayDemand.getOrElse(d) { 0 }
            if (need > have[d]) def[d] = need - have[d]
        }
        return def
    }

    /** 総不足（下界） */
    fun totalDeficit(problem: Problem, schedule: Array<IntArray>): Int =
        dayDeficits(problem, schedule).sum()

    /**
     * 日 d から終端までの suffix 不足合計（単純累積）。
     * exact DP が重いときの代替。
     */
    fun suffixDeficitFrom(deficits: IntArray, day: Int): Int {
        var s = 0
        for (d in day until deficits.size) s += deficits[d]
        return s
    }

    fun useExact(problem: Problem): Boolean =
        problem.S.toLong() * problem.T.toLong() <= MAX_EXACT_CELLS
}
