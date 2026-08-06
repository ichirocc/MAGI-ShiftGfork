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

/**
 * 並列 SA: ワーカー独立 Session、共有は betterReport による Elite CAS のみ。
 * Native handle は共有しない。進捗は [onProgress]（約1.5秒）で UI へ。
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
    private val deltaHook: DeltaEvaluateHook? = null,
) {
    fun run(
        initial: Array<IntArray>,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        budgetMs: Long = 120_000L,
        baseSeed: Long = 1L,
        shouldStop: () -> Boolean = { false },
        onProgress: ((iters: Long, best: ViolationReport) -> Unit)? = null,
    ): ParallelSaResult {
        val n = workers.coerceIn(1, 16)
        val wall0 = System.currentTimeMillis()
        val deadline = wall0 + budgetMs
        val stop = AtomicBoolean(false)
        val sharedIters = AtomicLong(0L)
        val elite = AtomicReference(
            Elite(SearchSessionFull.deepCopy(initial), evaluate(initial)),
        )
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map { w ->
                val seed = baseSeed xor (w.toLong() * -0x61c8864680b583ebL)
                pool.submit(Callable {
                    workerLoop(w, seed, initial, deadline, stop, shouldStop, elite, sharedIters)
                })
            }
            // ハートビート（探索スレッド側）。例外で本体を落とさない。
            while (futures.any { !it.isDone }) {
                if (shouldStop()) {
                    stop.set(true)
                    break
                }
                runCatching {
                    onProgress?.invoke(sharedIters.get(), elite.get().report)
                }
                try {
                    Thread.sleep(1500L)
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
                val waitMs = (deadline - System.currentTimeMillis() + 20_000L).coerceAtLeast(5_000L)
                val pair = runCatching {
                    f.get(waitMs, TimeUnit.MILLISECONDS)
                }.getOrElse { ex ->
                    android.util.Log.w("MAGI", "ParallelSa worker get: ${ex.javaClass.simpleName}: ${ex.message}")
                    0L to elite.get().report
                }
                totalIters += pair.first
                reports += pair.second
            }
            val e = elite.get()
            val finalIters = totalIters.coerceAtLeast(sharedIters.get())
            runCatching { onProgress?.invoke(finalIters, e.report) }
            return ParallelSaResult(
                schedule = e.schedule,
                report = e.report,
                workerBestReports = reports,
                totalIters = finalIters,
                stopReason = when {
                    shouldStop() -> StopReason.CANCELLED
                    else -> StopReason.DEADLINE
                },
            )
        } finally {
            stop.set(true)
            pool.shutdownNow()
            runCatching { pool.awaitTermination(3, TimeUnit.SECONDS) }
        }
    }

    private fun workerLoop(
        @Suppress("UNUSED_PARAMETER") workerId: Int,
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
                evaluate,
                better,
                deltaHook = deltaHook,
            )
            val g1 = G1LocalAnnealer(problem, session)
            var totalIters = 0L
            val sliceMs = 2_500L
            while (!stop.get() && !shouldStop() && System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) break
                val budget = minOf(sliceMs, remain)
                val iters = g1.run(
                    G1Params(
                        budgetMs = budget,
                        shouldStop = { stop.get() || shouldStop() },
                    ),
                    rng,
                )
                totalIters += iters
                sharedIters.addAndGet(iters)
                publishElite(elite, session.best, session.bestReport)
            }
            totalIters to session.bestReport
        } catch (t: Throwable) {
            android.util.Log.e("MAGI", "ParallelSa worker-$workerId crashed: ${t.javaClass.simpleName}", t)
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
}
