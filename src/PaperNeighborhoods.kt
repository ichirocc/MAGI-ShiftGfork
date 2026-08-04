package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import java.util.ArrayDeque
import java.util.Random

/**
 * 最適化論文に基づく構造近傍ライブラリ。
 *
 * 参照枠組み（nurse rostering / personnel scheduling / metaheuristics）:
 * - Burke et al. / Vanden Berghe: vertical/horizontal swap, block, pattern
 * - Glover: ejection chains
 * - Hansen & Mladenović: VNS / VND（近傍を層状に拡大）
 * - Ropke & Pisinger: ALNS destroy/repair
 * - Schrimpf et al.: ruin-and-recreate
 * - Graph coloring / timetabling: Kempe chain 風の二部交換
 *
 * すべて wish lock / canDo を生成時に満たし、正規化 Move を返す。
 */
object PaperNeighborhoods {

    // -------------------------------------------------------------------------
    // 1. Vertical swap (同一日・2スタッフ) — roster 列方向
    // -------------------------------------------------------------------------
    fun verticalSwap(
        board: BoardView,
        problem: Problem,
        day: Int,
        s1: Int,
        s2: Int,
        source: String = "paper.vswap",
    ): Move? {
        if (s1 == s2 || day !in 0 until problem.T) return null
        if (problem.wishLocked(s1, day) || problem.wishLocked(s2, day)) return null
        val a = board.current[s1][day]
        val b = board.current[s2][day]
        if (a == b) return null
        if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) return null
        return Move(board.version, intArrayOf(s1, day, b, s2, day, a), "vswap", source)
    }

    // -------------------------------------------------------------------------
    // 2. Horizontal swap (同一スタッフ・2日) — roster 行方向
    // -------------------------------------------------------------------------
    fun horizontalSwap(
        board: BoardView,
        problem: Problem,
        staff: Int,
        d1: Int,
        d2: Int,
        source: String = "paper.hswap",
    ): Move? {
        if (d1 == d2 || staff !in 0 until problem.S) return null
        if (problem.wishLocked(staff, d1) || problem.wishLocked(staff, d2)) return null
        val a = board.current[staff][d1]
        val b = board.current[staff][d2]
        if (a == b) return null
        if (!problem.canDo(staff, b) || !problem.canDo(staff, a)) return null
        return Move(board.version, intArrayOf(staff, d1, b, staff, d2, a), "hswap", source)
    }

    // -------------------------------------------------------------------------
    // 3. Ejection chain (Glover) — 同一日に沿って押し出し閉路/開路
    // -------------------------------------------------------------------------
    /**
     * @param path スタッフ列。開路なら末尾が newTail を受け取り先頭は path[1] の旧を受け取る、等は
     *             StructuralMoves.staffChainOnDay に委譲。
     */
    fun ejectionChainDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        path: IntArray,
        cycle: Boolean,
        source: String = "paper.eject",
    ): Move? {
        if (path.size < 2) return null
        return StructuralMoves.staffChainOnDay(
            board, problem, day, path,
            newTail = if (cycle) -1 else 0,
            source = source,
        )
    }

    /**
     * BFS で短い ejection path を構築: 空きセルへ最終的に逃がす。
     */
    fun findEjectionToVacancy(
        board: BoardView,
        problem: Problem,
        day: Int,
        startStaff: Int,
        maxLen: Int = 5,
        rng: Random,
        source: String = "paper.eject.bfs",
    ): Move? {
        if (problem.wishLocked(startStaff, day)) return null
        // 空きスタッフを終点候補に
        val vacant = (0 until problem.S).filter {
            it != startStaff && !problem.wishLocked(it, day) && board.current[it][day] == 0
        }
        if (vacant.isEmpty()) return null
        val target = vacant[rng.nextInt(vacant.size)]
        // 中間をランダムに挟む
        val midPool = (0 until problem.S).filter {
            it != startStaff && it != target && !problem.wishLocked(it, day)
        }.shuffled(rng)
        val pathLen = (2 + rng.nextInt(maxLen - 1)).coerceAtMost(2 + midPool.size)
        val path = IntArray(pathLen)
        path[0] = startStaff
        path[pathLen - 1] = target
        for (i in 1 until pathLen - 1) path[i] = midPool[i - 1]
        return ejectionChainDay(board, problem, day, path, cycle = false, source = source)
    }

    // -------------------------------------------------------------------------
    // 4. Kempe-like chain — 同一日でシフト種 A/B の二部交換
    // -------------------------------------------------------------------------
    /**
     * 日 day 上で shiftA と shiftB を持つスタッフ集合を入れ替え可能なだけ垂直交換。
     * グラフ彩色の Kempe chain をロスター列に適用した形。
     */
    fun kempeDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        shiftA: Int,
        shiftB: Int,
        maxPairs: Int = 4,
        rng: Random,
        source: String = "paper.kempe",
    ): Move? {
        if (shiftA == shiftB) return null
        val setA = (0 until problem.S).filter {
            !problem.wishLocked(it, day) && board.current[it][day] == shiftA && problem.canDo(it, shiftB)
        }.shuffled(rng)
        val setB = (0 until problem.S).filter {
            !problem.wishLocked(it, day) && board.current[it][day] == shiftB && problem.canDo(it, shiftA)
        }.shuffled(rng)
        val n = minOf(maxPairs, setA.size, setB.size)
        if (n <= 0) return null
        val writes = IntArray(n * 6)
        var j = 0
        for (i in 0 until n) {
            val sa = setA[i]
            val sb = setB[i]
            writes[j++] = sa; writes[j++] = day; writes[j++] = shiftB
            writes[j++] = sb; writes[j++] = day; writes[j++] = shiftA
        }
        return Move(board.version, writes, "kempe", source)
    }

    // -------------------------------------------------------------------------
    // 5. Ruin-and-Recreate (部分破壊→再構築候補)
    // -------------------------------------------------------------------------
    /**
     * スタッフ1人の連続 len 日を破壊し、別パターンで埋め直す（再作成）。
     * パターンは allowed からの乱択または preferred 優先。
     */
    fun ruinRecreateStaffWindow(
        board: BoardView,
        problem: Problem,
        staff: Int,
        day0: Int,
        len: Int,
        rng: Random,
        preferPreferred: Boolean = true,
        source: String = "paper.ruin",
    ): Move? {
        if (len <= 0 || day0 < 0 || day0 + len > problem.T) return null
        for (k in 0 until len) if (problem.wishLocked(staff, day0 + k)) return null
        val writes = IntArray(len * 3)
        var j = 0
        for (k in 0 until len) {
            val d = day0 + k
            val allowed = problem.allowedShiftsForStaff(staff)
            if (allowed.isEmpty()) return null
            val pref = problem.preferred[staff][d]
            val sh = when {
                preferPreferred && pref >= 0 && pref in allowed -> pref
                else -> allowed[rng.nextInt(allowed.size)]
            }
            writes[j++] = staff; writes[j++] = d; writes[j++] = sh
        }
        return Move(board.version, writes, "ruin", source)
    }

    /**
     * 1日を部分破壊: 最大 k 人の非 lock セルを別シフトへ。
     */
    fun ruinRecreateDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        k: Int,
        rng: Random,
        source: String = "paper.ruin.day",
    ): Move? {
        val cands = (0 until problem.S).filter { !problem.wishLocked(it, day) }.shuffled(rng)
        if (cands.isEmpty()) return null
        val n = minOf(k, cands.size)
        val writes = IntArray(n * 3)
        var j = 0
        for (i in 0 until n) {
            val s = cands[i]
            val allowed = problem.allowedShiftsForStaff(s)
            if (allowed.isEmpty()) return null
            val cur = board.current[s][day]
            val alt = allowed.filter { it != cur }.ifEmpty { allowed.toList() }
            writes[j++] = s; writes[j++] = day; writes[j++] = alt[rng.nextInt(alt.size)]
        }
        return Move(board.version, writes, "ruin_day", source)
    }

    // -------------------------------------------------------------------------
    // 6. Or-opt 風 — 行上の部分系列を別位置へ挿入
    // -------------------------------------------------------------------------
    fun orOptStaff(
        board: BoardView,
        problem: Problem,
        staff: Int,
        segStart: Int,
        segLen: Int,
        insertAt: Int,
        source: String = "paper.oropt",
    ): Move? {
        if (segLen <= 0) return null
        if (segStart < 0 || segStart + segLen > problem.T) return null
        if (insertAt < 0 || insertAt > problem.T - segLen) return null
        if (insertAt in segStart until (segStart + segLen)) return null
        for (d in segStart until segStart + segLen) if (problem.wishLocked(staff, d)) return null
        for (d in insertAt until insertAt + segLen) if (problem.wishLocked(staff, d)) return null
        // 系列を取り出し、行を再配置
        val seg = IntArray(segLen) { board.current[staff][segStart + it] }
        val row = IntArray(problem.T) { board.current[staff][it] }
        // remove segment (fill with -1 marker)
        for (i in segStart until segStart + segLen) row[i] = -1
        val compact = row.filter { it >= 0 }.toMutableList()
        val ins = insertAt.coerceIn(0, compact.size)
        compact.addAll(ins, seg.toList())
        while (compact.size < problem.T) compact.add(0)
        if (compact.size > problem.T) return null
        val writes = ArrayList<Int>()
        for (d in 0 until problem.T) {
            val sh = compact[d]
            if (!problem.canDo(staff, sh)) return null
            if (sh != board.current[staff][d]) {
                writes.add(staff); writes.add(d); writes.add(sh)
            }
        }
        if (writes.isEmpty()) return null
        return Move(board.version, writes.toIntArray(), "oropt", source)
    }

    // -------------------------------------------------------------------------
    // 7. Pattern move — 週末ブロック (金土日) の一括交換
    // -------------------------------------------------------------------------
    fun weekendBlockSwap(
        board: BoardView,
        problem: Problem,
        s1: Int,
        s2: Int,
        weekIndex: Int,
        source: String = "paper.weekend",
    ): Move? {
        // 週の金=4, 土=5, 日=6（day % 7）
        val base = weekIndex * 7
        val days = intArrayOf(base + 4, base + 5, base + 6).filter { it in 0 until problem.T }
        if (days.size < 2) return null
        val day0 = days.minOrNull()!!
                // 金〜日が連続している場合のみ block
        if (days.size == 3 && days[2] - days[0] == 2) {
            return BlockMoves.blockColumnSwap(board, problem, s1, s2, day0, 3, source)
        }
        // 非連続は個別 vertical
        val writes = ArrayList<Int>()
        for (d in days) {
            if (problem.wishLocked(s1, d) || problem.wishLocked(s2, d)) return null
            val a = board.current[s1][d]
            val b = board.current[s2][d]
            if (!problem.canDo(s1, b) || !problem.canDo(s2, a)) return null
            writes.add(s1); writes.add(d); writes.add(b)
            writes.add(s2); writes.add(d); writes.add(a)
        }
        return Move(board.version, writes.toIntArray(), "weekend", source)
    }
}

