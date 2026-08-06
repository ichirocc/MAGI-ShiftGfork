package com.magi.app.v6.engine.parallel

import com.magi.app.v6.NativeBridge
import com.magi.app.v6.NativeEval
import com.magi.app.v6.NativeGate
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.DeltaEvaluateHook
import com.magi.app.v6.engine.G1LocalAnnealer
import com.magi.app.v6.engine.G1Params
import com.magi.app.v6.engine.SearchSessionFull
import com.magi.app.v6.engine.StopReason
import com.magi.app.v6.engine.nativex.NativeBridgeProbe
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 完成版並列 SA
 *
 * ## 並列の契約（根本）
 * 各ワーカーが持つもの（共有しない）:
 * - SearchSessionFull（盤面・version・undo）
 * - G1LocalAnnealer
 * - Native problem handle（[allowNative] 時のみ・create/destroy はワーカー内）
 * - 評価用の盤面スナップショット
 *
 * 共有してよいもの:
 * - Problem（探索中は読み取り専用）
 * - Elite の CAS
 * - AtomicLong iters / stop フラグ
 *
 * 評価:
 * - 入力 schedule は必ず deepCopy してから [evaluate] へ（呼び出し側バッファを触らない）
 * - Checker がスレッドセーフでない場合に備え、評価だけ短時間のロックを取る
 *   （探索本体・近傍生成はロック外＝真の並列）
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
    /** 評価の短時間直列化（近傍探索は並列のまま） */
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
        allowNative: Boolean = true,
    ): ParallelSaResult {
        val n = workers.coerceIn(1, MAX_PARALLEL)
        val wall0 = System.currentTimeMillis()
        val deadline = wall0 + budgetMs.coerceAtLeast(1L)
        val stop = AtomicBoolean(false)
        val sharedIters = AtomicLong(0L)
        val initialCopy = SearchSessionFull.deepCopy(initial)
        val elite = AtomicReference(
            Elite(SearchSessionFull.deepCopy(initialCopy), evalSnapshot(initialCopy)),
        )
        val useNative = allowNative && NativeBridge.available && NativeGate.usable

        android.util.Log.i(
            "MAGI",
            "ParallelSa start workers=$n budgetMs=$budgetMs native=$useNative seed=$baseSeed",
        )

        if (n == 1) {
            val (iters, rep) = workerLoop(
                workerId = 0,
                seed = baseSeed,
                initial = initialCopy,
                deadline = deadline,
                stop = stop,
                shouldStop = shouldStop,
                elite = elite,
                sharedIters = sharedIters,
                useNative = useNative,
            )
            runCatching { onProgress?.invoke(iters, elite.get().report) }
            val e = elite.get()
            return ParallelSaResult(
                schedule = e.schedule,
                report = e.report,
                workerBestReports = listOf(rep),
                totalIters = iters,
                stopReason = if (shouldStop()) StopReason.CANCELLED else StopReason.DEADLINE,
            )
        }

        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (0 until n).map { w ->
                val seed = baseSeed xor (w.toLong() * -0x61c8864680b583ebL)
                pool.submit(Callable {
                    workerLoop(
                        workerId = w,
                        seed = seed,
                        initial = initialCopy,
                        deadline = deadline,
                        stop = stop,
                        shouldStop = shouldStop,
                        elite = elite,
                        sharedIters = sharedIters,
                        useNative = useNative,
                    )
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
                }.getOrElse { ex ->
                    android.util.Log.w("MAGI", "ParallelSa join: ${ex.javaClass.simpleName}")
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
                "ParallelSa done workers=$n iters=$finalIters hard=${e.report.hard} total=${e.report.total}",
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

    private fun workerLoop(
        workerId: Int,
        seed: Long,
        initial: Array<IntArray>,
        deadline: Long,
        stop: AtomicBoolean,
        shouldStop: () -> Boolean,
        elite: AtomicReference<Elite>,
        sharedIters: AtomicLong,
        useNative: Boolean,
    ): Pair<Long, ViolationReport> {
        var nativeHandle = 0L
        return try {
            val rng = Random(seed)
            val session = SearchSessionFull(
                problem,
                SearchSessionFull.deepCopy(initial),
                ::evalSnapshot,
                better,
                deltaHook = null,
            )
            // ワーカー専用 native handle（共有しない）
            val probe = if (useNative) {
                nativeHandle = runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L)
                if (nativeHandle != 0L) NativeBridgeProbe(nativeHandle) else null
            } else {
                null
            }
            if (probe != null) {
                android.util.Log.i("MAGI", "ParallelSa worker-$workerId nativeHandle=$nativeHandle")
            }
            val g1 = G1LocalAnnealer(problem, session)
            var totalIters = 0L
            while (!stop.get() && !shouldStop() && System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) break
                val budget = minOf(SLICE_MS, remain)
                val iters = g1.run(
                    G1Params(
                        budgetMs = budget,
                        shouldStop = { stop.get() || shouldStop() },
                        nativeProbe = probe,
                        // 並列では hard 増の早期棄却のみ（品質安全）
                        earlyRejectHardIncrease = probe != null,
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
            android.util.Log.e(
                "MAGI",
                "ParallelSa worker-$workerId ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
            0L to elite.get().report
        } finally {
            if (nativeHandle != 0L) {
                runCatching { NativeBridge.nativeDestroyProblem(nativeHandle) }
            }
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
        /** 端末コア数に合わせて上げてよい上限 */
        const val MAX_PARALLEL = 8
        private const val SLICE_MS = 2_500L
        private const val PROGRESS_MS = 1_500L
        private const val JOIN_SLACK_MS = 25_000L
    }
}
