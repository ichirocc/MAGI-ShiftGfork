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
 * 並列 SA（クラッシュ耐性・根本修正）
 *
 * ## クラッシュの根（実機: g1-parallel 中に必須改善後 OS kill）
 * 1. 評価のたび [SearchSessionFull.deepCopy] → GC 爆発・OOM/LMK
 * 2. 全ワーカーが共有 evalLock で直列化 → 並列の意味が無いのにコピーだけ増える
 * 3. 進捗 1.5s × UI ログでメイン/ヒープ圧迫
 *
 * ## 方針
 * - ワーカーは自盤面を所有。evaluate はコピーなし（評価は読み取りのみと契約）
 * - Elite 公開時だけ deepCopy
 * - ワーカー上限 4・進捗 5 秒
 * - JNI は並列では絶対に使わない
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
    fun run(
        initial: Array<IntArray>,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_PARALLEL),
        budgetMs: Long = 120_000L,
        baseSeed: Long = 1L,
        shouldStop: () -> Boolean = { false },
        onProgress: ((iters: Long, best: ViolationReport) -> Unit)? = null,
        @Suppress("UNUSED_PARAMETER")
        allowNative: Boolean = false,
        /** best.hard がこの時間改善しなければ早期切り上げ（0で無効） */
        noImproveMs: Long = DEFAULT_NO_IMPROVE_MS,
        /** これ以下の hard は「改善不能 HARD のみ」とみなし停滞切上げを早める */
        provenHardFloor: Int = 0,
    ): ParallelSaResult {
        val n = safeWorkerCount(workers)
        val deadline = System.currentTimeMillis() + budgetMs.coerceAtLeast(1L)
        val stop = AtomicBoolean(false)
        val sharedIters = AtomicLong(0L)
        val seedBoard = SearchSessionFull.deepCopy(initial)
        val elite = AtomicReference(
            Elite(SearchSessionFull.deepCopy(seedBoard), evaluate(seedBoard)),
        )
        val lastImproveMs = AtomicLong(System.currentTimeMillis())
        val lastHard = java.util.concurrent.atomic.AtomicInteger(elite.get().report.hard)

        android.util.Log.i(
            "MAGI",
            "STAGE parallel-start workers=$n budgetMs=$budgetMs noImproveMs=$noImproveMs " +
                "provenHardFloor=$provenHardFloor mode=kotlin-only-noCopyEval",
        )
        runCatching { onProgress?.invoke(0L, elite.get().report) }

        if (n == 1) {
            val (iters, rep) = runWorker(0, baseSeed, seedBoard, deadline, stop, shouldStop, elite, sharedIters, lastImproveMs, lastHard)
            runCatching { onProgress?.invoke(iters, elite.get().report) }
            val e = elite.get()
            return ParallelSaResult(
                e.schedule, e.report, listOf(rep), iters,
                if (shouldStop()) StopReason.CANCELLED else StopReason.DEADLINE,
            )
        }

        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map { w ->
                val seed = baseSeed xor (w.toLong() * -0x61c8864680b583ebL)
                pool.submit(Callable {
                    runWorker(w, seed, seedBoard, deadline, stop, shouldStop, elite, sharedIters, lastImproveMs, lastHard)
                })
            }
            var stopReasonLocal = StopReason.DEADLINE
            while (futures.any { !it.isDone }) {
                if (shouldStop()) {
                    stop.set(true)
                    stopReasonLocal = StopReason.CANCELLED
                    break
                }
                val er = elite.get().report
                val improvable = (er.hard - provenHardFloor).coerceAtLeast(0)
                // 改善可能な HARD が尽きた、または HARD 停滞
                val stalled = noImproveMs > 0L &&
                    System.currentTimeMillis() - lastImproveMs.get() >= noImproveMs
                if (improvable == 0 && er.hard > 0) {
                    android.util.Log.i(
                        "MAGI",
                        "STAGE parallel-cutover reason=all-hard-unimprovable hard=${er.hard} floor=$provenHardFloor",
                    )
                    stop.set(true)
                    stopReasonLocal = StopReason.PROVEN_PIN_WALL
                    break
                }
                if (stalled && er.hard > 0) {
                    android.util.Log.i(
                        "MAGI",
                        "STAGE parallel-cutover reason=hard-stagnation noImproveMs=$noImproveMs hard=${er.hard}",
                    )
                    stop.set(true)
                    stopReasonLocal = StopReason.FIXED_POINT
                    break
                }
                runCatching { onProgress?.invoke(sharedIters.get(), elite.get().report) }
                try {
                    Thread.sleep(PROGRESS_MS)
                } catch (_: InterruptedException) {
                    stop.set(true)
                    stopReasonLocal = StopReason.CANCELLED
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
                    android.util.Log.w("MAGI", "ParallelSa join: ${it.javaClass.simpleName}")
                    0L to elite.get().report
                }
                totalIters += pair.first
                reports += pair.second
            }
            val e = elite.get()
            val finalIters = totalIters.coerceAtLeast(sharedIters.get())
            runCatching { onProgress?.invoke(finalIters, e.report) }
            android.util.Log.i(
                "MAGI",
                "STAGE parallel-done workers=$n iters=$finalIters hard=${e.report.hard} total=${e.report.total}",
            )
            return ParallelSaResult(
                schedule = e.schedule,
                report = e.report,
                workerBestReports = reports,
                totalIters = finalIters,
                stopReason = when {
                    shouldStop() -> StopReason.CANCELLED
                    stopReasonLocal != StopReason.DEADLINE -> stopReasonLocal
                    else -> StopReason.DEADLINE
                },
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
        lastImproveMs: AtomicLong,
        lastHard: java.util.concurrent.atomic.AtomicInteger,
    ): Pair<Long, ViolationReport> {
        return try {
            val rng = Random(seed)
            val session = SearchSessionFull(
                problem,
                SearchSessionFull.deepCopy(initial),
                evaluate,
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
                    ),
                    rng,
                )
                totalIters += iters
                sharedIters.addAndGet(iters)
                publishElite(elite, session.best, session.bestReport, lastImproveMs, lastHard)
            }
            totalIters to session.bestReport
        } catch (t: Throwable) {
            android.util.Log.e("MAGI", "ParallelSa worker-$workerId ${t.javaClass.simpleName}: ${t.message}", t)
            0L to elite.get().report
        }
    }

    private fun publishElite(
        elite: AtomicReference<Elite>,
        schedule: Array<IntArray>,
        report: ViolationReport,
        lastImproveMs: AtomicLong,
        lastHard: java.util.concurrent.atomic.AtomicInteger,
    ) {
        while (true) {
            val cur = elite.get()
            if (!better(report, cur.report)) return
            val next = Elite(SearchSessionFull.deepCopy(schedule), report)
            if (elite.compareAndSet(cur, next)) {
                if (report.hard < lastHard.get()) {
                    lastHard.set(report.hard)
                    lastImproveMs.set(System.currentTimeMillis())
                }
                return
            }
        }
    }

    companion object {
        const val MAX_PARALLEL = 4
        private const val SLICE_MS = 3_000L
        private const val PROGRESS_MS = 5_000L
        private const val JOIN_SLACK_MS = 25_000L
        /** HARD 非更新の早期切り上げ（並列 G1） */
        const val DEFAULT_NO_IMPROVE_MS = 12_000L

        fun safeWorkerCount(requested: Int): Int {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val freeMb = Runtime.getRuntime().freeMemory() / (1024L * 1024L)
            val byMem = when {
                freeMb < 32L -> 1
                freeMb < 64L -> 2
                freeMb < 128L -> 3
                else -> MAX_PARALLEL
            }
            return requested.coerceIn(1, minOf(MAX_PARALLEL, cores, byMem))
        }
    }
}
