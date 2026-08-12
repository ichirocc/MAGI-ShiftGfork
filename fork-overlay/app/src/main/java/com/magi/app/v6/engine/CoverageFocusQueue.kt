package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.canDo
import com.magi.app.v6.shiftDemand
import com.magi.app.v6.wishLocked

/**
 * CoverageDiag 相当の「未到達 (day, shift)」を RSI / covU 修復が必ず試すためのキュー。
 *
 * - open: 担当可・非固定の補充者がいる不足 → 探索で減らせる
 * - 優先: deficit 大、movers 多い
 */
object CoverageFocusQueue {
    data class Target(
        val day: Int,
        val shift: Int,
        val deficit: Int,
        val movers: Int,
    )

    @Volatile
    private var targets: List<Target> = emptyList()

    fun clear() {
        targets = emptyList()
    }

    fun snapshot(): List<Target> = targets

    fun rebuild(problem: Problem, schedule: Array<IntArray>) {
        val sd = problem.shiftDemand
        if (sd == null || problem.T <= 0 || problem.S <= 0) {
            targets = emptyList()
            return
        }
        val bits = BitMasks.from(problem, schedule)
        val list = ArrayList<Target>()
        for (d in 0 until problem.T) {
            val row = sd.getOrNull(d) ?: continue
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                if (need <= 0) continue
                val have: Int
                val movers: Int
                if (bits != null) {
                    have = bits.countDayShift(d, k)
                    if (have >= need) continue
                    movers = bits.openMoversCount(d, k)
                } else {
                    have = (0 until problem.S).count { schedule[it][d] == k }
                    if (have >= need) continue
                    movers = (0 until problem.S).count { s ->
                        !problem.wishLocked(s, d) && problem.canDo(s, k) && schedule[s][d] != k
                    }
                }
                if (movers <= 0) continue
                list += Target(d, k, need - have, movers)
            }
        }
        list.sortWith(compareByDescending<Target> { it.deficit }.thenByDescending { it.movers })
        targets = list
        android.util.Log.i(
            "MAGI_FIX",
            "CoverageFocusQueue size=${list.size} bitOps=${bits != null} top=" +
                list.take(5).joinToString { "d${it.day}/k${it.shift}(-${it.deficit},m${it.movers})" },
        )
    }
}
