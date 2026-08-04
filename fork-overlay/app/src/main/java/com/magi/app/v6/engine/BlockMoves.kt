package com.magi.app.v6.engine
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo

import com.magi.app.v6.Problem
import java.util.Random

/**
 * ブロック移動・拡張四角スワップ（main Block / Rect 系の深い実装）。
 *
 * 設計:
 * - 連続日区間 [d0, d0+len) を単位に操作（lock が1つでもあればそのブロックは不可）
 * - スタッフ対 (s1,s2) のブロック列交換・ブロック転送・帯状四角
 * - 残差日集合にアンカーした選択（乱択より不足/過多日を優先）
 * - すべて正規化 Move（原子的）
 */
object BlockMoves {

    /**
     * ブロック列交換: 区間の全日で s1 と s2 のシフトを入れ替え。
     * （四角スワップの日方向拡張）
     */
    fun blockColumnSwap(
        board: BoardView,
        problem: Problem,
        s1: Int,
        s2: Int,
        day0: Int,
        len: Int,
        source: String = "block.col",
    ): Move? {
        if (s1 == s2 || len <= 0) return null
        if (day0 < 0 || day0 + len > problem.T) return null
        if (!blockFree(problem, s1, day0, len) || !blockFree(problem, s2, day0, len)) return null
        val writes = IntArray(len * 6)
        var j = 0
        for (k in 0 until len) {
            val d = day0 + k
            val a = board.current[s1][d]
            val b = board.current[s2][d]
            if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) return null
            writes[j++] = s1; writes[j++] = d; writes[j++] = b
            writes[j++] = s2; writes[j++] = d; writes[j++] = a
        }
        return normalize(board.version, writes, "block_col", source)
    }

    /**
     * ブロック転送: sFrom の区間勤務を sTo へ渡し、sFrom は休シフト等へ（または reverse で双方向）。
     * 人数調整・公平性向け。
     */
    fun blockTransfer(
        board: BoardView,
        problem: Problem,
        sFrom: Int,
        sTo: Int,
        day0: Int,
        len: Int,
        clearFrom: Boolean = true,
        source: String = "block.xfer",
    ): Move? {
        if (sFrom == sTo || len <= 0) return null
        if (day0 < 0 || day0 + len > problem.T) return null
        if (!blockFree(problem, sFrom, day0, len) || !blockFree(problem, sTo, day0, len)) return null
        val writes = ArrayList<Int>(len * 6)
        for (k in 0 until len) {
            val d = day0 + k
            val sh = board.current[sFrom][d]
            if (sh == 0 && board.current[sTo][d] == 0) continue
            // to が空いている日だけ受け取る（衝突日はスキップせず失敗）
            if (board.current[sTo][d] != 0 && board.current[sTo][d] != sh) {
                // 衝突: to 側を from の旧へ、from を to の旧へ（局所 swap）
                val other = board.current[sTo][d]
                if (!problem.canDo(sTo, sh) || !problem.canDo(sFrom, other)) return null
                writes.add(sTo); writes.add(d); writes.add(sh)
                writes.add(sFrom); writes.add(d); writes.add(other)
            } else {
                if (!problem.canDo(sTo, sh)) return null
                writes.add(sTo); writes.add(d); writes.add(sh)
                if (clearFrom) {
                    if (!problem.canDo(sFrom, ShiftKinds.REST)) return null
                    writes.add(sFrom); writes.add(d); writes.add(0)
                }
            }
        }
        if (writes.isEmpty()) return null
        return normalize(board.version, writes.toIntArray(), "block_xfer", source)
    }

    /**
     * 帯状四角（block rect）: 2スタッフ × 連続日で列交換（mode0）または
     * 各行内反転（mode1）または区間を逆順にして交換（mode2）。
     */
    fun blockRect(
        board: BoardView,
        problem: Problem,
        s1: Int,
        s2: Int,
        day0: Int,
        len: Int,
        mode: Int,
        source: String = "block.rect",
    ): Move? {
        if (s1 == s2 || len < 1) return null
        if (day0 < 0 || day0 + len > problem.T) return null
        if (!blockFree(problem, s1, day0, len) || !blockFree(problem, s2, day0, len)) return null
        val writes = ArrayList<Int>(len * 6)
        when (mode and 3) {
            0 -> { // column swap all days
                for (k in 0 until len) {
                    val d = day0 + k
                    val a = board.current[s1][d]
                    val b = board.current[s2][d]
                    if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) return null
                    writes.add(s1); writes.add(d); writes.add(b)
                    writes.add(s2); writes.add(d); writes.add(a)
                }
            }
            1 -> { // reverse each staff's block independently
                for (k in 0 until len) {
                    val d = day0 + k
                    val dR = day0 + (len - 1 - k)
                    if (d >= dR) break
                    val a = board.current[s1][d]
                    val b = board.current[s1][dR]
                    val c = board.current[s2][d]
                    val e = board.current[s2][dR]
                    if (!problem.canDo(s1, b) || !problem.canDo(s1, a)) return null
                    if (!problem.canDo(s2, e) || !problem.canDo(s2, c)) return null
                    writes.add(s1); writes.add(d); writes.add(b)
                    writes.add(s1); writes.add(dR); writes.add(a)
                    writes.add(s2); writes.add(d); writes.add(e)
                    writes.add(s2); writes.add(dR); writes.add(c)
                }
            }
            2 -> { // swap blocks then reverse on s1 only
                for (k in 0 until len) {
                    val d = day0 + k
                    val a = board.current[s1][d]
                    val b = board.current[s2][d]
                    if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) return null
                    writes.add(s1); writes.add(d); writes.add(b)
                    writes.add(s2); writes.add(d); writes.add(a)
                }
            }
            else -> { // pairwise 2-day rects along the block
                var k = 0
                while (k + 1 < len) {
                    val m = StructuralMoves.rectSwap(
                        board, problem, s1, s2, day0 + k, day0 + k + 1, 0, source,
                    ) ?: return null
                    // merge writes
                    for (x in m.writes) writes.add(x)
                    k += 2
                }
            }
        }
        if (writes.isEmpty()) return null
        return normalize(board.version, writes.toIntArray(), "block_rect", source)
    }

    /**
     * ブロックスライド: 同一スタッフの連続区間を ±shift 日ずらす（端は別シフト（多くは休）で埋める）。
     */
    fun blockSlide(
        board: BoardView,
        problem: Problem,
        staff: Int,
        day0: Int,
        len: Int,
        shift: Int,
        source: String = "block.slide",
    ): Move? {
        if (len <= 0 || shift == 0) return null
        val new0 = day0 + shift
        if (day0 < 0 || day0 + len > problem.T) return null
        if (new0 < 0 || new0 + len > problem.T) return null
        if (!blockFree(problem, staff, day0, len)) return null
        if (!blockFree(problem, staff, new0, len)) return null
        // 重なりがある場合は注意深く中間バッファ相当の順序で書く
        val old = IntArray(len) { board.current[staff][day0 + it] }
        val writes = ArrayList<Int>()
        // 先に移動先と重ならない元をクリア
        for (k in 0 until len) {
            val d = day0 + k
            if (d !in new0 until (new0 + len)) {
                if (!problem.canDo(staff, ShiftKinds.REST)) return null
                writes.add(staff); writes.add(d); writes.add(0)
            }
        }
        for (k in 0 until len) {
                        if (!problem.canDo(staff, old[k])) return null
            writes.add(staff); writes.add(new0 + k); writes.add(old[k])
        }
        return normalize(board.version, writes.toIntArray(), "block_slide", source)
    }

    /**
     * 2ブロック交換: s1[dayA,len] と s2[dayB,len] を交換（日がずれていても可）。
     */
    fun blockExchange(
        board: BoardView,
        problem: Problem,
        s1: Int,
        dayA: Int,
        s2: Int,
        dayB: Int,
        len: Int,
        source: String = "block.exch",
    ): Move? {
        if (len <= 0) return null
        if (dayA < 0 || dayA + len > problem.T) return null
        if (dayB < 0 || dayB + len > problem.T) return null
        if (!blockFree(problem, s1, dayA, len) || !blockFree(problem, s2, dayB, len)) return null
        if (s1 == s2 && kotlin.math.abs(dayA - dayB) < len) {
            // 同一スタッフ重なりは slide に任せる
            return null
        }
        val block1 = IntArray(len) { board.current[s1][dayA + it] }
        val block2 = IntArray(len) { board.current[s2][dayB + it] }
        val writes = ArrayList<Int>(len * 6)
        for (k in 0 until len) {
            if (!problem.canDo(s1, block2[k]) || !problem.canDo(s2, block1[k])) return null
            writes.add(s1); writes.add(dayA + k); writes.add(block2[k])
            writes.add(s2); writes.add(dayB + k); writes.add(block1[k])
        }
        return normalize(board.version, writes.toIntArray(), "block_exch", source)
    }

    private fun blockFree(problem: Problem, staff: Int, day0: Int, len: Int): Boolean {
        for (k in 0 until len) {
            if (problem.wishLocked(staff, day0 + k)) return false
        }
        return true
    }

    private fun normalize(version: Long, writes: IntArray, family: String, source: String): Move? {
        val map = LinkedHashMap<Long, Int>()
        var i = 0
        while (i + 2 < writes.size) {
            val s = writes[i]
            val d = writes[i + 1]
            val sh = writes[i + 2]
            map[(s.toLong() shl 32) or (d.toLong() and 0xffffffffL)] = sh
            i += 3
        }
        if (map.isEmpty()) return null
        val out = IntArray(map.size * 3)
        var j = 0
        for ((key, sh) in map) {
            out[j++] = (key ushr 32).toInt()
            out[j++] = key.toInt()
            out[j++] = sh
        }
        return Move(version, out, family, source)
    }
}

