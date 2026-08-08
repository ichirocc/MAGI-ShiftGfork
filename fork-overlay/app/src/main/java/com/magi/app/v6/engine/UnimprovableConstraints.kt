package com.magi.app.v6.engine

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.canDo
import com.magi.app.v6.wishLocked
import com.magi.app.v6.allowedShiftsForStaff

/**
 * 探索では改善できない HARD / シフト制約の検出。
 *
 * - HARD: 希望固定で塞がれた禁止連続、充足可能な担当者がいない人員不足 など
 * - シフト制約: 適切回数と上下限の矛盾、週平準の構造床 など
 *
 * 検出した族は RSI の infeasible に渡し、予算を無駄にしない。
 * ログ tag: MAGI_UNIMPROVABLE
 */
object UnimprovableConstraints {

    data class Finding(
        val family: String,
        val hard: Boolean,
        val reason: String,
        val count: Int = 1,
    )

    data class Report(
        val findings: List<Finding>,
        /** RSI / G2 から除外する族 */
        val excludeFamilies: Set<String>,
        /** 推定「探索不能」HARD 件数（参考） */
        val provenHardUnits: Int,
    ) {
        val hardFamilies: Set<String> get() = findings.filter { it.hard }.map { it.family }.toSet()
        val softFamilies: Set<String> get() = findings.filter { !it.hard }.map { it.family }.toSet()
    }

    fun analyze(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): Report {
        val findings = ArrayList<Finding>()
        findings += analyzeC3n(problem, schedule, report)
        findings += analyzeCovU(problem, schedule, report)
        findings += analyzeCovO(problem, schedule, report)
        findings += analyzeAptRanges(problem, schedule, report)
        findings += analyzeExactConflict(problem, report)
        findings += analyzeWeeklyFloor(problem, schedule, report)

        val exclude = HashSet<String>()
        var provenHard = 0
        for (f in findings) {
            if (f.hard && f.count > 0) {
                // 族全体が塞がっているときだけ exclude
                if (f.reason.startsWith("ALL:")) {
                    exclude += f.family
                }
                provenHard += f.count
            }
            // SOFT の構造床は exclude しない（部分改善の余地）が、週は床のみログ
        }

        // 部分 covU は exclude しない（動ける不足がある）
        val covUAll = findings.any { it.family == "covU" && it.reason.startsWith("ALL:") }
        if (!covUAll) exclude.remove("covU")

        logReport(findings, exclude, provenHard)
        return Report(findings, exclude, provenHard)
    }

    private fun logReport(findings: List<Finding>, exclude: Set<String>, provenHard: Int) {
        if (findings.isEmpty()) {
            android.util.Log.i("MAGI_UNIMPROVABLE", "none provenHard=0 exclude=[]")
            return
        }
        for (f in findings) {
            val kind = if (f.hard) "HARD" else "SOFT"
            android.util.Log.w(
                "MAGI_UNIMPROVABLE",
                "$kind family=${f.family} count=${f.count} ${f.reason}",
            )
        }
        android.util.Log.w(
            "MAGI_UNIMPROVABLE",
            "summary provenHardUnits=$provenHard exclude=$exclude",
        )
    }

    /** 希望固定で両端が動かない禁止連続 → c3n 改善不能 */
    private fun analyzeC3n(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): List<Finding> {
        val n = report.breakdown["c3n"] ?: 0
        if (n <= 0) return emptyList()
        val cfg = problem.constraintConfig()
        val night = cfg.nightShiftId
        val forbid = cfg.forbiddenAfterNight.toSet()
        if (night !in 0 until problem.K || forbid.isEmpty()) {
            // パターン不明でも、全セル希望固定なら触れる手がない
            return listOf(
                Finding("c3n", true, "PARTIAL: c3n=$n (pattern unknown; check wish locks)", n),
            )
        }
        var lockedPairs = 0
        var openPairs = 0
        for (s in 0 until problem.S) {
            for (d in 0 until problem.T - 1) {
                if (schedule[s][d] != night) continue
                if (schedule[s][d + 1] !in forbid) continue
                val bothLocked = problem.wishLocked(s, d) && problem.wishLocked(s, d + 1)
                if (bothLocked) lockedPairs++ else openPairs++
            }
        }
        if (lockedPairs == 0 && openPairs == 0) {
            return listOf(Finding("c3n", true, "PARTIAL: c3n=$n not matched night pattern", n))
        }
        if (openPairs == 0 && lockedPairs > 0) {
            return listOf(
                Finding(
                    "c3n",
                    true,
                    "ALL: $lockedPairs pairs wish-locked both days (希望固定の禁止連続)",
                    lockedPairs,
                ),
            )
        }
        if (lockedPairs > 0) {
            return listOf(
                Finding(
                    "c3n",
                    true,
                    "PARTIAL: locked=$lockedPairs open=$openPairs (固定分は探索不能)",
                    lockedPairs,
                ),
            )
        }
        return emptyList()
    }

