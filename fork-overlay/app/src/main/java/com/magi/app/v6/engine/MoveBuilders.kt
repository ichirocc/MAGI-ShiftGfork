package com.magi.app.v6.engine
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo
import com.magi.app.v6.allowedShiftsForStaff

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * 正規化 Move 生成。lock セルは生成時に除外（適用時スキップ禁止）。
 */

fun buildSingleMove(
    board: BoardView,
    problem: Problem,
    staff: Int,
    day: Int,
    shift: Int,
    source: String = "single",
): Move? {
    if (staff !in 0 until problem.S || day !in 0 until problem.T) return null
    if (problem.wishLocked(staff, day)) return null
    if (!problem.canDo(staff, shift)) return null
    if (board.current[staff][day] == shift) return null
    return Move(board.version, intArrayOf(staff, day, shift), "single", source)
}

fun buildSwapDaysMove(
    board: BoardView,
    problem: Problem,
    staff: Int,
    day1: Int,
    day2: Int,
    source: String = "swap2",
): Move? {
    if (day1 == day2) return null
    if (day1 !in 0 until problem.T || day2 !in 0 until problem.T) return null
    if (problem.wishLocked(staff, day1) || problem.wishLocked(staff, day2)) return null
    val a = board.current[staff][day1]
    val b = board.current[staff][day2]
    if (a == b) return null
    if (!problem.canDo(staff, b) || !problem.canDo(staff, a)) return null
    val d1 = minOf(day1, day2)
    val d2 = maxOf(day1, day2)
    val sh1 = if (d1 == day1) b else a
    val sh2 = if (d2 == day2) a else b
    return Move(board.version, intArrayOf(staff, d1, sh1, staff, d2, sh2), "swap2", source)
}

/**
 * WindowFill（契約）:
 * - 窓 [start, start+len) に wish lock が1つでもあれば **全体を却下**（部分化禁止）
 * - 既に目標 shift のセルは writes に載せない（no-op 正規化。適用時スキップではない）
 * - 可動セルが0なら null
 */
fun buildWindowFillMove(
    board: BoardView,
    problem: Problem,
    staff: Int,
    start: Int,
    len: Int,
    shift: Int,
    source: String = "window_fill",
): Move? {
    if (len <= 0 || start !in 0 until problem.T) return null
    if (!problem.canDo(staff, shift)) return null
    val end = minOf(problem.T, start + len)
    for (d in start until end) {
        if (problem.wishLocked(staff, d)) return null
    }
    val buf = IntArray(len * 3)
    var n = 0
    for (d in start until end) {
        if (board.current[staff][d] == shift) continue
        buf[n++] = staff
        buf[n++] = d
        buf[n++] = shift
    }
    if (n == 0) return null
    return Move(board.version, buf.copyOf(n), "window_fill", source)
}

fun buildDayLnsMove(
    board: BoardView,
    problem: Problem,
    day: Int,
    assignments: IntArray,
    source: String = "day_lns",
): Move? {
    if (day !in 0 until problem.T) return null
    if (assignments.isEmpty() || assignments.size % 2 != 0) return null
    val cells = ArrayList<Triple<Int, Int, Int>>()
    val seen = HashSet<Int>()
    var i = 0
    while (i < assignments.size) {
        val s = assignments[i]
        val sh = assignments[i + 1]
        i += 2
        if (s !in 0 until problem.S || sh !in 0 until problem.K) return null
        if (!seen.add(s)) return null
        if (problem.wishLocked(s, day)) return null  // 部分化禁止: 指定職員が lock なら手全体を却下
        if (!problem.canDo(s, sh)) return null
        if (board.current[s][day] == sh) continue
        cells.add(Triple(s, day, sh))
    }
    if (cells.isEmpty()) return null
    cells.sortBy { it.first }
    val out = IntArray(cells.size * 3)
    for (idx in cells.indices) {
        out[idx * 3] = cells[idx].first
        out[idx * 3 + 1] = cells[idx].second
        out[idx * 3 + 2] = cells[idx].third
    }
    return Move(board.version, out, "day_lns", source)
}

/** strongPerturbFlat 置換。1手ずつ tryTransition すること */
fun buildPerturbMoves(
    board: BoardView,
    problem: Problem,
    rng: java.util.Random,
    maxCells: Int,
    source: String = "perturb",
): List<Move> {
    val out = ArrayList<Move>(maxCells)
    var tries = 0
    val limit = maxOf(maxCells * 8, 16)
    while (out.size < maxCells && tries++ < limit) {
        val s = rng.nextInt(problem.S)
        val d = rng.nextInt(problem.T)
        if (problem.wishLocked(s, d)) continue
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) continue
        val sh = allowed[rng.nextInt(allowed.size)]
        val m = buildSingleMove(board, problem, s, d, sh, source) ?: continue
        out.add(m)
    }
    return out
}
