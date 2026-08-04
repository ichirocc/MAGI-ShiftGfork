package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.groupDemand
import com.magi.app.v6.staffGroup
import java.util.Random

/**
 * main の C1 / coverage 系から移植した高精度・低コスト近傍。
 * - 同一日の複数セルを1 Move にまとめ、評価回数を削減
 * - 生成時に wish/canDo を満たす
 */
object C1PrecisionMoves {

    /**
     * 日 day の勤務人数が need 未満なら、空きスタッフに勤務を入れる（最大 maxFill 人）。
     */
    fun fillCoverageDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        need: Int,
        rng: Random,
        maxFill: Int = 4,
        source: String = "c1.fill_day",
    ): Move? {
        if (day !in 0 until problem.T) return null
        var work = 0
        for (s in 0 until problem.S) if (board.current[s][day] > 0) work++
        if (work >= need) return null
        val deficit = (need - work).coerceAtMost(maxFill)
        val candidates = ArrayList<Int>()
        for (s in 0 until problem.S) {
            if (problem.wishLocked(s, day)) continue
            if (board.current[s][day] != 0) continue
            candidates.add(s)
        }
        if (candidates.isEmpty()) return null
        candidates.shuffle(rng)
        val writes = ArrayList<Int>(deficit * 3)
        var filled = 0
        for (s in candidates) {
            if (filled >= deficit) break
            val prefer = ShiftKinds.preferOnDuty(problem, s)
            if (prefer.isEmpty()) continue
            val sh = prefer[rng.nextInt(prefer.size)]
            writes.add(s); writes.add(day); writes.add(sh)
            filled++
        }
        if (writes.isEmpty()) return null
        return Move(board.version, writes.toIntArray(), "c1_fill", source)
    }

    /**
     * 過多日: 別シフトへ変更する（休含む通常シフト種）。
     * （シフト種別の偏り・c2/covO 種別過多の緩和向け）
     */
    fun trimCoverageDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        need: Int,
        rng: Random,
        maxTrim: Int = 3,
        source: String = "c1.trim_day",
    ): Move? {
        if (day !in 0 until problem.T) return null
        val workers = ArrayList<Int>()
        for (s in 0 until problem.S) {
            if (problem.wishLocked(s, day)) continue
            if (board.current[s][day] > 0) workers.add(s)
        }
        if (workers.isEmpty()) return null
        // need は「過多かどうか」の目安。勤務者が need 以下でも種別変更は許可
        if (workers.size <= need && need > 0) {
            // 人数過多でなければ、最多シフト種の一部だけ別勤務へ
        }
        workers.shuffle(rng)
        val n = maxTrim.coerceAtMost(workers.size)
        val writes = ArrayList<Int>(n * 3)
        var changed = 0
        for (s in workers) {
            if (changed >= n) break
            val cur = board.current[s][day]
            if (cur <= 0) continue
            val alts = ShiftKinds.otherThan(problem, s, cur)
            if (alts.isEmpty()) continue
            val sh = alts[rng.nextInt(alts.size)]
            writes.add(s); writes.add(day); writes.add(sh)
            changed++
        }
        if (writes.isEmpty()) return null
        return Move(board.version, writes.toIntArray(), "c1_retype", source)
    }

    /**
     * グループ最低人数不足の日を一括修復（main groupViol 系の簡略移植）。
     */
    fun fillGroupDay(
        board: BoardView,
        problem: Problem,
        day: Int,
        rng: Random,
        source: String = "c1.group_day",
    ): Move? {
        if (day !in 0 until problem.T) return null
        val groups = problem.staffGroup()
        val demand = problem.groupDemand()
        if (day !in demand.indices) return null
        val gCount = demand[day].size
        val present = IntArray(gCount)
        for (s in 0 until problem.S) {
            if (board.current[s][day] <= 0) continue
            val g = groups[s]
            if (g in 0 until gCount) present[g]++
        }
        val writes = ArrayList<Int>()
        for (g in 0 until gCount) {
            val need = demand[day][g]
            if (present[g] >= need) continue
            val deficit = need - present[g]
            val pool = (0 until problem.S).filter {
                groups[it] == g && !problem.wishLocked(it, day) && board.current[it][day] == 0
            }.shuffled(rng)
            var k = 0
            for (s in pool) {
                if (k >= deficit) break
                val prefer = ShiftKinds.preferOnDuty(problem, s)
                if (prefer.isEmpty()) continue
                writes.add(s); writes.add(day); writes.add(prefer[rng.nextInt(prefer.size)])
                k++
            }
        }
        if (writes.isEmpty()) return null
        return Move(board.version, writes.toIntArray(), "c1_group", source)
    }

    /**
     * 同一日 3 人循環交換（小さな ejection）。評価1回で3セル。
     */
    fun dayTripleRepair(
        board: BoardView,
        problem: Problem,
        day: Int,
        rng: Random,
        source: String = "c1.triple",
    ): Move? {
        val unlocked = (0 until problem.S).filter { !problem.wishLocked(it, day) }
        if (unlocked.size < 3) return null
        val a = unlocked[rng.nextInt(unlocked.size)]
        var b = unlocked[rng.nextInt(unlocked.size)]
        var c = unlocked[rng.nextInt(unlocked.size)]
        if (a == b || b == c || a == c) return null
        val sa = board.current[a][day]
        val sb = board.current[b][day]
        val sc = board.current[c][day]
        // a<-b, b<-c, c<-a
        if (!problem.canDo(a, sb) || !problem.canDo(b, sc) || !problem.canDo(c, sa)) return null
        if (sa == sb && sb == sc) return null
        return Move(
            board.version,
            intArrayOf(a, day, sb, b, day, sc, c, day, sa),
            "c1_triple",
            source,
        )
    }
}
