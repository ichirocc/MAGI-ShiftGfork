package com.magi.app.v6.engine

// WeightAuditLog in same package


import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import com.magi.app.v6.engine.parallel.ParallelSaCoordinator
import java.util.Random
import com.magi.app.v6.engine.OptimizeBenchLog

data class ScheduleProfile(
    val totalBudgetMs: Long = 300_000L,
    val postReserveMs: Long = 25_000L,
) {
    fun searchDeadline(started: Long): Long =
        started + (totalBudgetMs - postReserveMs).coerceAtLeast(1L)
    fun postDeadline(started: Long): Long = started + totalBudgetMs
}

class SchedulerService(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val packedScore: (ViolationReport) -> Long = PackedScore::of,
) {
    fun optimize(
        initial: Array<IntArray>,
        profile: ScheduleProfile = ScheduleProfile(),
        rng: Random = Random(0L),
        shouldStop: () -> Boolean = { false },
        infeasible: Set<String> = emptySet(),
        g3Backend: G3Backend = FullyWiredG3Backend(problem, evaluate, better, rng),
        fixProvider: FocusFixProvider = FocusAwareFixProvider(problem),
        deltaHook: DeltaEvaluateHook? = null,
        workers: Int = 1,
        baseSeed: Long = 0L,
        nativeProbe: com.magi.app.v6.engine.nativex.NativeWritesProbe? = null,
        progressListener: SearchProgressListener? = null,
        fixedItersG1: Long = 0L,
        fixedItersG2: Long = 0L,
        skipPostProcess: Boolean = false,
    ): RunArtifacts {
        val started = System.currentTimeMillis()
        val searchDl = profile.searchDeadline(started)
        val postDl = profile.postDeadline(started)
        var session = SearchSessionFull(problem, initial, evaluate, better, deltaHook = deltaHook)
        val g4 = G4Diversify(better)
        fun emit(phase: String, iters: Long = 0L, withSchedule: Boolean = true) {
            android.util.Log.i("MAGI", "探索フェーズ: rebuild-$phase 必須=${session.bestReport.hard} 合計=${session.bestReport.total}")

            val elapsed = System.currentTimeMillis() - started
            WeightAuditLog.logContribution(phase, session.bestReport)
            OptimizeBenchLog.phase(
                engine = OptimizeBenchLog.ENGINE_REBUILD,
                phase = phase,
                report = session.bestReport,
                elapsedMs = elapsed,
                iters = iters,
                seed = baseSeed,
                workers = workers,
                budgetMs = profile.totalBudgetMs,
            )
            progressListener?.onProgress(
                SearchProgress(
                    phase = phase,
                    report = session.bestReport,
                    iters = iters,
                    elapsedMs = elapsed,
                    schedule = if (withSchedule) SearchSessionFull.deepCopy(session.best) else null,
                ),
            )
        }
        emit("start")

        val searchBudget = (searchDl - System.currentTimeMillis()).coerceAtLeast(1L)
        val g1Ms = (searchBudget * 0.55).toLong().coerceAtLeast(1L)
        val g2Ms = (searchBudget - g1Ms).coerceAtLeast(1L)
        fun stopSearch() = shouldStop() || System.currentTimeMillis() >= searchDl

        // G1: workers==1 → 単一 Session（再現性）。workers>=2 → 独立並列 SA → best を本 session に吸収
        if (workers <= 1) {
            G1LocalAnnealer(problem, session, packedScore).run(
                G1Params(budgetMs = g1Ms, shouldStop = { stopSearch() }, nativeProbe = nativeProbe, maxIters = fixedItersG1),
                rng,
            )
        } else {
            val par = ParallelSaCoordinator(problem, evaluate, better, deltaHook).run(
                initial = session.current,
                workers = workers,
                budgetMs = g1Ms,
                baseSeed = if (baseSeed != 0L) baseSeed else rng.nextLong(),
                shouldStop = { stopSearch() },
            )
            session.replaceBestIfBetter(par.schedule, par.report)
        }
        g4.considerStrict(session.best, session.bestReport)
        emit("g1")

                // RSI（フォーク元分解）: 最大違反族フォーカス
        val rsiMs = (g2Ms * 0.55).toLong().coerceAtLeast(1L)
        val alnsMs = (g2Ms - rsiMs).coerceAtLeast(1L)
        if (skipPostProcess) {
            return session.snapshotBest(stopReason = StopReason.FIXED_POINT)
        }
        SimpleRsi(problem, session, fixProvider).run(
            SimpleRsi.Params(
                budgetMs = rsiMs,
                shouldStop = { stopSearch() },
                infeasible = infeasible,
                nativeProbe = nativeProbe,
                maxIters = fixedItersG2,
            ),
            rng,
        )
        g4.considerStrict(session.best, session.bestReport)
        emit("rsi")

        // ALNS + VNS（フォーク元分解・論文近傍）
        if (!stopSearch() && alnsMs > 0L) {
            val alnsDeadline = System.currentTimeMillis() + alnsMs
            AlnsPolish(problem, rng).run(session, alnsDeadline)
            g4.considerStrict(session.best, session.bestReport)
            emit("alns")
            if (!stopSearch() && System.currentTimeMillis() < alnsDeadline) {
                VnsPolish(problem, rng).run(session, alnsDeadline)
                g4.considerStrict(session.best, session.bestReport)
                emit("vns")
            }
        }


        val (sessionAfter, stopG3) = G3FamilyPolish(g3Backend, problem, rng).run(
            session,
            G3Params(
                deadlineMs = postDl,
                shouldStop = { shouldStop() || System.currentTimeMillis() >= postDl },
                absorb = { s, result -> absorbG3Step(s, result) },
            ),
        )
        session = sessionAfter
        g4.considerStrict(session.best, session.bestReport)
        emit("g3")

        // Path Relinking: elite 間を STRICT で繋ぐ（論文 Glover/Laguna）
        if (!shouldStop() && System.currentTimeMillis() < postDl) {
            val elites = g4.outputCandidatesSorted()
            if (elites.size >= 2) {
                PathRelinker(problem, better, evaluate, rng).relinkElites(
                    session, elites, postDl, maxPairs = 8,
                )
                g4.considerStrict(session.best, session.bestReport)
            }
            emit("g4")
        }

        emit("done")
        val reason = when {
            shouldStop() -> StopReason.CANCELLED
            stopG3 == StopReason.FIXED_POINT -> StopReason.FIXED_POINT
            else -> StopReason.DEADLINE
        }
        val alts = ReportOrdering.sortedByReport(
            g4.outputCandidatesSorted().map { it.schedule to it.report },
            reportOf = { it.second },
            better = better,
        ).map { it.first }.filter { !it.contentDeepEquals(session.best) }.take(3)
        return session.snapshotBest(alternatives = alts, stopReason = reason)
    }

    
}

private fun Array<IntArray>.contentDeepEquals(other: Array<IntArray>): Boolean {
    if (size != other.size) return false
    for (i in indices) if (!this[i].contentEquals(other[i])) return false
    return true
}
