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
        val list = ArrayList<Target>()
        for (d in 0 until problem.T) {
            val row = sd.getOrNull(d) ?: continue
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                if (need <= 0) continue
                var have = 0
                for (s in 0 until problem.S) {
                    if (schedule[s][d] == k) have++
                }
                if (have >= need) continue
                val deficit = need - have
                val movers = (0 until problem.S).count { s ->
                    !problem.wishLocked(s, d) && problem.canDo(s, k) && schedule[s][d] != k
                }
                if (movers <= 0) continue // 塞がった不足はキューに載せない
                list += Target(d, k, deficit, movers)
            }
        }
        list.sortWith(compareByDescending<Target> { it.deficit }.thenByDescending { it.movers })
        targets = list
        android.util.Log.i(
            "MAGI_FIX",
            "CoverageFocusQueue size=${list.size} top=" +
                list.take(5).joinToString { "d${it.day}/k${it.shift}(-${it.deficit},m${it.movers})" },
        )
    }
}
