package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * 職員×シフト回数の差分カウンタ。
 * ExactCountPolicy.FORBID_WORSEN は「満たしていたピンの悪化」のみ拒否する。
 */
class StaffShiftCounts(
    private val S: Int,
    private val K: Int,
) {
    private val cnt = Array(S) { IntArray(K) }

    fun rebuildFrom(schedule: Array<IntArray>, T: Int) {
        for (i in 0 until S) {
            cnt[i].fill(0)
            val row = schedule.getOrNull(i) ?: continue
            for (j in 0 until minOf(T, row.size)) {
                val sh = row[j]
                if (sh in 0 until K) cnt[i][sh]++
            }
        }
    }

    fun get(staff: Int, shift: Int): Int =
        if (staff in 0 until S && shift in 0 until K) cnt[staff][shift] else 0

    fun applyChange(staff: Int, oldShift: Int, newShift: Int) {
        if (oldShift == newShift) return
        if (staff !in 0 until S) return
        if (oldShift in 0 until K) cnt[staff][oldShift]--
        if (newShift in 0 until K) cnt[staff][newShift]++
    }

    fun revertChange(staff: Int, oldShift: Int, newShift: Int) {
        applyChange(staff, newShift, oldShift)
    }

    /**
     * FORBID_WORSEN:
     * 変更前に count==lo（ピン充足）だった (staff,shift) が、
     * 変更後に count!=lo になったときのみ true（悪化）。
     * もともと外れていたピンを触っても「悪化」とはみなさない。
     *
     * @param beforeCounts touchKeys[i] に対応する変更前カウント
     */
    fun regressesSatisfiedExactPins(
        problem: Problem,
        touchKeys: LongArray,
        beforeCounts: IntArray,
        nTouched: Int,
    ): Boolean {
        var t = 0
        while (t < nTouched) {
            val key = touchKeys[t]
            val s = (key shr 32).toInt()
            val sh = (key and 0xffffffffL).toInt()
            val lo = problem.rangeLo[s][sh]
            val hi = problem.rangeHi[s][sh]
            if (lo != Int.MIN_VALUE && lo == hi) {
                val before = beforeCounts[t]
                val after = cnt[s][sh]
                if (before == lo && after != lo) return true
            }
            t++
        }
        return false
    }

}
