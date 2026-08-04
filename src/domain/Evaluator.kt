package com.magi.app.v6

import com.magi.app.v6.engine.ObjectiveWeightsSource
import kotlin.math.abs

/**
 * 全制約族を評価する単一エンジン。
 * breakdown キーは G2 focus / main Mirror 名に揃える。
 */
class Evaluator(
    private val problem: Problem,
) {
    fun evaluate(schedule: Array<IntArray>): ViolationReport {
        require(schedule.size == problem.S) { "schedule.S mismatch" }
        val bd = LinkedHashMap<String, Int>()
        var weighted = 0.0
        val cfg = problem.constraintConfig()

        val dayHave = IntArray(problem.T)
        for (i in 0 until problem.S) {
            require(schedule[i].size == problem.T)
            for (j in 0 until problem.T) if (schedule[i][j] > 0) dayHave[j]++
        }
        var covU = 0
        var covO = 0
        for (j in 0 until problem.T) {
            val need = problem.dayDemand[j]
            if (need <= 0) continue
            val d = dayHave[j] - need
            if (d < 0) covU += -d else if (d > 0) covO += d
        }
        putHard(bd, "covU", covU)
        weighted = putSoft(bd, "covO", covO, weighted)

        problem.shiftDemand?.let { sd ->
            var sU = 0
            for (j in 0 until problem.T) {
                val have = IntArray(problem.K)
                for (i in 0 until problem.S) {
                    val sh = schedule[i][j]
                    if (sh in 0 until problem.K) have[sh]++
                }
                for (k in 1 until problem.K) {
                    val need = sd.getOrNull(j)?.getOrNull(k) ?: 0
                    if (need > 0 && have[k] < need) sU += need - have[k]
                }
            }
            putHard(bd, "shiftU", sU)
        }

        var exact = 0
        var range = 0
        var low = 0
        var high = 0
        for (i in 0 until problem.S) {
            val cnt = IntArray(problem.K)
            for (j in 0 until problem.T) {
                val sh = schedule[i][j]
                if (sh in 0 until problem.K) cnt[sh]++
            }
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[i][k]
                val hi = problem.rangeHi[i][k]
                if (lo == Int.MIN_VALUE && hi == Int.MAX_VALUE) continue
                val c = cnt[k]
                if (lo != Int.MIN_VALUE && lo == hi) {
                    if (c != lo) exact += abs(c - lo)
                } else {
                    if (lo != Int.MIN_VALUE && c < lo) {
                        val d = lo - c
                        range += d; low += d
                    }
                    if (hi != Int.MAX_VALUE && c > hi) {
                        val d = c - hi
                        range += d; high += d
                    }
                }
            }
        }
        putHard(bd, "exact", exact)
        weighted = putSoft(bd, "range", range, weighted)
        weighted = putSoft(bd, "low", low, weighted)
        weighted = putSoft(bd, "high", high, weighted)

        var illegal = 0
        for (i in 0 until problem.S) for (j in 0 until problem.T) {
            val sh = schedule[i][j]
            if (sh !in 0 until problem.K || !problem.canDo(i, sh)) illegal++
        }
        putHard(bd, "illegal", illegal)

        var wishMiss = 0
        var pref = 0
        var lockBreak = 0
        for (i in 0 until problem.S) for (j in 0 until problem.T) {
            val pr = problem.preferred[i][j]
            if (pr < 0) continue
            if (schedule[i][j] == pr) continue
            if (problem.wishLocked(i, j)) lockBreak++
            else { wishMiss++; pref++ }
        }
        putHard(bd, "lockBreak", lockBreak)
        weighted = putSoft(bd, "wish", wishMiss, weighted)
        weighted = putSoft(bd, "pref", pref, weighted)

        val workDays = IntArray(problem.S) { s ->
            (0 until problem.T).count { schedule[s][it] > 0 }
        }
        val avg = if (problem.S > 0) workDays.average() else 0.0
        var bal = 0
        for (w in workDays) bal += abs(w - avg).toInt()
        if (cfg.enableFair) {
            weighted = putSoft(bd, "bal", bal, weighted)
            weighted = putSoft(bd, "fair", bal, weighted)
        }

        // weekly = 7日周期（曜日）の勤務シフト平準化
        // 各スタッフについて、各シフト種の曜日別回数のばらつきを罰する
        if (cfg.enableWeekly) {
            var weekly = 0
            for (i in 0 until problem.S) {
                // shift k → weekday 0..6 の回数
                val byKW = Array(problem.K) { IntArray(7) }
                for (j in 0 until problem.T) {
                    val sh = schedule[i][j]
                    if (sh !in 0 until problem.K) continue
                    byKW[sh][j % 7]++
                }
                for (k in 0 until problem.K) {
                    val row = byKW[k]
                    val mx = row.maxOrNull() ?: 0
                    val mn = row.minOrNull() ?: 0
                    weekly += mx - mn
                }
            }
            // 任意: 週あたり勤務日数上限
            if (cfg.maxWorkDaysPerWeek > 0) {
                val weeks = (problem.T + 6) / 7
                for (i in 0 until problem.S) {
                    for (w in 0 until weeks) {
                        val from = w * 7
                        val to = minOf(problem.T, from + 7)
                        var cnt = 0
                        for (d in from until to) if (schedule[i][d] != 0) cnt++
                        if (cnt > cfg.maxWorkDaysPerWeek) weekly += cnt - cfg.maxWorkDaysPerWeek
                    }
                }
            }
            weighted = putSoft(bd, "weekly", weekly, weighted)
        }

        if (cfg.enableC3 && cfg.maxConsecutiveWork > 0) {
            var c3 = 0
            for (i in 0 until problem.S) {
                var run = 0
                for (j in 0 until problem.T) {
                    if (schedule[i][j] > 0) {
                        run++
                        if (run > cfg.maxConsecutiveWork) c3++
                    } else run = 0
                }
            }
            weighted = putSoft(bd, "c3", c3, weighted)
        }

        if (cfg.enableC3 && cfg.minConsecutiveOff > 0) {
            var c3m = 0
            for (i in 0 until problem.S) {
                var offRun = 0
                var inOff = false
                for (j in 0 until problem.T) {
                    if (schedule[i][j] == 0) {
                        offRun++; inOff = true
                    } else {
                        if (inOff && offRun < cfg.minConsecutiveOff) c3m += cfg.minConsecutiveOff - offRun
                        offRun = 0; inOff = false
                    }
                }
            }
            weighted = putSoft(bd, "c3m", c3m, weighted)
            var c3mn = 0
            val night = cfg.nightShiftId
            if (night in 0 until problem.K) {
                for (i in 0 until problem.S) {
                    var run = 0
                    for (j in 0 until problem.T) {
                        if (schedule[i][j] > 0) {
                            run++
                            if (schedule[i][j] == night && run >= cfg.maxConsecutiveWork) c3mn++
                        } else run = 0
                    }
                }
            }
            weighted = putSoft(bd, "c3mn", c3mn, weighted)
        }

        if (cfg.enableC3n && cfg.nightShiftId in 0 until problem.K) {
            var c3n = 0
            val night = cfg.nightShiftId
            val forbid = cfg.forbiddenAfterNight
            for (i in 0 until problem.S) {
                for (j in 0 until problem.T - 1) {
                    if (schedule[i][j] == night && schedule[i][j + 1] in forbid) c3n++
                }
            }
            putHard(bd, "c3n", c3n)
        }

        if (cfg.enableC1) {
            var c1 = 0
            val groups = problem.staffGroup()
            for (j in 0 until problem.T) {
                if (problem.dayDemand[j] <= 0) continue
                val present = HashSet<Int>()
                for (i in 0 until problem.S) if (schedule[i][j] > 0) present.add(groups[i])
                val needG = if (problem.S >= 2) 2 else 1
                if (present.size < needG) c1 += needG - present.size
            }
            putHard(bd, "c1", c1)
        }

        if (cfg.enableC2) {
            var c2 = 0
            for (j in 0 until problem.T) {
                val cnt = IntArray(problem.K)
                for (i in 0 until problem.S) {
                    val sh = schedule[i][j]
                    if (sh in 1 until problem.K) cnt[sh]++
                }
                for (k in 1 until problem.K) {
                    val need = problem.shiftDemand?.getOrNull(j)?.getOrNull(k)
                        ?: if (problem.dayDemand[j] > 0) 1 else 0
                    if (need > 0 && cnt[k] > need + 1) c2 += cnt[k] - need - 1
                }
            }
            weighted = putSoft(bd, "c2", c2, weighted)
        }

        if (cfg.enableApt) {
            var apt = 0
            val skill = problem.skillMatrix()
            for (i in 0 until problem.S) for (j in 0 until problem.T) {
                val sh = schedule[i][j]
                if (sh in 0 until problem.K && !skill[i][sh]) apt++
            }
            weighted = putSoft(bd, "apt", apt, weighted)
        }

        if (cfg.enableGroup) {
            var groupViol = 0
            val groups = problem.staffGroup()
            val demand = problem.groupDemand()
            val nG = demand.firstOrNull()?.size ?: 0
            for (j in 0 until problem.T) {
                val have = IntArray(nG)
                for (i in 0 until problem.S) {
                    if (schedule[i][j] > 0) {
                        val g = groups[i]
                        if (g in 0 until nG) have[g]++
                    }
                }
                for (g in 0 until nG) {
                    val need = demand[j][g]
                    if (need > 0 && have[g] < need) groupViol += need - have[g]
                }
            }
            putHard(bd, "groupViol", groupViol)
        }

        val hardFinal = ObjectiveWeightsSource.hardKeys().sumOf { bd[it] ?: 0 }
        val softWeighted = weighted.coerceAtLeast(0.0)
        val soft = softWeighted.toInt().coerceAtLeast(0)
        // soft 表示は回数寄り、比較用 weightedScore は重み付き
        return ViolationReport.of(
            hard = hardFinal,
            soft = soft,
            breakdown = bd,
            weightedScore = hardFinal * ViolationReport.HARD_UNIT + softWeighted.toLong(),
        )
    }

    private fun putHard(bd: MutableMap<String, Int>, key: String, v: Int) {
        if (v > 0) bd[key] = v
    }

    private fun putSoft(bd: MutableMap<String, Int>, key: String, v: Int, weighted: Double): Double {
        if (v <= 0) return weighted
        bd[key] = v
        return weighted + v * ObjectiveWeightsSource.softWeight(key)
    }
}
