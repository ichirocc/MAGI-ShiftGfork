package com.magi.app.v6.engine.parallel

import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.DeltaEvaluateHook
import com.magi.app.v6.engine.G1LocalAnnealer
import com.magi.app.v6.engine.G1Params
import com.magi.app.v6.engine.SearchSessionFull
import com.magi.app.v6.engine.StopReason
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun logI(msg: String) = println("[MAGI] $msg")
private fun logW(msg: String) = System.err.println("[MAGI][W] $msg")
private fun logE(msg: String, t: Throwable? = null) {
    System.err.println("[MAGI][E] $msg"); t?.printStackTrace()
}

/**
 * 並列 SA（g1-parallel 完成・安定版）
 *
 * ## なぜ並列内で native を使わないか（クラッシュの根）
 * JNI [NativeEval.createHandle] / delta を複数スレッドから同時に呼ぶと、
 * C++ 側の非スレッドセーフ初期化やグローバルとぶつかり SIGSEGV する実害があった。
 *
 * ## 並列の正しい役割分担
 * - **並列フェーズ（ここ）**: Kotlin のみ。ワーカー独立 Session で探索を並列化
 * - **単一スレッドフェーズ**: マージ後にメイン Session で native 加速（Scheduler 側）
 *
 * 共有は Elite CAS と原子カウンタのみ。評価は盤面コピー＋短時間ロック。
 */
data class ParallelSaResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val workerBestReports: List<ViolationReport>,
    val totalIters: Long,
    val stopReason: StopReason,
)

data class Elite(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
)

class ParallelSaCoordinator(
    private val problem: Problem,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    @Suppress("UNUSED_PARAMETER")
    private val deltaHook: DeltaEvaluateHook? = null,
) {
    private val evalLock = Any()

    private fun evalSnapshot(schedule: Array<IntArray>): ViolationReport {
        val snap = SearchSessionFull.deepCopy(schedule)
        synchronized(evalLock) {
            return evaluate(snap)
        }
    }

    fun run(
        initial: Array<IntArray>,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        budgetMs: Long = 120_000L,
        baseSeed: Long = 1L,
        shouldStop: () -> Boolean = { false },
        onProgress: ((iters: Long, best: ViolationReport) -> Unit)? = null,
        @Suppress("UNUSED_PARAMETER")
        allowNative: Boolean = false, // 互換のため残す。並列内 native は常に無効
    ): ParallelSaResult {
        val n = workers.coerceIn(1, MAX_PARALLEL)
        val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(1L)
        val stop = AtomicBoolean(false)
        val sharedIters = AtomicLong(0L)
        val initialCopy = SearchSessionFull.deepCopy(initial)
        val elite = AtomicReference(
            Elite(SearchSessionFull.deepCopy(initialCopy), evalSnapshot(initialCopy)),
        )

        logI("ParallelSa start workers=$n budgetMs=$budgetMs mode=kotlin-only (native after merge)",
        )

        if (n == 1) {
            val (iters, rep) = runWorker(0, baseSeed, initialCopy, deadline, stop, shouldStop, elite, sharedIters)
            runCatching { onProgress?.invoke(iters, elite.get().report) }
            val e = elite.get()
            return ParallelSaResult(e.schedule, e.report, listOf(rep), iters,
                if (shouldStop()) StopReason.CANCELLED else StopReason.DEADLINE)
        }

        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map { w ->
                val seed = baseSeed xor (w.toLong() * -0x61c8864680b583ebL)
                pool.submit(Callable {
                    runWorker(w, seed, initialCopy, deadline, stop, shouldStop, elite, sharedIters)
                })
            }
            while (futures.any { !it.isDone }) {
                if (shouldStop()) {
                    stop.set(true)
                    break
                }
                runCatching { onProgress?.invoke(sharedIters.get(), elite.get().report) }
                try {
                    Thread.sleep(PROGRESS_MS)
                } catch (_: InterruptedException) {
                    stop.set(true)
                    Thread.currentThread().interrupt()
                    break
                }
            }
            stop.set(true)
            var totalIters = 0L
            val reports = ArrayList<ViolationReport>(n)
            for (f in futures) {
                val waitMs = (deadline - System.currentTimeMillis() + JOIN_SLACK_MS).coerceAtLeast(5_000L)
                val pair = runCatching {
                    f.get(waitMs, TimeUnit.MILLISECONDS)
                }.getOrElse {
                    logW("ParallelSa join: ${it.javaClass.simpleName}")
                    0L to elite.get().report
                }
                totalIters += pair.first
                reports += pair.second
            }
            val e = elite.get()
            val finalIters = totalIters.coerceAtLeast(sharedIters.get())
            runCatching { onProgress?.invoke(finalIters, e.report) }
            logI("ParallelSa done workers=$n iters=$finalIters hard=${e.report.hard} total=${e.report.total}",
            )
            return ParallelSaResult(
                schedule = e.schedule,
                report = e.report,
                workerBestReports = reports,
                totalIters = finalIters,
                stopReason = if (shouldStop()) StopReason.CANCELLED else StopReason.DEADLINE,
            )
        } finally {
            stop.set(true)
            pool.shutdownNow()
            runCatching { pool.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }

    private fun runWorker(
        workerId: Int,
        seed: Long,
        initial: Array<IntArray>,
        deadline: Long,
        stop: AtomicBoolean,
        shouldStop: () -> Boolean,
        elite: AtomicReference<Elite>,
        sharedIters: AtomicLong,
    ): Pair<Long, ViolationReport> {
        return try {
            val rng = Random(seed)
            val session = SearchSessionFull(
                problem,
                SearchSessionFull.deepCopy(initial),
                ::evalSnapshot,
                better,
                deltaHook = null,
            )
            val g1 = G1LocalAnnealer(problem, session)
            var totalIters = 0L
            while (!stop.get() && !shouldStop() && System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) break
                val iters = g1.run(
                    G1Params(
                        budgetMs = minOf(SLICE_MS, remain),
                        shouldStop = { stop.get() || shouldStop() },
                        nativeProbe = null,
                        earlyRejectHardIncrease = false,
                        earlyRejectColdWorse = false,
                    ),
                    rng,
                )
                totalIters += iters
                sharedIters.addAndGet(iters)
                publishElite(elite, session.best, session.bestReport)
            }
            totalIters to session.bestReport
        } catch (t: Throwable) {
            logE("ParallelSa worker-$workerId ${t.javaClass.simpleName}: ${t.message}", t)
            0L to elite.get().report
        }
    }

    private fun publishElite(
        elite: AtomicReference<Elite>,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ) {
        while (true) {
            val cur = elite.get()
            if (!better(report, cur.report)) return
            val next = Elite(SearchSessionFull.deepCopy(schedule), report)
            if (elite.compareAndSet(cur, next)) return
        }
    }

    companion object {
        const val MAX_PARALLEL = 8
        private const val SLICE_MS = 2_500L
        private const val PROGRESS_MS = 1_500L
        private const val JOIN_SLACK_MS = 25_000L
    }
}
