package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.canDo
import com.magi.app.v6.wishLocked

/**
 * S≤64, T≤64, K≤64 前提のビット盤面。
 *
 * - rowMask[staff][shift] bit d = その日にそのシフト
 * - dayMask[day][shift] bit s = その職員がそのシフト
 * - wishLock[staff] bit d = 希望固定
 * - canDoMask[staff] bit k = 担当可
 */
class BitMasks(
    val S: Int,
    val T: Int,
    val K: Int,
) {
    init {
        require(S in 1..64 && T in 1..64) { "BitMasks requires S,T in 1..64" }
        require(K in 1..64) { "BitMasks requires K in 1..64 for canDoMask" }
    }

    val rowMask: Array<LongArray> = Array(S) { LongArray(K) }
    val dayMask: Array<LongArray> = Array(T) { LongArray(K) }
    val wishLock: LongArray = LongArray(S)
    /** staff → 担当可シフトのビット集合 */
    val canDoMask: LongArray = LongArray(S)

    private val allDays: Long =
        if (T >= 64) -1L else (1L shl T) - 1L
    private val allStaff: Long =
        if (S >= 64) -1L else (1L shl S) - 1L

    fun clearAssignments() {
        for (i in 0 until S) for (k in 0 until K) rowMask[i][k] = 0L
        for (j in 0 until T) for (k in 0 until K) dayMask[j][k] = 0L
    }

    fun rebuildFrom(schedule: Array<IntArray>) {
        clearAssignments()
        for (i in 0 until S) {
            val row = schedule.getOrNull(i) ?: continue
            for (j in 0 until minOf(T, row.size)) {
                val sh = row[j]
                if (sh in 0 until K) setCell(i, j, sh)
            }
        }
    }

    fun setWishLockFrom(problem: Problem) {
        for (i in 0 until S) {
            var m = 0L
            for (j in 0 until T) {
                if (problem.wishLocked(i, j)) m = m or (1L shl j)
            }
            wishLock[i] = m
        }
    }

    fun setCanDoFrom(problem: Problem) {
        for (i in 0 until S) {
            var m = 0L
            for (k in 0 until K) {
                if (problem.canDo(i, k)) m = m or (1L shl k)
            }
            canDoMask[i] = m
        }
    }

    fun isWishLocked(staff: Int, day: Int): Boolean =
        staff in 0 until S && day in 0 until T && (wishLock[staff] and (1L shl day)) != 0L

    fun canDoBit(staff: Int, shift: Int): Boolean =
        staff in 0 until S && shift in 0 until K && (canDoMask[staff] and (1L shl shift)) != 0L

    fun setCell(staff: Int, day: Int, shift: Int) {
        val bitJ = 1L shl day
        val bitI = 1L shl staff
        rowMask[staff][shift] = rowMask[staff][shift] or bitJ
        dayMask[day][shift] = dayMask[day][shift] or bitI
    }

    fun clearCell(staff: Int, day: Int, shift: Int) {
        val bitJ = 1L shl day
        val bitI = 1L shl staff
        rowMask[staff][shift] = rowMask[staff][shift] and bitJ.inv()
        dayMask[day][shift] = dayMask[day][shift] and bitI.inv()
    }

    fun reassign(staff: Int, day: Int, oldShift: Int, newShift: Int) {
        if (oldShift == newShift) return
        if (oldShift in 0 until K) clearCell(staff, day, oldShift)
        if (newShift in 0 until K) setCell(staff, day, newShift)
    }

    fun countStaffShift(staff: Int, shift: Int): Int =
        java.lang.Long.bitCount(rowMask[staff][shift])

    fun countDayShift(day: Int, shift: Int): Int =
        java.lang.Long.bitCount(dayMask[day][shift])

    /** 窓 [start, start+len) 内の立ビット数 */
    fun windowPop(staff: Int, shift: Int, start: Int, len: Int): Int {
        if (len <= 0 || start !in 0 until T) return 0
        val width = minOf(len, T - start)
        val window = if (width >= 64) -1L else ((1L shl width) - 1L) shl start
        return java.lang.Long.bitCount(rowMask[staff][shift] and window)
    }

    fun movableDays(staff: Int): Long = allDays and wishLock[staff].inv()

    /**
     * 日 day のシフト shift に「担当可・非固定・未配置」の職員ビット。
     * covU 補充候補の集合演算。
     */
    fun openMoversMask(day: Int, shift: Int): Long {
        if (day !in 0 until T || shift !in 0 until K) return 0L
        var can = 0L
        for (s in 0 until S) {
            if ((canDoMask[s] and (1L shl shift)) == 0L) continue
            if ((wishLock[s] and (1L shl day)) != 0L) continue
            can = can or (1L shl s)
        }
        val assigned = dayMask[day][shift]
        return can and assigned.inv()
    }

    fun openMoversCount(day: Int, shift: Int): Int =
        java.lang.Long.bitCount(openMoversMask(day, shift))

    /**
     * 禁止連続 shiftA→shiftB: staff の row で A の翌日が B。
     * 戻り値 = 該当開始日のビット（bit d = day d が A かつ d+1 が B）
     */
    fun forbiddenConsecutiveMask(staff: Int, shiftA: Int, shiftB: Int): Long {
        if (staff !in 0 until S) return 0L
        if (shiftA !in 0 until K || shiftB !in 0 until K) return 0L
        val a = rowMask[staff][shiftA]
        val b = rowMask[staff][shiftB]
        // day d is A and day d+1 is B → a bit d and b bit d+1
        return a and (b ushr 1) and allDays
    }

    fun forbiddenConsecutiveCount(staff: Int, shiftA: Int, shiftB: Int): Int =
        java.lang.Long.bitCount(forbiddenConsecutiveMask(staff, shiftA, shiftB))

    /** 下位から立っているビット index を列挙（最大 S or T 個） */
    fun forEachBit(mask: Long, limit: Int, body: (Int) -> Unit) {
        var m = mask
        var i = 0
        while (m != 0L && i < limit) {
            val b = java.lang.Long.numberOfTrailingZeros(m)
            if (b in 0 until limit) body(b)
            m = m and (m - 1) // 最下位ビットを落とす
            i++
        }
    }

    companion object {
        fun supports(problem: Problem): Boolean =
            problem.S in 1..64 && problem.T in 1..64 && problem.K in 1..64

        fun from(problem: Problem, schedule: Array<IntArray>): BitMasks? {
            if (!supports(problem)) return null
            return BitMasks(problem.S, problem.T, problem.K).also {
                it.rebuildFrom(schedule)
                it.setWishLockFrom(problem)
                it.setCanDoFrom(problem)
            }
        }
    }
}
