package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import java.util.ArrayDeque
import java.util.Random

/**
 * 構造近傍: 玉突き連鎖・四角スワップ・日次サイクル。
 * main の Block/Cycle/Chain 系を抽象化した完成版。
 *
 * すべて正規化済み writes を返し、wish lock / canDo を生成時に満たす。
 */
object StructuralMoves {

    /**
     * 四角スワップ（2 staff × 2 days）。
     *
     *   (s1,d1)=a  (s1,d2)=b
     *   (s2,d1)=c  (s2,d2)=d
     *
     * mode:
     *  0: 列交換 — s1 の2日と s2 の2日を入れ替え → (c,d)/(a,b)
     *  1: 行交換 — 各スタッフ内で日を入れ替え → (b,a)/(d,c)
     *  2: 対角循環 — a→d, d→a, b→c, c→b
     *  3: 周期 (a→c→d→b→a)
     */
    fun rectSwap(
        board: BoardView,
        problem: Problem,
        s1: Int,
        s2: Int,
        d1: Int,
        d2: Int,
        mode: Int,
        source: String = "struct.rect",
    ): Move? {
        if (s1 == s2 || d1 == d2) return null
        if (problem.wishLocked(s1, d1) || problem.wishLocked(s1, d2)) return null
        if (problem.wishLocked(s2, d1) || problem.wishLocked(s2, d2)) return null
        val a = board.current[s1][d1]
        val b = board.current[s1][d2]
        val c = board.current[s2][d1]
        val d = board.current[s2][d2]
        val writes = when (mode and 3) {
            0 -> intArrayOf(s1, d1, c, s1, d2, d, s2, d1, a, s2, d2, b) // column swap
            1 -> intArrayOf(s1, d1, b, s1, d2, a, s2, d1, d, s2, d2, c) // row swap
            2 -> intArrayOf(s1, d1, d, s1, d2, c, s2, d1, b, s2, d2, a) // diagonal
            else -> intArrayOf(s1, d1, c, s2, d1, d, s2, d2, b, s1, d2, a) // cycle
        }
        // canDo check
        var i = 0
        while (i < writes.size) {
            if (!problem.canDo(writes[i], writes[i + 2])) return null
            i += 3
        }
        // drop no-ops / normalize
        return normalizeStructural(board.version, writes, "rect", source)
    }

