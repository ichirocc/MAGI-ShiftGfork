package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import com.magi.app.v6.engine.nativex.NativeWritesProbe
import com.magi.app.v6.engine.nativex.nativePrefilter
import java.util.Random

/** G1 パラメータ。温度は TemperatureParams のみ。 */
data class G1Params(
    val budgetMs: Long = 30_000L,
    val temperature: TemperatureParams = TemperatureParams(),
    val lahcLen: Int = 32,
    /** 再加熱時に小さな perturb を挟む */
    val reheatPerturbCells: Int = 4,
    val shouldStop: () -> Boolean = { false },
    /** 任意: Native tryWrites プローブ（番兵は Session） */
    val nativeProbe: NativeWritesProbe? = null,
)

/**
 * G1: SA（完成温度管理）→ hard==0 で LAHC。
 * 受理は必ず tryMetropolis / tryLahc（version は却下時不変）。
 */
class G1LocalAnnealer(
    private val problem: Problem,
    private val session: SearchSessionFull,
    private val packedScore: (ViolationReport) -> Long = PackedScore::of,
) {
    private var struct: StructuralMoveFactory? = null

    var lastController: TemperatureController? = null
        private set

    fun run(params: G1Params, rng: Random): Long {
        val start = System.nanoTime() / 1_000_000L
        val tp = params.temperature
        struct = StructuralMoveFactory(problem, rng)
        val ctrl = TemperatureController(tp, packedScore(session.bestReport))
        ctrl.bootstrap()
        lastController = ctrl

        val lahc = LahcHistory(params.lahcLen, packedScore(session.currentReport))
        var iters = 0L
        var lastReheatSeen = 0
        var nativeHints = 0L

        fun timeUp() =
            params.shouldStop() || (System.nanoTime() / 1_000_000L) - start >= params.budgetMs

        while (!timeUp()) {
            iters++
            val move = propose(rng) ?: continue
            val bestBefore = packedScore(session.bestReport)

            when (ctrl.phase) {
                SearchPhase.ANNEAL -> {
                    if (params.nativeProbe != null) {
                        val pre = session.nativePrefilter(
                            move, TransitionMode.ANNEAL, params.nativeProbe, ctrl.temperature,
                        )
                        if (pre.status > 0) nativeHints++
                    }
                    val r = session.tryMetropolis(move, ctrl.temperature, rng, packedScore)
                    val accepted = r !is TransitionResult.Rejected
                    ctrl.onTrial(
                        accepted = accepted,
                        bestScore = packedScore(session.bestReport),
                        bestHard = session.bestReport.hard,
                    )
                    // 再加熱が起きたら current を軽く撹拌（best は Session が守る）
                    if (ctrl.reheatCountValue > lastReheatSeen) {
                        lastReheatSeen = ctrl.reheatCountValue
                        if (params.reheatPerturbCells > 0) {
                            perturb(rng, params.reheatPerturbCells)
                        }
                    }
                    if (ctrl.phase == SearchPhase.LAHC) {
                        lahc.reset(packedScore(session.currentReport))
                    }
                }
                SearchPhase.LAHC -> {
                    val r = session.tryLahc(move, lahc.threshold(), packedScore)
                    if (r !is TransitionResult.Rejected) {
                        lahc.onAccept(packedScore(session.currentReport))
                    }
                    // LAHC 中も停滞が長ければ一度だけ再加熱して SA に戻す
                    ctrl.onTrial(
                        accepted = r !is TransitionResult.Rejected,
                        bestScore = packedScore(session.bestReport),
                        bestHard = session.bestReport.hard,
                    )
                    if (ctrl.phase == SearchPhase.LAHC &&
                        packedScore(session.bestReport) >= bestBefore &&
                        ctrl.iterations > 0 &&
                        ctrl.iterations % (tp.stagnateIters * 2) == 0L
                    ) {
                        ctrl.forceAnnealing()
                        ctrl.reheat(tp.reheatFactor * 0.75)
                        lastReheatSeen = ctrl.reheatCountValue
                    }
                }
            }
        }
        return iters
    }

    /** 停滞脱出: 高温 Metropolis で current を動かす。best は better のときだけ。 */
    fun perturb(rng: Random, cells: Int = 4 + rng.nextInt(8)) {
        val hot = 1e9
        val st = struct ?: StructuralMoveFactory(problem, rng)
        st.anyStructural(session)?.let {
            session.tryMetropolis(it, hot, rng, packedScore)
        }
        PaperNeighborhoods.ruinRecreateDay(session, problem, rng.nextInt(problem.T), 2, rng)
            ?.let { session.tryMetropolis(it, hot, rng, packedScore) }
        repeat(cells) {
            val s = rng.nextInt(problem.S)
            var d = rng.nextInt(problem.T)
            var tries = 0
            while (problem.wishLocked(s, d) && tries++ < 4) d = rng.nextInt(problem.T)
            if (problem.wishLocked(s, d)) return@repeat
            val allowed = problem.allowedShiftsForStaff(s)
            if (allowed.isEmpty()) return@repeat
            val m = buildSingleMove(
                session, problem, s, d, allowed[rng.nextInt(allowed.size)], "g1.perturb",
            ) ?: return@repeat
            session.tryMetropolis(m, hot, rng, packedScore)
        }
    }

    private fun propose(rng: Random): Move? {
        // HARD 残存時は C1 日次一括を優先（評価1回で複数セル＝高速＋高精度）
        if (session.currentReport.hard > 0 && rng.nextInt(100) < 35) {
            val d = rng.nextInt(problem.T)
            val need = problem.dayDemand[d]
            C1PrecisionMoves.fillCoverageDay(session, problem, d, need, rng)?.let { return it }
            C1PrecisionMoves.trimCoverageDay(session, problem, d, need, rng)?.let { return it }
            C1PrecisionMoves.fillGroupDay(session, problem, d, rng)?.let { return it }
        }
        return when (rng.nextInt(100)) {
            in 0 until 50 -> proposeSingle(rng)
            in 50 until 68 -> proposeSwap(rng)
            in 68 until 78 -> proposeWindow(rng)
            in 78 until 86 -> proposeDayLns(rng)
            in 86 until 93 -> {
                if (problem.S < 2) proposeSingle(rng)
                else {
                    val s1 = rng.nextInt(problem.S)
                    var s2 = rng.nextInt(problem.S)
                    if (s1 == s2) s2 = (s2 + 1) % problem.S
                    PaperNeighborhoods.verticalSwap(session, problem, rng.nextInt(problem.T), s1, s2)
                        ?: proposeSingle(rng)
                }
            }
            else -> struct?.anyStructural(session) ?: proposeSingle(rng)
        }
    }

    private fun proposeSingle(rng: Random): Move? {
        val s = rng.nextInt(problem.S)
        var d = rng.nextInt(problem.T)
        var tries = 0
        while (problem.wishLocked(s, d) && tries++ < 4) d = rng.nextInt(problem.T)
        if (problem.wishLocked(s, d)) return null
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) return null
        return buildSingleMove(session, problem, s, d, allowed[rng.nextInt(allowed.size)], "g1.single")
    }

    private fun proposeSwap(rng: Random): Move? {
        if (problem.T < 2) return null
        val s = rng.nextInt(problem.S)
        val d1 = rng.nextInt(problem.T)
        var d2 = rng.nextInt(problem.T)
        if (d1 == d2) d2 = (d2 + 1) % problem.T
        return buildSwapDaysMove(session, problem, s, d1, d2, "g1.swap2")
    }

    private fun proposeWindow(rng: Random): Move? {
        val s = rng.nextInt(problem.S)
        val len = 2 + rng.nextInt(3)
        if (problem.T < len) return null
        val start = rng.nextInt(problem.T - len + 1)
        val allowed = problem.allowedShiftsForStaff(s)
        if (allowed.isEmpty()) return null
        return buildWindowFillMove(
            session, problem, s, start, len, allowed[rng.nextInt(allowed.size)], "g1.window",
        )
    }

    private fun proposeDayLns(rng: Random): Move? {
        val day = rng.nextInt(problem.T)
        val n = 2 + rng.nextInt(minOf(4, problem.S.coerceAtLeast(2)))
        val buf = IntArray(n * 2)
        var k = 0
        var tries = 0
        while (k < n * 2 && tries++ < n * 6) {
            val s = rng.nextInt(problem.S)
            if (problem.wishLocked(s, day)) continue
            val allowed = problem.allowedShiftsForStaff(s)
            if (allowed.isEmpty()) continue
            buf[k++] = s
            buf[k++] = allowed[rng.nextInt(allowed.size)]
        }
        if (k < 4) return null
        return buildDayLnsMove(session, problem, day, buf.copyOf(k), "g1.day_lns")
    }
}