    /** シフト別不足で、担当可・非固定の補充者が誰もいない */
    private fun analyzeCovU(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): List<Finding> {
        val n = report.breakdown["covU"] ?: report.breakdown["shiftU"] ?: 0
        if (n <= 0) return emptyList()
        val sd = problem.shiftDemand
        if (sd == null) {
            return listOf(Finding("covU", true, "PARTIAL: covU=$n no shiftDemand", n))
        }
        var blocked = 0
        var open = 0
        for (d in 0 until problem.T) {
            val row = sd.getOrNull(d) ?: continue
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                if (need <= 0) continue
                var have = 0
                for (s in 0 until problem.S) {
                    if (schedule[s][d] == k) have++
                }
                if (have >= need) continue
                val deficit = need - have
                val movers = (0 until problem.S).count { s ->
                    !problem.wishLocked(s, d) && problem.canDo(s, k) && schedule[s][d] != k
                }
                if (movers == 0) blocked += deficit else open += deficit
            }
        }
        if (blocked == 0) return emptyList()
        if (open == 0) {
            return listOf(
                Finding(
                    "covU",
                    true,
                    "ALL: $blocked units no movable assignee (担当可・非固定がいない不足)",
                    blocked,
                ),
            )
        }
        return listOf(
            Finding(
                "covU",
                true,
                "PARTIAL: blocked=$blocked open=$open (塞がった不足は探索不能)",
                blocked,
            ),
        )
    }

    private fun analyzeCovO(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): List<Finding> {
        val n = report.breakdown["covO"] ?: 0
        if (n <= 0) return emptyList()
        val sd = problem.shiftDemand ?: return emptyList()
        var blocked = 0
        var open = 0
        for (d in 0 until problem.T) {
            val row = sd.getOrNull(d) ?: continue
            for (k in 0 until minOf(problem.K, row.size)) {
                val need = row[k]
                var have = 0
                for (s in 0 until problem.S) if (schedule[s][d] == k) have++
                if (have <= need) continue
                val excess = have - need
                val movers = (0 until problem.S).count { s ->
                    !problem.wishLocked(s, d) && schedule[s][d] == k
                }
                if (movers == 0) blocked += excess else open += excess
            }
        }
        if (blocked == 0) return emptyList()
        if (open == 0) {
            return listOf(
                Finding("covO", true, "ALL: $blocked excess locked on over-staffed cells", blocked),
            )
        }
        return listOf(
            Finding("covO", true, "PARTIAL: blocked=$blocked open=$open", blocked),
        )
    }

    /** 適切回数 vs 上下限の矛盾（シフト制約） */
    private fun analyzeAptRanges(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): List<Finding> {
        val apt = (report.breakdown["apt"] ?: 0) +
            (report.breakdown["aptLow"] ?: 0) +
            (report.breakdown["aptHigh"] ?: 0)
        if (apt <= 0) return emptyList()
        var conflicts = 0
        for (s in 0 until problem.S) {
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[s][k]
                val hi = problem.rangeHi[s][k]
                if (lo != Int.MIN_VALUE && hi != Int.MAX_VALUE && lo > hi) {
                    conflicts++
                }
            }
        }
        if (conflicts > 0) {
            return listOf(
                Finding(
                    "apt",
                    false,
                    "STRUCT: rangeLo>rangeHi conflicts=$conflicts (設定矛盾)",
                    conflicts,
                ),
            )
        }
        // 目標が需要合計と矛盾する典型はログのみ（詳細は診断側）
        return listOf(
            Finding("apt", false, "SOFT: apt residual=$apt (探索で減る余地あり)", apt),
        )
    }

    private fun analyzeExactConflict(
        problem: Problem,
        report: ViolationReport,
    ): List<Finding> {
        val n = report.breakdown["exact"] ?: 0
        if (n <= 0) return emptyList()
        var pins = 0
        for (s in 0 until problem.S) {
            for (k in 0 until problem.K) {
                val lo = problem.rangeLo[s][k]
                val hi = problem.rangeHi[s][k]
                if (lo != Int.MIN_VALUE && lo == hi) pins++
            }
        }
        if (pins > 0 && n > 0) {
            return listOf(
                Finding(
                    "exact",
                    true,
                    "PARTIAL: exact pins=$pins residual=$n (固定回数は STRICT で悪化禁止)",
                    n,
                ),
            )
        }
        return emptyList()
    }

    /**
     * weekly: 7日周期で「床」になりやすい残差をソフト構造として記録。
     * 族全体は exclude しない（曜日寄せで減る分がある）。
     */
    private fun analyzeWeeklyFloor(
        problem: Problem,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): List<Finding> {
        val n = report.breakdown["weekly"] ?: 0
        if (n <= 0) return emptyList()
        // 粗い推定: 職員×シフトの回数が極端に偏っていると床が大きい
        var uneven = 0
        for (s in 0 until problem.S) {
            for (k in 0 until problem.K) {
                var c = 0
                for (d in 0 until problem.T) if (schedule[s][d] == k) c++
                if (c > 0 && problem.T >= 7 && c % 7 != 0) uneven++
            }
        }
        return listOf(
            Finding(
                "weekly",
                false,
                "STRUCT: weekly=$n unevenStaffShift=$uneven (構造床は消えにくい・部分改善可)",
                n,
            ),
        )
    }
}