    /**
     * 同一日の玉突き連鎖（スタッフ間）。
     * path[0] のシフトを path[1] へ、…、最後は newLastShift（または path[0] の旧シフトで閉路）。
     *
     * 開路: path の先頭が空き（0）を受け取り、末尾が newTail になる
     * 閉路: newTail < 0 のとき末尾が先頭の旧シフトを受け取る
     */
    fun staffChainOnDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        path: IntArray,
        newTail: Int = -1,
        source: String = "struct.chain.day",
    ): Move? {
        if (path.size < 2) return null
        for (s in path) if (problem.wishLocked(s, day)) return null
        val old = IntArray(path.size) { board.current[path[it]][day] }
        val writes = IntArray(path.size * 3)
        for (i in path.indices) {
            val s = path[i]
            val sh = when {
                i + 1 < path.size -> old[i + 1]
                newTail >= 0 -> newTail
                else -> old[0] // cycle
            }
            if (!problem.canDo(s, sh)) return null
            writes[i * 3] = s
            writes[i * 3 + 1] = day
            writes[i * 3 + 2] = sh
        }
        return normalizeStructural(board.version, writes, "chain_day", source)
    }

    /**
     * 同一スタッフの日次玉突き（シフトを日沿いにずらす）。
     * days に沿って shifts をローテート。
     */
    fun dayChainOnStaff(
        board: BoardView,
        problem: Problem,
        staff: Int,
        days: IntArray,
        rotate: Int = 1,
        source: String = "struct.chain.staff",
    ): Move? {
        if (days.size < 2) return null
        for (d in days) if (problem.wishLocked(staff, d)) return null
        val old = IntArray(days.size) { board.current[staff][days[it]] }
        val writes = IntArray(days.size * 3)
        val n = days.size
        val r = ((rotate % n) + n) % n
        for (i in days.indices) {
            val sh = old[(i + r) % n]
            if (!problem.canDo(staff, sh)) return null
            writes[i * 3] = staff
            writes[i * 3 + 1] = days[i]
            writes[i * 3 + 2] = sh
        }
        return normalizeStructural(board.version, writes, "chain_staff", source)
    }

    /**
     * 多日・多スタッフの玉突き（BFS で短いパスを探索）。
     * 目標: (targetStaff, targetDay) を wantShift にする。
     * 障害となる現シフト保有者を別日/別に逃がす簡易チェーン。
     */
    fun cascadeToCell(
        board: BoardView,
        problem: Problem,
        targetStaff: Int,
        targetDay: Int,
        wantShift: Int,
        rng: Random,
        maxDepth: Int = 4,
        source: String = "struct.cascade",
    ): Move? {
        if (problem.wishLocked(targetStaff, targetDay)) return null
        if (!problem.canDo(targetStaff, wantShift)) return null
        val cur = board.current[targetStaff][targetDay]
        if (cur == wantShift) return null

        // 深さ1: 直接書き換え
        if (ShiftKinds.isRest(cur) || ShiftKinds.isRest(wantShift)) {
            return buildSingleMove(board, problem, targetStaff, targetDay, wantShift, source)
        }

        // 深さ2: 同日の別スタッフと交換
        val order = IntArray(problem.S) { it }
        for (i in order.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = order[i]; order[i] = order[j]; order[j] = t
        }
        for (s2 in order) {
            if (s2 == targetStaff || problem.wishLocked(s2, targetDay)) continue
            val o = board.current[s2][targetDay]
            if (o != wantShift && !ShiftKinds.isRest(wantShift)) {
                // s2 が want を持っていない場合、target に want を直接
                continue
            }
            if (o == wantShift) {
                // s2 が持っている → target と交換
                if (!problem.canDo(s2, cur)) continue
                return Move(
                    board.version,
                    intArrayOf(targetStaff, targetDay, wantShift, s2, targetDay, cur),
                    "cascade2",
                    source,
                )
            }
        }

        // 深さ3+: 別日へ押し出し（玉突き）
        // target の cur を別日 free へ、targetDay に want を入れる
        for (depth in 2..maxDepth) {
            val pathDays = ArrayList<Int>()
            pathDays.add(targetDay)
            var staff = targetStaff
            var carrying = cur
            var ok = true
            val writes = ArrayList<Int>()
            for (step in 1 until depth) {
                // carrying を置ける空き日を探す
                var placed = false
                val dayOrder = IntArray(problem.T) { it }
                for (i in dayOrder.size - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    val t = dayOrder[i]; dayOrder[i] = dayOrder[j]; dayOrder[j] = t
                }
                for (d in dayOrder) {
                    if (d in pathDays) continue
                    if (problem.wishLocked(staff, d)) continue
                    if (board.current[staff][d] != 0) continue
                    if (!problem.canDo(staff, carrying)) continue
                    writes.add(staff); writes.add(d); writes.add(carrying)
                    pathDays.add(d)
                    carrying = 0
                    placed = true
                    break
                }
                if (!placed) {
                    // 同日の他スタッフへ押し出し
                    var pushed = false
                    for (s2 in order) {
                        if (s2 == staff || problem.wishLocked(s2, targetDay)) continue
                        val o = board.current[s2][targetDay]
                        if (!problem.canDo(s2, carrying)) continue
                        if (!problem.canDo(staff, o)) continue
                        writes.add(s2); writes.add(targetDay); writes.add(carrying)
                        writes.add(staff); writes.add(targetDay); writes.add(o)
                        carrying = o
                        staff = s2
                        pushed = true
                        break
                    }
                    if (!pushed) {
                        ok = false
                        break
                    }
                }
            }
            if (!ok) continue
            // 最後に target を want に
            if (!problem.canDo(targetStaff, wantShift)) continue
            writes.add(targetStaff); writes.add(targetDay); writes.add(wantShift)
            val arr = writes.toIntArray()
            return normalizeStructural(board.version, arr, "cascade", source)
        }
        return null
    }

    /**
     * 閉路サイクル: 同一日でスタッフのシフトを k-cycle。
     */
    fun cycleOnDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        cycle: IntArray,
        source: String = "struct.cycle",
    ): Move? = staffChainOnDay(board, problem, day, cycle, newTail = -1, source = source)

    private fun normalizeStructural(
        version: Long,
        writes: IntArray,
        family: String,
        source: String,
    ): Move? {
        // remove no-ops and dedupe last-write-wins per cell
        val map = LinkedHashMap<Long, Int>()
        var i = 0
        while (i + 2 < writes.size) {
            val s = writes[i]
            val d = writes[i + 1]
            val sh = writes[i + 2]
            map[(s.toLong() shl 32) or (d.toLong() and 0xffffffffL)] = sh
            i += 3
        }
        val out = IntArray(map.size * 3)
        var j = 0
        for ((key, sh) in map) {
            val s = (key ushr 32).toInt()
            val d = key.toInt()
            out[j++] = s
            out[j++] = d
            out[j++] = sh
        }
        if (j == 0) return null
        return Move(version, out.copyOf(j), family, source)
    }
}

