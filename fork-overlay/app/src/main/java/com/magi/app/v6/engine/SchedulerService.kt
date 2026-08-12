package com.magi.app.v6.engine

import com.magi.app.v6.engine.nativex.GlobalNativeSkipGate

// WeightAuditLog in same package


import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.shiftDemand
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
        /** 探索不能と証明された HARD 下限（UnimprovableConstraints.provenHardUnits） */
        provenHardFloor: Int = 0,
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
        fun emit(phase: String, iters: Long = 0L, withSchedule: Boolean = true, note: String = "") {
            val bd = session.bestReport.breakdown
            val hardBits = listOf("covU", "covO", "c3n", "groupViol", "pref").joinToString(",") { k ->
                "$k=${bd[k] ?: 0}"
            }
            val softBits = listOf("weekly", "c3", "c3m", "apt", "low").joinToString(",") { k ->
                "$k=${bd[k] ?: 0}"
            }
            val detail = note.ifEmpty { "hard{$hardBits} soft{$softBits}" }
            android.util.Log.i(
                "MAGI",
                "探索フェーズ: rebuild-$phase 必須=${session.bestReport.hard} soft=${session.bestReport.soft} 合計=${session.bestReport.total} iters=$iters $detail",
            )

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
                note = detail,
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

        // G1: 本番は常に単一 SearchSessionFull。
        // ParallelSaCoordinator は Session×N + elite deepCopy で実機 LMK するため無効化。
        // workers は将来の「複数シード逐次再スタート」用に残し、並列 Session は作らない。
        var g1Iters = 0L
        if (workers > 1) {
            android.util.Log.w(
                "MAGI",
                "STAGE g1-serial-forced requestedWorkers=$workers reason=avoid-Session-xN-OOM",
            )
        }
        android.util.Log.i("MAGI", "STAGE g1-serial-enter g1Ms=$g1Ms")
        G1LocalAnnealer(problem, session, packedScore).run(
            G1Params(
                budgetMs = g1Ms,
                shouldStop = { stopSearch() },
                nativeProbe = nativeProbe,
                maxIters = fixedItersG1,
                progressEveryMs = 4_000L,
                onProgressTick = { it, rep ->
                    g1Iters = it
                    progressListener?.onProgress(
                        SearchProgress(
                            phase = "g1",
                            report = rep,
                            iters = it,
                            elapsedMs = System.currentTimeMillis() - started,
                            schedule = null,
                        ),
                    )
                },
            ),
            rng,
        )
        android.util.Log.i(
            "MAGI",
            "STAGE g1-serial-exit iters=$g1Iters hard=${session.bestReport.hard} soft=${session.bestReport.soft}",
        )
        g4.considerStrict(session.best, session.bestReport)
        emit("g1", iters = g1Iters)

                // RSI（フォーク元分解）: 最大違反族フォーカス
        // HARD が残っている間は RSI に厚く配分（後段空振り対策）
        val hardAfterG1 = session.bestReport.hard
        // 主判定: 残 HARD 族がすべて infeasible(exclude) か（数値 floor は参考のみ）
        val residualHardFamilies = UnimprovableConstraints.HARD_FAMILY_KEYS.filter { k ->
            val key = when (k) {
                "shiftU" -> "covU"
                "wish" -> "pref"
                else -> k
            }
            (session.bestReport.breakdown[key] ?: session.bestReport.breakdown[k] ?: 0) > 0
        }.map {
            when (it) {
                "shiftU" -> "covU"
                "wish" -> "pref"
                else -> it
            }
        }.toSet()
        val allHardExcluded = hardAfterG1 > 0 &&
            residualHardFamilies.isNotEmpty() &&
            residualHardFamilies.all { it in infeasible }
        android.util.Log.i(
            "MAGI",
            "STAGE hard-floor afterG1 hard=$hardAfterG1 provenFloor=$provenHardFloor " +
                "residualFamilies=$residualHardFamilies exclude=$infeasible allExcluded=$allHardExcluded",
        )
        // 残 HARD がすべて exclude のときだけ RSI 短縮（不能を掘らない）
        val rsiShare = when {
            hardAfterG1 <= 0 -> 0.50
            allHardExcluded -> 0.25
            else -> 0.72
        }
        val rsiMs = (g2Ms * rsiShare).toLong().coerceAtLeast(1L)
        val alnsMs = (g2Ms - rsiMs).coerceAtLeast(1L)
        android.util.Log.i("MAGI", "STAGE post-g1 hard=$hardAfterG1 g2Ms=$g2Ms rsiMs=$rsiMs alnsMs=$alnsMs share=$rsiShare")
        if (skipPostProcess) {
            android.util.Log.i("MAGI_DELTA", GlobalNativeSkipGate.gate.stats())
        return session.snapshotBest(stopReason = StopReason.FIXED_POINT)
        }
        val hasSd = try { problem.shiftDemand != null } catch (_: Throwable) { false }
        android.util.Log.i(
            "MAGI",
            "STAGE rsi-enter budgetMs=$rsiMs hard=${session.bestReport.hard} shiftDemand=$hasSd native=${nativeProbe != null} infeasible=$infeasible",
        )
        CoverageFocusQueue.rebuild(problem, session.best)
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



        // 前後左右・過去未来 + 高速制約研磨（HARD 残差をさらに削る）
        if (!stopSearch()) {
            val polishMs = minOf(8_000L, (postDl - System.currentTimeMillis()).coerceAtLeast(0L))
            if (polishMs > 500L) {
                val polishDl = System.currentTimeMillis() + polishMs
                android.util.Log.i("MAGI", "STAGE dir-polish-enter ms=$polishMs hard=${session.bestReport.hard}")
                var dirAcc = 0
                var guard = 0
                while (System.currentTimeMillis() < polishDl && !stopSearch() && guard++ < 400) {
                    val mv = DirectionalPolish.proposeAny(session, problem, rng) ?: break
                    if (mv.baseVersion != session.version) continue
                    val r = session.tryTransition(mv, TransitionMode.STRICT)
                    if (r is TransitionResult.AcceptedBest || r is TransitionResult.AcceptedCurrent) dirAcc++
                }
                val fastAcc = runCatching {
                    FastConstraintPolish(problem, rng).polishAll(session, polishDl)
                }.getOrDefault(0)
                g4.considerStrict(session.best, session.bestReport)
                android.util.Log.i("MAGI", "STAGE dir-polish-exit dirAcc=$dirAcc fastAcc=$fastAcc hard=${session.bestReport.hard}")
                emit("dir-polish", iters = (dirAcc + fastAcc).toLong())
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