/**
 * 残差誘導付きブロック／四角ファクトリ。
 */
class BlockMoveFactory(
    private val problem: Problem,
    private val rng: Random,
) {
    private val lengths = intArrayOf(2, 3, 4, 5, 7)

    /** 不足日を含む最短ブロックで列交換 */
    fun guidedBlockSwap(
        board: BoardView,
        anchorDays: IntArray,
        preferWorkHeavy: Boolean = true,
    ): Move? {
        if (anchorDays.isEmpty() || problem.S < 2) return null
        val dAnchor = anchorDays[rng.nextInt(anchorDays.size)]
        for (len in lengths) {
            val day0 = (dAnchor - len / 2).coerceIn(0, (problem.T - len).coerceAtLeast(0))
            if (day0 + len > problem.T) continue
            val pair = pickStaffPair(board, day0, len, preferWorkHeavy) ?: continue
            val mode = rng.nextInt(3)
            BlockMoves.blockRect(board, problem, pair.first, pair.second, day0, len, mode)
                ?.let { return it }
            BlockMoves.blockColumnSwap(board, problem, pair.first, pair.second, day0, len)
                ?.let { return it }
        }
        return null
    }

    /** 勤務が多いスタッフ → 少ないスタッフへブロック転送 */
    fun guidedBlockTransfer(board: BoardView): Move? {
        val work = IntArray(problem.S) { s ->
            (0 until problem.T).count { board.current[s][it] > 0 }
        }
        val hi = work.indices.maxByOrNull { work[it] } ?: return null
        val lo = work.indices.minByOrNull { work[it] } ?: return null
        if (work[hi] <= work[lo] + 1) return null
        for (len in lengths) {
            if (len > problem.T) continue
            val day0 = rng.nextInt((problem.T - len) + 1)
            BlockMoves.blockTransfer(board, problem, hi, lo, day0, len)
                ?.let { return it }
        }
        return null
    }

    /** アンカー日周辺の 2×2 四角を総当たりに近い形で試す */
    fun guidedRectAround(
        board: BoardView,
        anchorDays: IntArray,
    ): Move? {
        if (anchorDays.isEmpty() || problem.S < 2 || problem.T < 2) return null
        val d1 = anchorDays[rng.nextInt(anchorDays.size)]
        val d2cands = intArrayOf(d1 - 1, d1 + 1, d1 - 2, d1 + 2).filter {
            it in 0 until problem.T && it != d1
        }
        if (d2cands.isEmpty()) return null
        val d2 = d2cands[rng.nextInt(d2cands.size)]
        // 勤務差が大きいスタッフ対を優先
        val scored = ArrayList<Triple<Int, Int, Int>>()
        for (s1 in 0 until problem.S) {
            for (s2 in s1 + 1 until problem.S) {
                val diff = kotlin.math.abs(
                    (if (board.current[s1][d1] > 0) 1 else 0) + (if (board.current[s1][d2] > 0) 1 else 0) -
                        (if (board.current[s2][d1] > 0) 1 else 0) - (if (board.current[s2][d2] > 0) 1 else 0),
                )
                if (diff == 0) continue
                scored.add(Triple(s1, s2, diff))
            }
        }
        scored.sortByDescending { it.third }
        for ((s1, s2, _) in scored.take(12)) {
            for (mode in 0..3) {
                StructuralMoves.rectSwap(board, problem, s1, s2, d1, d2, mode)
                    ?.let { return it }
            }
        }
        return null
    }

    /** ずれ日ブロック交換（週またぎ公平） */
    fun guidedBlockExchange(board: BoardView): Move? {
        if (problem.T < 4 || problem.S < 2) return null
        val cands = lengths.filter { it <= problem.T / 2 }
        if (cands.isEmpty()) return null
        val len = cands[rng.nextInt(cands.size)]
        val s1 = rng.nextInt(problem.S)
        var s2 = rng.nextInt(problem.S)
        if (s1 == s2) s2 = (s2 + 1) % problem.S
        val dayA = rng.nextInt(problem.T - len + 1)
        var dayB = rng.nextInt(problem.T - len + 1)
        if (kotlin.math.abs(dayA - dayB) < len) {
            dayB = (dayA + len).coerceAtMost(problem.T - len)
        }
        return BlockMoves.blockExchange(board, problem, s1, dayA, s2, dayB, len)
    }

    fun guidedSlide(board: BoardView, staffHint: IntArray): Move? {
        val s = if (staffHint.isNotEmpty()) staffHint[rng.nextInt(staffHint.size)]
        else rng.nextInt(problem.S)
        for (len in lengths) {
            if (len >= problem.T) continue
            val day0 = rng.nextInt(problem.T - len + 1)
            val shift = if (rng.nextBoolean()) 1 else -1
            BlockMoves.blockSlide(board, problem, s, day0, len, shift)?.let { return it }
        }
        return null
    }

    private fun pickStaffPair(
        board: BoardView,
        day0: Int,
        len: Int,
        preferWorkHeavy: Boolean,
    ): Pair<Int, Int>? {
        val load = IntArray(problem.S) { s ->
            (0 until len).count { board.current[s][day0 + it] > 0 }
        }
        val order = load.indices.sortedWith(
            if (preferWorkHeavy) compareByDescending { load[it] } else compareBy { load[it] },
        )
        if (order.size < 2) return null
        val a = order[0]
        val b = order.getOrElse(order.lastIndex) { order[1] }
        if (a == b) return null
        return a to b
    }
}