/**
 * ConstraintPolishEngine から呼ぶ高レベル生成。
 */
class StructuralMoveFactory(
    private val problem: Problem,
    private val rng: Random,
) {
    fun randomRect(board: BoardView): Move? {
        if (problem.S < 2 || problem.T < 2) return null
        repeat(12) {
            val s1 = rng.nextInt(problem.S)
            var s2 = rng.nextInt(problem.S)
            if (s1 == s2) s2 = (s2 + 1) % problem.S
            val d1 = rng.nextInt(problem.T)
            var d2 = rng.nextInt(problem.T)
            if (d1 == d2) d2 = (d2 + 1) % problem.T
            val m = StructuralMoves.rectSwap(board, problem, s1, s2, d1, d2, rng.nextInt(4))
            if (m != null) return m
        }
        return null
    }

    fun chainFillUnder(board: BoardView, underDays: IntArray): Move? {
        if (underDays.isEmpty()) return null
        val d = underDays[rng.nextInt(underDays.size)]
        // 空きスタッフに、過多スタッフから玉突き
        val empty = (0 until problem.S).filter {
            !problem.wishLocked(it, d) && board.current[it][d] == 0
        }
        val full = (0 until problem.S).filter {
            !problem.wishLocked(it, d) && board.current[it][d] > 0
        }
        if (empty.isEmpty() || full.isEmpty()) return null
        val receiver = empty[rng.nextInt(empty.size)]
        val donor = full[rng.nextInt(full.size)]
        val sh = board.current[donor][d]
        if (!problem.canDo(receiver, sh) || !problem.canDo(donor, ShiftKinds.REST)) return null
        // 2-chain: donor→0, receiver→sh
        return StructuralMoves.staffChainOnDay(
            board, problem, d,
            intArrayOf(donor, receiver),
            newTail = sh,
            source = "struct.chain.fill",
        )
    }

    /** 3人玉突き: AのシフトをBへ、BをCへ、CをAの旧へ（閉路） */
    fun tripleCycle(board: BoardView, day: Int): Move? {
        val unlocked = (0 until problem.S).filter { !problem.wishLocked(it, day) }
        if (unlocked.size < 3) return null
        val a = unlocked[rng.nextInt(unlocked.size)]
        var b = unlocked[rng.nextInt(unlocked.size)]
        var c = unlocked[rng.nextInt(unlocked.size)]
        if (a == b || b == c || a == c) return null
        return StructuralMoves.cycleOnDay(board, problem, day, intArrayOf(a, b, c))
    }

    fun cascadeWant(
        board: BoardView,
        staff: Int,
        day: Int,
        want: Int,
    ): Move? = StructuralMoves.cascadeToCell(board, problem, staff, day, want, rng)

    fun staffDayRotate(board: BoardView, staff: Int, len: Int = 3): Move? {
        if (problem.T < len) return null
        val start = rng.nextInt(problem.T - len + 1)
        val days = IntArray(len) { start + it }
        for (d in days) if (problem.wishLocked(staff, d)) return null
        return StructuralMoves.dayChainOnStaff(board, problem, staff, days, rotate = 1)
    }

    private val blocks = BlockMoveFactory(problem, rng)

    fun guidedBlockSwap(board: BoardView, anchorDays: IntArray): Move? =
        blocks.guidedBlockSwap(board, anchorDays)

    fun guidedBlockTransfer(board: BoardView): Move? =
        blocks.guidedBlockTransfer(board)

    fun guidedRectAround(board: BoardView, anchorDays: IntArray): Move? =
        blocks.guidedRectAround(board, anchorDays)

    fun guidedBlockExchange(board: BoardView): Move? =
        blocks.guidedBlockExchange(board)

    fun guidedSlide(board: BoardView, staffHint: IntArray = intArrayOf()): Move? =
        blocks.guidedSlide(board, staffHint)

    /** 構造手のポートフォリオから1手 */
    fun anyStructural(
        board: BoardView,
        anchorDays: IntArray = intArrayOf(),
        staffHint: IntArray = intArrayOf(),
    ): Move? {
        val picks = listOf(
            { if (anchorDays.isNotEmpty()) guidedRectAround(board, anchorDays) else randomRect(board) },
            { if (anchorDays.isNotEmpty()) guidedBlockSwap(board, anchorDays) else null },
            { guidedBlockTransfer(board) },
            { guidedBlockExchange(board) },
            { guidedSlide(board, staffHint) },
            { randomRect(board) },
        )
        val order = picks.indices.toMutableList()
        order.shuffle(rng)
        for (i in order) {
            picks[i]()?.let { return it }
        }
        return null
    }
}
