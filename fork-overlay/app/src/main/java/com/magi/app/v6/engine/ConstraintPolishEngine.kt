package com.magi.app.v6.engine
import com.magi.app.v6.dayDemand
import com.magi.app.v6.wishLocked
import com.magi.app.v6.canDo
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.preferred

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.constraintConfig
import com.magi.app.v6.groupDemand
import com.magi.app.v6.skillMatrix
import com.magi.app.v6.staffGroup
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * main 制約違反研磨の完成形。
 *
 * 設計原則（main RSI / find*Fix / ALNS-repair / Hotfix を抽象化）:
 * 1. 残差を「キー」だけでなく「セル・日・スタッフ」に局所化してから手を作る
 * 2. 単発ランダムより、構造手（swap / 連鎖 / 日 LNS / パターン破壊）を優先
 * 3. STRICT のみで採否（betterReport）。best は Session 契約に従う
 * 4. HARD 残差が残る間は soft 研磨に時間を使わない
 * 5. 同じ fingerprint の失敗は再試行せず、破壊半径を広げてから諦める
 *
 * 実装コストは無視し、精度と到達性を優先する。
 */
class ConstraintPolishEngine(
    private val problem: Problem,
    private val rng: Random = Random(0L),
) {
    private val S = problem.S
    private val T = problem.T
    private val K = problem.K
    private val struct = StructuralMoveFactory(problem, rng)

    private val hardOrder = listOf(
        "lockBreak", "illegal", "c3n", "covU", "shiftU", "groupViol", "c1", "exact",
    )
    private val softOrder = listOf(
        "low", "high", "range", "c3", "c3m", "c3mn", "weekly",
        "pref", "wish", "covO", "c2", "apt", "bal", "fair",
    )
    private val weight = mapOf(
        "lockBreak" to 1e6, "illegal" to 1e6, "c3n" to 5e5,
        "covU" to 1e5, "shiftU" to 1e5, "groupViol" to 8e4, "c1" to 6e4, "exact" to 5e4,
        "low" to 3e3, "high" to 2e3, "range" to 2e3,
        "c3" to 1.5e3, "c3m" to 1.2e3, "c3mn" to 1.2e3, "weekly" to 1e3,
        "pref" to 800.0, "wish" to 800.0, "covO" to 400.0, "c2" to 400.0,
        "apt" to 300.0, "bal" to 100.0, "fair" to 100.0,
    )

    data class Residual(
        val key: String,
        val amount: Int,
        val days: IntArray = intArrayOf(),
        val staff: IntArray = intArrayOf(),
        val cells: IntArray = intArrayOf(), // packed s<<16|d
    )

    /**
     * 締切まで残差を削る。戻り値は STRICT 受理回数。
     */
    fun run(session: SearchSessionFull, deadlineMs: Long): Int {
        var accepts = 0
        var idleWaves = 0
        while (System.currentTimeMillis() < deadlineMs && idleWaves < 6) {
            val report = session.currentReport
            if (isClean(report)) break
            val residuals = localize(session.current, report)
            if (residuals.isEmpty()) break
            val focus = residuals.first()
            val before = report.breakdown[focus.key] ?: 0
            val got = repairFocus(session, focus, deadlineMs)
            accepts += got
            val after = session.currentReport.breakdown[focus.key] ?: 0
            if (got == 0 || after >= before) idleWaves++ else idleWaves = 0
        }
        // 仕上げ: 全 soft を浅いパスでもう一周
        if (System.currentTimeMillis() < deadlineMs && session.currentReport.hard == 0) {
            accepts += softSweep(session, deadlineMs)
        }
        return accepts
    }

    private fun isClean(r: ViolationReport): Boolean =
        r.hard == 0 && softOrder.all { (r.breakdown[it] ?: 0) == 0 }

    private fun localize(sch: Array<IntArray>, report: ViolationReport): List<Residual> {
        val out = ArrayList<Residual>()
        fun amt(k: String) = report.breakdown[k] ?: 0

        // HARD localizers
        if (amt("lockBreak") > 0) out += Residual("lockBreak", amt("lockBreak"), cells = wishCells(sch, lockedOnly = true))
        if (amt("illegal") > 0) out += Residual("illegal", amt("illegal"), cells = illegalCells(sch))
        if (amt("c3n") > 0) out += Residual("c3n", amt("c3n"), cells = c3nCells(sch))
        if (amt("covU") > 0) out += Residual("covU", amt("covU"), days = underDays(sch))
        if (amt("shiftU") > 0) out += Residual("shiftU", amt("shiftU"), days = underDays(sch))
        if (amt("groupViol") > 0) out += Residual("groupViol", amt("groupViol"), days = groupShortDays(sch))
        if (amt("c1") > 0) out += Residual("c1", amt("c1"), days = c1ShortDays(sch))
        if (amt("exact") > 0) out += Residual("exact", amt("exact"), staff = exactBadStaff(sch))

        // SOFT localizers
        if (amt("low") > 0) out += Residual("low", amt("low"), staff = rangeLowStaff(sch))
        if (amt("high") > 0) out += Residual("high", amt("high"), staff = rangeHighStaff(sch))
        if (amt("range") > 0) out += Residual("range", amt("range"), staff = rangeBadStaff(sch))
        if (amt("c3") > 0) out += Residual("c3", amt("c3"), staff = streakStaff(sch))
        if (amt("c3m") > 0) out += Residual("c3m", amt("c3m"), staff = shortRestStaff(sch))
        if (amt("c3mn") > 0) out += Residual("c3mn", amt("c3mn"), staff = streakStaff(sch))
        if (amt("weekly") > 0) out += Residual("weekly", amt("weekly"), staff = weeklyHeavyStaff(sch))
        if (amt("pref") > 0 || amt("wish") > 0) {
            val a = maxOf(amt("pref"), amt("wish"))
            out += Residual(if (amt("pref") >= amt("wish")) "pref" else "wish", a, cells = wishCells(sch, lockedOnly = false))
        }
        if (amt("covO") > 0) out += Residual("covO", amt("covO"), days = overDays(sch))
        if (amt("c2") > 0) out += Residual("c2", amt("c2"), days = overDays(sch))
        if (amt("apt") > 0) out += Residual("apt", amt("apt"), cells = aptCells(sch))
        if (amt("bal") > 0 || amt("fair") > 0) {
            out += Residual("bal", maxOf(amt("bal"), amt("fair")), staff = IntArray(S) { it })
        }

        return out.sortedByDescending { it.amount * (weight[it.key] ?: 1.0) }
    }

    private fun repairFocus(
        session: SearchSessionFull,
        focus: Residual,
        deadlineMs: Long,
    ): Int {
        val generators: List<() -> Move?> = when (focus.key) {
            "covU", "shiftU" -> listOf(
                { genFillUnderDay(session, focus) },
                { genDayLnsFill(session, focus) },
                { genTransferFromOver(session, focus) },
                { struct.chainFillUnder(session, if (focus.days.isNotEmpty()) focus.days else underDays(session.current)) },
                { struct.tripleCycle(session, (if (focus.days.isNotEmpty()) focus.days else underDays(session.current)).let { if (it.isEmpty()) 0 else it[rng.nextInt(it.size)] }) },
                { struct.anyStructural(session, if (focus.days.isNotEmpty()) focus.days else intArrayOf(), if (focus.staff.isNotEmpty()) focus.staff else intArrayOf()) },
            )
            "covO", "c2" -> listOf(
                { genRetypeOver(session, focus) },
                { genTransferOverToUnder(session, focus) },
                { struct.anyStructural(session, if (focus.days.isNotEmpty()) focus.days else intArrayOf(), if (focus.staff.isNotEmpty()) focus.staff else intArrayOf()) },
                { struct.tripleCycle(session, (if (focus.days.isNotEmpty()) focus.days else overDays(session.current)).let { if (it.isEmpty()) 0 else it[rng.nextInt(it.size)] }) },
            )
            "c1" -> listOf(
                { genFillMissingGroup(session, focus) },
                { genDayLnsFill(session, focus) },
            )
            "groupViol" -> listOf(
                { genFillGroup(session, focus) },
                { genDayLnsFill(session, focus) },
            )
            "exact" -> listOf(
                { genExactAdjust(session, focus) },
                { genExactChain(session, focus) },
                { struct.anyStructural(session, if (focus.days.isNotEmpty()) focus.days else intArrayOf(), if (focus.staff.isNotEmpty()) focus.staff else intArrayOf()) },
                { genExactCascade(session, focus) },
            )
            "low", "range" -> listOf(
                { genRangeLow(session, focus) },
                { genExactChain(session, focus) },
            )
            "high" -> listOf(
                { genRangeHigh(session, focus) },
            )
            "c3", "c3mn" -> listOf(
                { genBreakStreakRetype(session, focus) },
                { genBreakStreakSwap(session, focus) },
                { struct.anyStructural(session, if (focus.days.isNotEmpty()) focus.days else intArrayOf(), if (focus.staff.isNotEmpty()) focus.staff else intArrayOf()) },
                { genStreakCascade(session, focus) },
            )
            "c3m" -> listOf({ genExtendRest(session, focus) })
            "c3n" -> listOf({ genFixNightFollow(session, focus) })
            "weekly" -> listOf(
                { genWeeklyRetype(session, focus) },
                { genWeeklyTransfer(session, focus) },
            )
            "pref", "wish" -> listOf({ genPref(session, focus) })
            "apt" -> listOf({ genApt(session, focus) })
            "illegal" -> listOf({ genLegalize(session, focus) })
            "lockBreak" -> listOf({ genPref(session, focus) }) // lock は Session が守る; 希望整合のみ
            "bal", "fair" -> listOf(
                { genBalanceTransfer(session, focus) },
                { genBalanceSwapDay(session, focus) },
                { struct.anyStructural(session, if (focus.days.isNotEmpty()) focus.days else intArrayOf(), if (focus.staff.isNotEmpty()) focus.staff else intArrayOf()) },
                { genBalanceCascade(session, focus) },
            )
            else -> listOf({ ConstraintPolishers.propose(focus.key, session, problem, rng) })
        }

        return tryApplyGenerators(session, generators, focus.key, deadlineMs, maxTries = 48)
    }

    private fun softSweep(session: SearchSessionFull, deadlineMs: Long): Int {
        var n = 0
        for (key in softOrder) {
            if (System.currentTimeMillis() >= deadlineMs) break
            if ((session.currentReport.breakdown[key] ?: 0) <= 0) continue
            val focus = Residual(key, session.currentReport.breakdown[key] ?: 1)
            n += repairFocus(session, focus, deadlineMs)
        }
        return n
    }

    // ---------- generators (deep) ----------

    private fun genFillUnderDay(session: SearchSessionFull, focus: Residual): Move? {
        val days = if (focus.days.isNotEmpty()) focus.days else underDays(session.current)
        if (days.isEmpty()) return null
        val d = days[rng.nextInt(days.size)]
        val order = staffPerm()
        for (s in order) {
            if (problem.wishLocked(s, d) || session.current[s][d] > 0) continue
            val work = ShiftKinds.preferOnDuty(problem, s)
            if (work.isEmpty()) continue
            // prefer preferred if any
            val pref = problem.preferred[s][d]
            val sh = if (pref > 0 && pref in work) pref else work[rng.nextInt(work.size)]
            return buildSingleMove(session, problem, s, d, sh, "eng.fill.$d")
        }
        return null
    }

    private fun genDayLnsFill(session: SearchSessionFull, focus: Residual): Move? {
        val days = if (focus.days.isNotEmpty()) focus.days else underDays(session.current)
        if (days.isEmpty()) return null
        val d = days[rng.nextInt(days.size)]
        val buf = ArrayList<Int>()
        val order = staffPerm()
        for (s in order) {
            if (problem.wishLocked(s, d)) continue
            val work = ShiftKinds.preferOnDuty(problem, s)
            if (work.isEmpty()) continue
            if (session.current[s][d] == 0) {
                buf.add(s); buf.add(work[rng.nextInt(work.size)])
            }
            if (buf.size >= 8) break
        }
        if (buf.size < 4) return null
        return buildDayLnsMove(session, problem, d, buf.toIntArray(), "eng.dayLns")
    }

    private fun genTransferFromOver(session: SearchSessionFull, @Suppress("UNUSED_PARAMETER") focus: Residual): Move? {
        val under = underDays(session.current)
        val over = overDays(session.current)
        if (under.isEmpty() || over.isEmpty()) return null
        val dUnder = under[rng.nextInt(under.size)]
        val dOver = over[rng.nextInt(over.size)]
        if (dUnder == dOver) return null
        for (s in staffPerm()) {
            if (problem.wishLocked(s, dOver) || problem.wishLocked(s, dUnder)) continue
            if (session.current[s][dOver] == 0 || session.current[s][dUnder] > 0) continue
            val sh = session.current[s][dOver]
            if (!problem.canDo(s, ShiftKinds.REST) || !problem.canDo(s, sh)) continue
            return Move(
                session.version,
                intArrayOf(s, dOver, 0, s, dUnder, sh),
                "transfer",
                "eng.xfer.over2under",
            )
        }
        return null
    }

    private fun genRetypeOver(session: SearchSessionFull, focus: Residual): Move? {
        val days = if (focus.days.isNotEmpty()) focus.days else overDays(session.current)
        if (days.isEmpty()) return null
        val d = days[rng.nextInt(days.size)]
        for (s in staffPerm()) {
            if (problem.wishLocked(s, d) || session.current[s][d] == 0) continue
            val cur = session.current[s][d]
            val alt = problem.allowedShiftsForStaff(s).filter { it != cur }
            if (alt.isEmpty()) continue
            return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.retype")
        }
        return null
    }

    private fun genTransferOverToUnder(session: SearchSessionFull, focus: Residual): Move? =
        genTransferFromOver(session, focus)

    private fun genFillMissingGroup(session: SearchSessionFull, focus: Residual): Move? {
        val groups = problem.staffGroup()
        val days = if (focus.days.isNotEmpty()) focus.days else c1ShortDays(session.current)
        if (days.isEmpty()) return null
        val d = days[rng.nextInt(days.size)]
        val present = HashSet<Int>()
        for (i in 0 until S) if (session.current[i][d] > 0) present.add(groups[i])
        for (s in staffPerm()) {
            if (groups[s] in present) continue
            if (problem.wishLocked(s, d) || session.current[s][d] > 0) continue
            val work = ShiftKinds.preferOnDuty(problem, s)
            if (work.isEmpty()) continue
            return buildSingleMove(session, problem, s, d, work[rng.nextInt(work.size)], "eng.c1")
        }
        return null
    }

    private fun genFillGroup(session: SearchSessionFull, focus: Residual): Move? {
        val groups = problem.staffGroup()
        val demand = problem.groupDemand()
        val nG = demand.firstOrNull()?.size ?: return null
        val days = if (focus.days.isNotEmpty()) focus.days else groupShortDays(session.current)
        if (days.isEmpty()) return null
        val d = days[rng.nextInt(days.size)]
        val have = IntArray(nG)
        for (i in 0 until S) {
            if (session.current[i][d] > 0) {
                val g = groups[i]
                if (g in 0 until nG) have[g]++
            }
        }
        for (g in 0 until nG) {
            if (demand[d][g] <= 0 || have[g] >= demand[d][g]) continue
            for (s in staffPerm()) {
                if (groups[s] != g) continue
                if (problem.wishLocked(s, d) || session.current[s][d] > 0) continue
                val work = ShiftKinds.preferOnDuty(problem, s)
                if (work.isEmpty()) continue
                return buildSingleMove(session, problem, s, d, work[rng.nextInt(work.size)], "eng.group")
            }
        }
        return null
    }

    private fun genExactAdjust(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else exactBadStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (k in 0 until K) {
            val lo = problem.rangeLo[s][k]
            val hi = problem.rangeHi[s][k]
            if (lo == Int.MIN_VALUE || lo != hi) continue
            val c = (0 until T).count { session.current[s][it] == k }
            if (c == lo) continue
            if (c < lo) {
                val d = (0 until T).firstOrNull {
                    !problem.wishLocked(s, it) && session.current[s][it] != k && problem.canDo(s, k)
                } ?: continue
                return buildSingleMove(session, problem, s, d, k, "eng.exact+")
            } else {
                val d = (0 until T).firstOrNull {
                    !problem.wishLocked(s, it) && session.current[s][it] == k
                } ?: continue
                val alt = problem.allowedShiftsForStaff(s).filter { it != k }
                if (alt.isEmpty()) continue
                return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.exact-")
            }
        }
        return null
    }

    /** exact / range: 他スタッフと日を交換して回数を合わせる連鎖 */
    private fun genExactChain(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else exactBadStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (d in 0 until T) {
            if (problem.wishLocked(s, d)) continue
            val sh = session.current[s][d]
            for (t in staffPerm()) {
                if (t == s || problem.wishLocked(t, d)) continue
                val other = session.current[t][d]
                if (other == sh) continue
                if (!problem.canDo(s, other) || !problem.canDo(t, sh)) continue
                return Move(
                    session.version,
                    intArrayOf(s, d, other, t, d, sh),
                    "chain",
                    "eng.chain",
                )
            }
        }
        return null
    }

    private fun genRangeLow(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else rangeLowStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (k in 1 until K) {
            val lo = problem.rangeLo[s][k]
            if (lo == Int.MIN_VALUE) continue
            val c = (0 until T).count { session.current[s][it] == k }
            if (c >= lo) continue
            val d = (0 until T).firstOrNull {
                !problem.wishLocked(s, it) && session.current[s][it] != k && problem.canDo(s, k)
            } ?: continue
            return buildSingleMove(session, problem, s, d, k, "eng.low")
        }
        return null
    }

    private fun genRangeHigh(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else rangeHighStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (k in 0 until K) {
            val hi = problem.rangeHi[s][k]
            if (hi == Int.MAX_VALUE) continue
            val c = (0 until T).count { session.current[s][it] == k }
            if (c <= hi) continue
            val d = (0 until T).firstOrNull {
                !problem.wishLocked(s, it) && session.current[s][it] == k
            } ?: continue
            val alt = ShiftKinds.otherThan(problem, s, k)
            if (alt.isEmpty()) continue
            return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.high")
        }
        return null
    }

    private fun genBreakStreakRetype(session: SearchSessionFull, focus: Residual): Move? {
        val maxRun = problem.constraintConfig().maxConsecutiveWork
        if (maxRun <= 0) return null
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else streakStaff(session.current)
        for (s in staffs) {
            var run = 0
            for (d in 0 until T) {
                if (session.current[s][d] > 0) {
                    run++
                    if (run > maxRun && !problem.wishLocked(s, d)) {
                        val cur = session.current[s][d]
                        val alt = problem.allowedShiftsForStaff(s).filter { it != cur }
                        // 連勤切断: 休を含む別シフトへの変更（休は通常シフト種）
                        if (problem.canDo(s, ShiftKinds.REST)) {
                            return buildSingleMove(session, problem, s, d, ShiftKinds.REST, "eng.c3.rest")
                        }
                        if (alt.isNotEmpty()) {
                            return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.c3.retype")
                        }
                    }
                } else run = 0
            }
        }
        return null
    }

    private fun genBreakStreakSwap(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else streakStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (d in 0 until T) {
            if (problem.wishLocked(s, d) || session.current[s][d] == 0) continue
            for (t in staffPerm()) {
                if (t == s || problem.wishLocked(t, d)) continue
                if (session.current[t][d] > 0) continue
                val sh = session.current[s][d]
                if (!problem.canDo(t, sh) || !problem.canDo(s, ShiftKinds.REST)) continue
                return Move(
                    session.version,
                    intArrayOf(s, d, 0, t, d, sh),
                    "swap",
                    "eng.c3.swap",
                )
            }
        }
        return null
    }

    private fun genExtendRest(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else shortRestStaff(session.current)
        for (s in staffs) {
            for (d in 1 until T - 1) {
                if (session.current[s][d] != 0) continue
                if (session.current[s][d - 1] == 0 || session.current[s][d + 1] == 0) continue
                if (problem.wishLocked(s, d + 1)) continue
                if (session.current[s][d + 1] > 0 && problem.canDo(s, ShiftKinds.REST)) {
                    return buildSingleMove(session, problem, s, d + 1, ShiftKinds.REST, "eng.c3m")
                }
            }
        }
        return null
    }

    private fun genFixNightFollow(session: SearchSessionFull, focus: Residual): Move? {
        val cfg = problem.constraintConfig()
        val night = cfg.nightShiftId
        if (night !in 0 until K) return null
        val forbid = cfg.forbiddenAfterNight
        val cells = if (focus.cells.isNotEmpty()) focus.cells else c3nCells(session.current)
        for (pack in cells) {
            val s = pack ushr 16
            val d = pack and 0xFFFF
            if (d + 1 >= T) continue
            if (problem.wishLocked(s, d + 1)) continue
            val alt = problem.allowedShiftsForStaff(s).filter { it !in forbid }
            val sh = when {
                0 in alt || problem.canDo(s, ShiftKinds.REST) -> ShiftKinds.REST
                alt.isNotEmpty() -> alt[rng.nextInt(alt.size)]
                else -> continue
            }
            return buildSingleMove(session, problem, s, d + 1, sh, "eng.c3n")
        }
        return null
    }

    private fun genWeeklyRetype(session: SearchSessionFull, focus: Residual): Move? {
        // 7日周期: シフト k が偏っている曜日同士で交換
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else weeklyUnbalancedStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        val k = rng.nextInt(K)
        val wdCnt = IntArray(7)
        for (d in 0 until T) if (session.current[s][d] == k) wdCnt[d % 7]++
        val hi = (0 until 7).maxByOrNull { wdCnt[it] } ?: return null
        val lo = (0 until 7).minByOrNull { wdCnt[it] } ?: return null
        if (wdCnt[hi] <= wdCnt[lo]) return null
        val hiDays = (0 until T).filter {
            it % 7 == hi && session.current[s][it] == k && !problem.wishLocked(s, it)
        }
        val loDays = (0 until T).filter {
            it % 7 == lo && session.current[s][it] != k && !problem.wishLocked(s, it) &&
                problem.canDo(s, k)
        }
        if (hiDays.isEmpty() || loDays.isEmpty()) return null
        val dHi = hiDays[rng.nextInt(hiDays.size)]
        val dLo = loDays[rng.nextInt(loDays.size)]
        val shLo = session.current[s][dLo]
        if (!problem.canDo(s, shLo)) return null
        return Move(
            session.version,
            intArrayOf(s, dHi, shLo, s, dLo, k),
            "weekly",
            "eng.weekly.level",
        )
    }

    private fun genWeeklyTransfer(session: SearchSessionFull, @Suppress("UNUSED_PARAMETER") focus: Residual): Move? {
        // 同一曜日で偏りが大きいスタッフ間でスワップ
        val staffs = weeklyUnbalancedStaff(session.current)
        if (staffs.size < 2) return null
        val a = staffs[rng.nextInt(staffs.size)]
        var b = staffs[rng.nextInt(staffs.size)]
        if (a == b) b = staffs[(staffs.indexOf(a) + 1) % staffs.size]
        val wd = rng.nextInt(7)
        val daysA = (0 until T).filter { it % 7 == wd && !problem.wishLocked(a, it) }
        val daysB = (0 until T).filter { it % 7 == wd && !problem.wishLocked(b, it) }
        if (daysA.isEmpty() || daysB.isEmpty()) return null
        val dA = daysA[rng.nextInt(daysA.size)]
        val dB = daysB[rng.nextInt(daysB.size)]
        val sa = session.current[a][dA]
        val sb = session.current[b][dB]
        if (sa == sb) return null
        if (!problem.canDo(a, sb) || !problem.canDo(b, sa)) return null
        return Move(
            session.version,
            intArrayOf(a, dA, sb, b, dB, sa),
            "weekly",
            "eng.weekly.swap",
        )
    }

    private fun genPref(session: SearchSessionFull, focus: Residual): Move? {
        val cells = if (focus.cells.isNotEmpty()) focus.cells else wishCells(session.current, false)
        if (cells.isEmpty()) return null
        val pack = cells[rng.nextInt(cells.size)]
        val s = pack ushr 16
        val d = pack and 0xFFFF
        val pref = problem.preferred[s][d]
        if (pref < 0 || problem.wishLocked(s, d) || !problem.canDo(s, pref)) return null
        return buildSingleMove(session, problem, s, d, pref, "eng.pref")
    }

    private fun genApt(session: SearchSessionFull, focus: Residual): Move? {
        val skill = problem.skillMatrix()
        val cells = if (focus.cells.isNotEmpty()) focus.cells else aptCells(session.current)
        for (pack in cells) {
            val s = pack ushr 16
            val d = pack and 0xFFFF
            if (problem.wishLocked(s, d)) continue
            val alt = problem.allowedShiftsForStaff(s).filter { it < skill[s].size && skill[s][it] }
            if (alt.isEmpty()) continue
            return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.apt")
        }
        return null
    }

    private fun genLegalize(session: SearchSessionFull, focus: Residual): Move? {
        val cells = if (focus.cells.isNotEmpty()) focus.cells else illegalCells(session.current)
        for (pack in cells) {
            val s = pack ushr 16
            val d = pack and 0xFFFF
            if (problem.wishLocked(s, d)) continue
            val alt = problem.allowedShiftsForStaff(s)
            if (alt.isEmpty()) continue
            return buildSingleMove(session, problem, s, d, alt[rng.nextInt(alt.size)], "eng.illegal")
        }
        return null
    }

    private fun genBalanceTransfer(session: SearchSessionFull, @Suppress("UNUSED_PARAMETER") focus: Residual): Move? {
        val work = IntArray(S) { s -> (0 until T).count { session.current[s][it] > 0 } }
        val hi = work.indices.maxByOrNull { work[it] } ?: return null
        val lo = work.indices.minByOrNull { work[it] } ?: return null
        if (work[hi] <= work[lo] + 1) return null
        for (d in 0 until T) {
            if (problem.wishLocked(hi, d) || problem.wishLocked(lo, d)) continue
            if (session.current[hi][d] == 0 || session.current[lo][d] > 0) continue
            val sh = session.current[hi][d]
            if (!problem.canDo(lo, sh) || !problem.canDo(hi, ShiftKinds.REST)) continue
            return Move(session.version, intArrayOf(hi, d, 0, lo, d, sh), "bal", "eng.bal")
        }
        return null
    }

    private fun genBalanceSwapDay(session: SearchSessionFull, @Suppress("UNUSED_PARAMETER") focus: Residual): Move? {
        if (T < 2) return null
        val s = rng.nextInt(S)
        val d1 = rng.nextInt(T)
        var d2 = rng.nextInt(T)
        if (d1 == d2) d2 = (d2 + 1) % T
        return buildSwapDaysMove(session, problem, s, d1, d2, "eng.bal.swap")
    }

    // ---------- localization helpers ----------

    private fun underDays(sch: Array<IntArray>): IntArray {
        val have = dayHave(sch)
        return (0 until T).filter { problem.dayDemand[it] > 0 && have[it] < problem.dayDemand[it] }.toIntArray()
    }

    private fun overDays(sch: Array<IntArray>): IntArray {
        val have = dayHave(sch)
        return (0 until T).filter { problem.dayDemand[it] > 0 && have[it] > problem.dayDemand[it] }.toIntArray()
    }

    private fun dayHave(sch: Array<IntArray>): IntArray {
        val h = IntArray(T)
        for (i in 0 until S) for (j in 0 until T) if (sch[i][j] > 0) h[j]++
        return h
    }

    private fun groupShortDays(sch: Array<IntArray>): IntArray {
        val groups = problem.staffGroup()
        val demand = problem.groupDemand()
        val nG = demand.firstOrNull()?.size ?: return intArrayOf()
        val bad = ArrayList<Int>()
        for (d in 0 until T) {
            val have = IntArray(nG)
            for (i in 0 until S) if (sch[i][d] > 0) {
                val g = groups[i]
                if (g in 0 until nG) have[g]++
            }
            if ((0 until nG).any { demand[d][it] > 0 && have[it] < demand[d][it] }) bad.add(d)
        }
        return bad.toIntArray()
    }

    private fun c1ShortDays(sch: Array<IntArray>): IntArray {
        val groups = problem.staffGroup()
        val bad = ArrayList<Int>()
        for (d in 0 until T) {
            if (problem.dayDemand[d] <= 0) continue
            val present = HashSet<Int>()
            for (i in 0 until S) if (sch[i][d] > 0) present.add(groups[i])
            if (present.size < if (S >= 2) 2 else 1) bad.add(d)
        }
        return bad.toIntArray()
    }

    private fun exactBadStaff(sch: Array<IntArray>): IntArray {
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            for (k in 0 until K) {
                val lo = problem.rangeLo[s][k]
                val hi = problem.rangeHi[s][k]
                if (lo == Int.MIN_VALUE || lo != hi) continue
                val c = (0 until T).count { sch[s][it] == k }
                if (c != lo) {
                    bad.add(s); break
                }
            }
        }
        return bad.toIntArray()
    }

    private fun rangeLowStaff(sch: Array<IntArray>): IntArray {
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            for (k in 0 until K) {
                val lo = problem.rangeLo[s][k]
                if (lo == Int.MIN_VALUE || problem.rangeHi[s][k] == lo) continue
                if ((0 until T).count { sch[s][it] == k } < lo) {
                    bad.add(s); break
                }
            }
        }
        return bad.toIntArray()
    }

    private fun rangeHighStaff(sch: Array<IntArray>): IntArray {
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            for (k in 0 until K) {
                val hi = problem.rangeHi[s][k]
                if (hi == Int.MAX_VALUE) continue
                if ((0 until T).count { sch[s][it] == k } > hi) {
                    bad.add(s); break
                }
            }
        }
        return bad.toIntArray()
    }

    private fun rangeBadStaff(sch: Array<IntArray>): IntArray =
        (rangeLowStaff(sch).toSet() + rangeHighStaff(sch).toSet()).toIntArray()

    private fun streakStaff(sch: Array<IntArray>): IntArray {
        val maxRun = problem.constraintConfig().maxConsecutiveWork
        if (maxRun <= 0) return intArrayOf()
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            var run = 0
            for (d in 0 until T) {
                if (sch[s][d] > 0) {
                    run++
                    if (run > maxRun) {
                        bad.add(s); break
                    }
                } else run = 0
            }
        }
        return bad.toIntArray()
    }

    private fun shortRestStaff(sch: Array<IntArray>): IntArray {
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            for (d in 1 until T - 1) {
                if (sch[s][d] == 0 && sch[s][d - 1] > 0 && sch[s][d + 1] > 0) {
                    bad.add(s); break
                }
            }
        }
        return bad.toIntArray()
    }

    private fun weeklyHeavyStaff(sch: Array<IntArray>): IntArray = weeklyUnbalancedStaff(sch)

    /** いずれかのシフト種で曜日 max-min が正のスタッフ */
    private fun weeklyUnbalancedStaff(sch: Array<IntArray>): IntArray {
        val bad = ArrayList<Int>()
        for (s in 0 until S) {
            var unbalanced = false
            for (k in 0 until K) {
                val wd = IntArray(7)
                for (d in 0 until T) if (sch[s][d] == k) wd[d % 7]++
                if ((wd.maxOrNull() ?: 0) - (wd.minOrNull() ?: 0) > 0) {
                    unbalanced = true
                    break
                }
            }
            if (unbalanced) bad.add(s)
        }
        return bad.toIntArray()
    }

    private fun wishCells(sch: Array<IntArray>, lockedOnly: Boolean): IntArray {
        val cells = ArrayList<Int>()
        for (s in 0 until S) for (d in 0 until T) {
            val pref = problem.preferred[s][d]
            if (pref < 0 || sch[s][d] == pref) continue
            val locked = problem.wishLocked(s, d)
            if (lockedOnly && !locked) continue
            if (!lockedOnly && locked) continue
            cells.add((s shl 16) or d)
        }
        return cells.toIntArray()
    }

    private fun illegalCells(sch: Array<IntArray>): IntArray {
        val cells = ArrayList<Int>()
        for (s in 0 until S) for (d in 0 until T) {
            val sh = sch[s][d]
            if (sh !in 0 until K || !problem.canDo(s, sh)) cells.add((s shl 16) or d)
        }
        return cells.toIntArray()
    }

    private fun aptCells(sch: Array<IntArray>): IntArray {
        val skill = problem.skillMatrix()
        val cells = ArrayList<Int>()
        for (s in 0 until S) for (d in 0 until T) {
            val sh = sch[s][d]
            if (sh in 0 until K && !skill[s][sh]) cells.add((s shl 16) or d)
        }
        return cells.toIntArray()
    }

    private fun c3nCells(sch: Array<IntArray>): IntArray {
        val cfg = problem.constraintConfig()
        val night = cfg.nightShiftId
        if (night !in 0 until K) return intArrayOf()
        val forbid = cfg.forbiddenAfterNight
        val cells = ArrayList<Int>()
        for (s in 0 until S) for (d in 0 until T - 1) {
            if (sch[s][d] == night && sch[s][d + 1] in forbid) cells.add((s shl 16) or d)
        }
        return cells.toIntArray()
    }

    private fun staffPerm(): IntArray {
        val a = IntArray(S) { it }
        for (i in S - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = a[i]; a[i] = a[j]; a[j] = tmp
        }
        return a
    }


    private fun genExactCascade(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else exactBadStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        for (k in 0 until K) {
            val lo = problem.rangeLo[s][k]
            val hi = problem.rangeHi[s][k]
            if (lo == Int.MIN_VALUE || lo != hi) continue
            val c = (0 until T).count { session.current[s][it] == k }
            if (c == lo) continue
            val d = (0 until T).firstOrNull { !problem.wishLocked(s, it) } ?: continue
            val want = if (c < lo) k else 0
            return struct.cascadeWant(session, s, d, want) ?: continue
        }
        return null
    }

    private fun genStreakCascade(session: SearchSessionFull, focus: Residual): Move? {
        val staffs = if (focus.staff.isNotEmpty()) focus.staff else streakStaff(session.current)
        if (staffs.isEmpty()) return null
        val s = staffs[rng.nextInt(staffs.size)]
        val maxRun = problem.constraintConfig().maxConsecutiveWork
        var run = 0
        for (d in 0 until T) {
            if (session.current[s][d] > 0) {
                run++
                if (run > maxRun && !problem.wishLocked(s, d)) {
                    // 玉突きで他スタッフへ勤務を渡す
                    return struct.cascadeWant(session, s, d, 0)
                        ?: struct.staffDayRotate(session, s, 3)
                }
            } else run = 0
        }
        return null
    }

    private fun genBalanceCascade(session: SearchSessionFull, @Suppress("UNUSED_PARAMETER") focus: Residual): Move? {
        val work = IntArray(S) { s -> (0 until T).count { session.current[s][it] > 0 } }
        val hi = work.indices.maxByOrNull { work[it] } ?: return null
        val lo = work.indices.minByOrNull { work[it] } ?: return null
        if (work[hi] <= work[lo] + 1) return null
        for (d in 0 until T) {
            if (session.current[hi][d] > 0 && session.current[lo][d] == 0) {
                val sh = session.current[hi][d]
                return struct.cascadeWant(session, lo, d, sh)
                    ?: StructuralMoves.staffChainOnDay(session, problem, d, intArrayOf(hi, lo), newTail = sh)
            }
        }
        return struct.randomRect(session)
    }

    private fun tryApplyGenerators(
        session: SearchSessionFull,
        generators: List<() -> Move?>,
        key: String,
        deadlineMs: Long,
        maxTries: Int = 48,
    ): Int {
        var accepts = 0
        var tries = 0
        val failedFp = HashSet<Long>()
        while (tries++ < maxTries && System.currentTimeMillis() < deadlineMs) {
            val residual = session.currentReport.breakdown[key] ?: 0
            if (residual <= 0) break
            var applied = false
            for (gen in generators) {
                if (System.currentTimeMillis() >= deadlineMs) break
                val move = gen() ?: continue
                if (move.baseVersion != session.version) continue
                val fp = fingerprint(move)
                if (fp in failedFp) continue
                when (session.tryTransition(move, TransitionMode.STRICT)) {
                    is TransitionResult.Rejected -> failedFp.add(fp)
                    else -> {
                        accepts++
                        applied = true
                        failedFp.clear()
                        break
                    }
                }
            }
            if (!applied) break
        }
        return accepts
    }

    private fun fingerprint(m: Move): Long {
        var h = m.family.hashCode().toLong()
        for (x in m.writes) h = h * 1315423911L + x
        return h
    }
}
