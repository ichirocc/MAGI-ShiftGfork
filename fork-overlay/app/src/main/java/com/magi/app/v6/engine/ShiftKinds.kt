package com.magi.app.v6.engine
import com.magi.app.v6.canDo
import com.magi.app.v6.allowedShiftsForStaff

import com.magi.app.v6.Problem

/**
 * シフト種の扱い（全体方針）。
 *
 * - **休は通常のシフト種の一つ**（index は慣例で 0 だが、OFF 特殊値ではない）
 * - 割当の可否は常に [Problem.canDo] / [Problem.allowedShiftsForStaff]
 * - 「出勤日」「連勤」などの**意味上のカウント**で休を区別するのは制約定義であり、
 *   休を禁止したり、休だけ別経路で書くことではない
 */
object ShiftKinds {
    /** 休シフトの慣例 index。値として特殊扱いしない。 */
    const val REST = 0

    fun isRest(shift: Int): Boolean = shift == REST

    /** スタッフが担当可能な全シフト（休含む） */
    fun allowed(problem: Problem, staff: Int): List<Int> =
        problem.allowedShiftsForStaff(staff).toList()

    /** cur 以外の担当可能シフト（休含む） */
    fun otherThan(problem: Problem, staff: Int, cur: Int): List<Int> =
        allowed(problem, staff).filter { it != cur }

    /**
     * 日次必要人数を満たすための候補。
     * 需要が「出勤」人数のときは非休を優先するが、休も正規候補として残す。
     */
    fun preferOnDuty(problem: Problem, staff: Int): List<Int> {
        val all = allowed(problem, staff)
        if (all.isEmpty()) return emptyList()
        val onDuty = all.filter { !isRest(it) }
        return if (onDuty.isNotEmpty()) onDuty else all
    }

    /** 休を割当可能なら休、否则は otherThan から1つ（呼び出し側で乱択） */
    fun restIfAllowed(problem: Problem, staff: Int): Int? =
        if (problem.canDo(staff, REST)) REST else null
}