/**
 * VNS 層: k=1..kMax で近傍を拡大（Hansen & Mladenović）。
 * STRICT で改善があれば k=1 に戻す。
 */
class VnsPolish(
    private val problem: Problem,
    private val rng: Random,
    private val kMax: Int = 5,
) {
    private val blocks = BlockMoveFactory(problem, rng)
    private val struct = StructuralMoveFactory(problem, rng)

    fun run(session: SearchSessionFull, deadlineMs: Long): Int {
        var accepts = 0
        var k = 1
        while (k <= kMax && System.currentTimeMillis() < deadlineMs) {
            val move = shake(session, k)
            if (move == null || move.baseVersion != session.version) {
                k++
                continue
            }
            val r = session.tryTransition(move, TransitionMode.STRICT)
            if (r is TransitionResult.AcceptedBest || r is TransitionResult.AcceptedCurrent) {
                accepts++
                k = 1 // VNS: 改善で最小近傍へ
            } else {
                k++
            }
        }
        return accepts
    }

    /** k に応じた破壊半径の近傍 */
    private fun shake(session: SearchSessionFull, k: Int): Move? {
        val board = session
        return when (k) {
            1 -> {
                // 最小: vertical / horizontal
                if (rng.nextBoolean()) {
                    val d = rng.nextInt(problem.T)
                    val s1 = rng.nextInt(problem.S)
                    var s2 = rng.nextInt(problem.S)
                    if (s1 == s2) s2 = (s2 + 1) % problem.S
                    PaperNeighborhoods.verticalSwap(board, problem, d, s1, s2)
                } else {
                    val s = rng.nextInt(problem.S)
                    val d1 = rng.nextInt(problem.T)
                    var d2 = rng.nextInt(problem.T)
                    if (d1 == d2) d2 = (d2 + 1) % problem.T
                    PaperNeighborhoods.horizontalSwap(board, problem, s, d1, d2)
                }
            }
            2 -> struct.randomRect(board)
            3 -> {
                val d = rng.nextInt(problem.T)
                PaperNeighborhoods.findEjectionToVacancy(board, problem, d, rng.nextInt(problem.S), 4, rng)
                    ?: struct.tripleCycle(board, d)
            }
            4 -> {
                val d = rng.nextInt(problem.T)
                val a = 1 + rng.nextInt(maxOf(1, problem.K - 1))
                var b = 1 + rng.nextInt(maxOf(1, problem.K - 1))
                if (a == b) b = if (a == 1) minOf(2, problem.K - 1) else 1
                PaperNeighborhoods.kempeDay(board, problem, d, a, b, maxPairs = 3, rng = rng)
                    ?: blocks.guidedBlockSwap(board, intArrayOf(d))
            }
            else -> {
                // ruin-and-recreate + block
                when (rng.nextInt(3)) {
                    0 -> PaperNeighborhoods.ruinRecreateDay(board, problem, rng.nextInt(problem.T), 2 + k, rng)
                    1 -> {
                        val s = rng.nextInt(problem.S)
                        val len = minOf(2 + k, problem.T)
                        val d0 = rng.nextInt(problem.T - len + 1)
                        PaperNeighborhoods.ruinRecreateStaffWindow(board, problem, s, d0, len, rng)
                    }
                    else -> blocks.guidedBlockTransfer(board) ?: struct.anyStructural(board)
                }
            }
        }
    }
}

