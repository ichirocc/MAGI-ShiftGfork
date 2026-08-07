package com.magi.app.v6.engine

import com.magi.app.v6.engine.nativex.GlobalNativeSkipGate

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
        // 並列時は G1 を抑え G2(RSI/ALNS) に時間を残す（後段空振り対策）
        val g1Ratio = if (workers > 1) 0.40 else 0.50
        val g1Ms = (searchBudget * g1Ratio).toLong().coerceAtLeast(1L)
        val g2Ms = (searchBudget - g1Ms).coerceAtLeast(1L)
        android.util.Log.i("MAGI", "STAGE budget g1Ms=$g1Ms g2Ms=$g2Ms ratio=$g1Ratio workers=$workers")
        fun stopSearch() = shouldStop() || System.currentTimeMillis() >= searchDl

        // G1: workers==1 → 単一 Session（再現性）。workers>=2 → 独立並列 SA → best を本 session に吸収
        var g1Iters = 0L
        if (workers <= 1) {
            G1LocalAnnealer(problem, session, packedScore).run(
                G1Params(
                    budgetMs = g1Ms,
                    shouldStop = { stopSearch() },
                    nativeProbe = nativeProbe,
                    maxIters = fixedItersG1,
                    onProgressTick = { it, rep ->
                        g1Iters = it
                        progressListener?.onProgress(
                            SearchProgress(
                                phase = "g1",
                                report = rep,
                                iters = it,
                                elapsedMs = System.currentTimeMillis() - started,
                                schedule = null, // 高頻度なので盤面は載せない
                            ),
                        )
                    },
                ),
                rng,
            )
        } else {
            android.util.Log.i("MAGI", "STAGE g1-parallel-enter workers=$workers g1Ms=$g1Ms")
            val par = ParallelSaCoordinator(problem, evaluate, better, deltaHook).run(
                initial = session.current,
                workers = workers,
                budgetMs = g1Ms,
                baseSeed = if (baseSeed != 0L) baseSeed else rng.nextLong(),
                shouldStop = { stopSearch() },
                allowNative = true,
                onProgress = { it, rep ->
                    g1Iters = it
                    runCatching {
                        progressListener?.onProgress(
                            SearchProgress(
                                phase = "g1-parallel",
                                report = rep,
                                iters = it,
                                elapsedMs = System.currentTimeMillis() - started,
                                schedule = null,
                            ),
                        )
                    }
                },
            )
            g1Iters = par.totalIters
            android.util.Log.i("MAGI", "STAGE g1-parallel-exit iters=$g1Iters hard=${par.report.hard}")
            session.replaceBestIfBetter(par.schedule, par.report)
            // 並列は Kotlin のみ。マージ後に単一スレッドで native 加速（安全）
            if (nativeProbe != null && !stopSearch()) {
                val refineMs = minOf(8_000L, (g1Ms / 8L).coerceAtLeast(2_000L))
                android.util.Log.i("MAGI", "STAGE g1-native-refine-enter ms=$refineMs")
                val refined = G1LocalAnnealer(problem, session, packedScore).run(
                    G1Params(
                        budgetMs = refineMs,
                        shouldStop = { stopSearch() },
                        nativeProbe = nativeProbe,
                        earlyRejectHardIncrease = true,
                        earlyRejectColdWorse = false,
                    ),
                    rng,
                )
                g1Iters += refined
                android.util.Log.i("MAGI", "STAGE g1-native-refine-exit iters=$g1Iters hard=${session.bestReport.hard}")
                progressListener?.onProgress(
                    SearchProgress(
                        phase = "g1-native-refine",
                        report = session.bestReport,
                        iters = g1Iters,
                        elapsedMs = System.currentTimeMillis() - started,
                        schedule = null,
                    ),
                )
            }
        }
        g4.considerStrict(session.best, session.bestReport)
        emit("g1", iters = g1Iters)

                // RSI（フォーク元分解）: 最大違反族フォーカス
        val rsiMs = (g2Ms * 0.55).toLong().coerceAtLeast(1L)
        val alnsMs = (g2Ms - rsiMs).coerceAtLeast(1L)
        if (skipPostProcess) {
            android.util.Log.i("MAGI_DELTA", GlobalNativeSkipGate.gate.stats())
        return session.snapshotBest(stopReason = StopReason.FIXED_POINT)
        }
        android.util.Log.i("MAGI", "STAGE rsi-enter budgetMs=$rsiMs hard=${session.bestReport.hard}")
        val rsiIters = SimpleRsi(problem, session, fixProvider).run(
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
        android.util.Log.i("MAGI", "STAGE rsi-exit iters=$rsiIters hard=${session.bestReport.hard}")
        emit("rsi", iters = rsiIters)

        // ALNS + VNS（フォーク元分解・論文近傍）
        if (!stopSearch() && alnsMs > 0L) {
            val alnsDeadline = System.currentTimeMillis() + alnsMs
            android.util.Log.i("MAGI", "STAGE alns-enter deadlineIn=${alnsMs}ms")
            val alnsAccepts = AlnsPolish(problem, rng).run(session, alnsDeadline).toLong()
            g4.considerStrict(session.best, session.bestReport)
            android.util.Log.i("MAGI", "STAGE alns-exit accepts=$alnsAccepts hard=${session.bestReport.hard}")
            emit("alns", iters = alnsAccepts)
            if (!stopSearch() && System.currentTimeMillis() < alnsDeadline) {
                android.util.Log.i("MAGI", "STAGE vns-enter")
                val vnsAccepts = VnsPolish(problem, rng).run(session, alnsDeadline).toLong()
                g4.considerStrict(session.best, session.bestReport)
                android.util.Log.i("MAGI", "STAGE vns-exit accepts=$vnsAccepts hard=${session.bestReport.hard}")
                emit("vns", iters = vnsAccepts)
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

        emit("done", iters = g1Iters) // 主要探索量の目安（G1）。詳細は phase 行を参照
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
        android.util.Log.i("MAGI_DELTA", GlobalNativeSkipGate.gate.stats())
        return session.snapshotBest(alternatives = alts, stopReason = reason)
    }

    
}

private fun Array<IntArray>.contentDeepEquals(other: Array<IntArray>): Boolean {
    if (size != other.size) return false
    for (i in indices) if (!this[i].contentEquals(other[i])) return false
    return true
}
