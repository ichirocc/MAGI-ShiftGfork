package com.magi.app.v6.engine
import com.magi.app.v6.wishLocked

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * S≤30, T≤31 前提のビット盤面。
 * rowMask[staff][shift] の bit d = その日にそのシフト
 * dayMask[day][shift] の bit s = その職員がそのシフト
 * wishLock[staff] の bit d = 実行可能希望ロック
 */
class BitMasks(
    val S: Int,
    val T: Int,
    val K: Int,
) {
    init {
        require(S in 1..64 && T in 1..64) { "BitMasks requires S,T in 1..64 (business max 30×31)" }
    }

    val rowMask: Array<LongArray> = Array(S) { LongArray(K) }
    val dayMask: Array<LongArray> = Array(T) { LongArray(K) }
    val wishLock: LongArray = LongArray(S)

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

    fun isWishLocked(staff: Int, day: Int): Boolean =
        staff in 0 until S && day in 0 until T && (wishLock[staff] and (1L shl day)) != 0L

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

    fun movableDays(staff: Int): Long {
        val all = if (T >= 64) -1L else (1L shl T) - 1L
        return all and wishLock[staff].inv()
    }
}