/**
 * ALNS 風: destroy 度合いを適応、repair は既存研磨ジェネレータ。
 */
class AlnsPolish(
    private val problem: Problem,
    private val rng: Random,
) {
    private val destroyWeights = doubleArrayOf(1.0, 1.0, 1.0, 1.0) // day, staff, kempe, block
    private val repairGain = DoubleArray(4)

    fun run(session: SearchSessionFull, deadlineMs: Long): Int {
        var accepts = 0
        var iter = 0
        while (System.currentTimeMillis() < deadlineMs && iter++ < 200) {
            val di = roulette()
            val move = destroy(session, di) ?: continue
            if (move.baseVersion != session.version) continue
            val before = session.currentReport.weightedScore
            val r = session.tryTransition(move, TransitionMode.STRICT)
            if (r is TransitionResult.AcceptedBest || r is TransitionResult.AcceptedCurrent) {
                accepts++
                val gain = (before - session.currentReport.weightedScore).toDouble().coerceAtLeast(0.0)
                repairGain[di] += 1.0 + gain
                destroyWeights[di] += 0.2
            } else {
                destroyWeights[di] = (destroyWeights[di] * 0.95).coerceAtLeast(0.15)
            }
        }
        return accepts
    }

    private fun roulette(): Int {
        val sum = destroyWeights.sum()
        var r = rng.nextDouble() * sum
        for (i in destroyWeights.indices) {
            r -= destroyWeights[i]
            if (r <= 0) return i
        }
        return destroyWeights.lastIndex
    }

    private fun destroy(session: SearchSessionFull, di: Int): Move? {
        val board = session
        return when (di) {
            0 -> PaperNeighborhoods.ruinRecreateDay(board, problem, rng.nextInt(problem.T), 3, rng)
            1 -> {
                val s = rng.nextInt(problem.S)
                val len = 2 + rng.nextInt(3)
                if (len > problem.T) return null
                val d0 = rng.nextInt(problem.T - len + 1)
                PaperNeighborhoods.ruinRecreateStaffWindow(board, problem, s, d0, len, rng)
            }
            2 -> {
                val d = rng.nextInt(problem.T)
                val a = 1 + rng.nextInt(maxOf(1, problem.K - 1))
                val b = (a % (problem.K - 1)) + 1
                PaperNeighborhoods.kempeDay(board, problem, d, a, b, 3, rng)
            }
            else -> BlockMoveFactory(problem, rng).guidedBlockTransfer(board)
                ?: StructuralMoveFactory(problem, rng).anyStructural(board)
        }
    }
}
