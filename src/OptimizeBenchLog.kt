package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.ViolationReport
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本番精度・速度の優劣比較用の構造化ログ。
 *
 * 1行1イベント（パース容易）:
 * ```
 * MAGI_BENCH engine=rebuild phase=g1 hard=3 soft=12 total=15 weighted=42.5 elapsedMs=12045 iters=8000 seed=42 workers=4
 * MAGI_BENCH_SUMMARY engine=rebuild hard0=0 weighted=12.0 total=8 elapsedMs=298101 seed=42 vsBaselineHardDelta=-1 ...
 * ```
 *
 * ログは Logcat tag `MAGI_BENCH` と [events] バッファの両方に残す。
 * UI / Worker は [drainMirrorLogs] で操作ログへ転記できる。
 */
object OptimizeBenchLog {

    const val TAG = "MAGI_BENCH"
    const val ENGINE_REBUILD = "rebuild"
    const val ENGINE_UPSTREAM = "upstream"

    data class Event(
        val engine: String,
        val phase: String,
        val hard: Int,
        val soft: Int,
        val total: Int,
        val weighted: Double,
        val elapsedMs: Long,
        val iters: Long = 0L,
        val seed: Long = 0L,
        val workers: Int = 1,
        val budgetMs: Long = 0L,
        val note: String = "",
        val wallTs: Long = System.currentTimeMillis(),
    ) {
        fun line(): String = buildString {
            append("MAGI_BENCH")
            append(" engine=").append(engine)
            append(" phase=").append(phase)
            append(" hard=").append(hard)
            append(" soft=").append(soft)
            append(" total=").append(total)
            append(" weighted=").append(weighted)
            append(" elapsedMs=").append(elapsedMs)
            append(" iters=").append(iters)
            append(" seed=").append(seed)
            append(" workers=").append(workers)
            append(" version=").append(AppVersion.info.compact())
            if (budgetMs > 0L) append(" budgetMs=").append(budgetMs)
            if (note.isNotEmpty()) append(" note=").append(note.replace(' ', '_'))
        }

        fun toMirrorLog(iter: Long = iters): MirrorLog =
            MirrorLog(message = line(), level = "I")
    }

    data class Summary(
        val engine: String,
        val hard: Int,
        val soft: Int,
        val total: Int,
        val weighted: Double,
        val elapsedMs: Long,
        val seed: Long,
        val workers: Int,
        val budgetMs: Long,
        val phases: List<Event>,
    ) {
        fun line(): String = buildString {
            append("MAGI_BENCH_SUMMARY")
            append(" version=").append(AppVersion.info.compact())
            append(" engine=").append(engine)
            append(" hard=").append(hard)
            append(" soft=").append(soft)
            append(" total=").append(total)
            append(" weighted=").append(weighted)
            append(" elapsedMs=").append(elapsedMs)
            append(" seed=").append(seed)
            append(" workers=").append(workers)
            append(" version=").append(AppVersion.info.compact())
            append(" budgetMs=").append(budgetMs)
            append(" phases=").append(phases.size)
        }
    }

    private val events = CopyOnWriteArrayList<Event>()
    @Volatile private var runId: String = ""

    fun beginRun(engine: String, seed: Long, workers: Int, budgetMs: Long) {
        runId = "${engine}_${seed}_${System.currentTimeMillis()}"
        val e = Event(
            engine = engine,
            phase = "begin",
            hard = -1,
            soft = -1,
            total = -1,
            weighted = -1.0,
            elapsedMs = 0L,
            seed = seed,
            workers = workers,
            budgetMs = budgetMs,
            note = "runId=$runId",
        )
        events.add(e)
        log(e)
    }

    fun phase(
        engine: String,
        phase: String,
        report: ViolationReport?,
        elapsedMs: Long,
        iters: Long = 0L,
        seed: Long = 0L,
        workers: Int = 1,
        budgetMs: Long = 0L,
        note: String = "",
    ) {
        val e = Event(
            engine = engine,
            phase = phase,
            hard = report?.hard ?: -1,
            soft = report?.soft ?: -1,
            total = report?.total ?: -1,
            weighted = (report?.weightedScore?.toDouble() ?: -1.0),
            elapsedMs = elapsedMs,
            iters = iters,
            seed = seed,
            workers = workers,
            budgetMs = budgetMs,
            note = note,
        )
        events.add(e)
        log(e)
    }

    fun summary(s: Summary) {
        events.add(
            Event(
                engine = s.engine,
                phase = "summary",
                hard = s.hard,
                soft = s.soft,
                total = s.total,
                weighted = s.weighted,
                elapsedMs = s.elapsedMs,
                seed = s.seed,
                workers = s.workers,
                budgetMs = s.budgetMs,
                note = "phases=${s.phases.size}",
            ),
        )
        println(s.line())
    }

    /** rebuild vs upstream の1行比較（両方の SUMMARY がある前提） */
    fun compare(rebuild: Summary, upstream: Summary): String {
        val hardD = rebuild.hard - upstream.hard
        val wD = rebuild.weighted - upstream.weighted
        val tD = rebuild.elapsedMs - upstream.elapsedMs
        val line = buildString {
            append("MAGI_BENCH_COMPARE")
            append(" hardDelta=").append(hardD)
            append(" weightedDelta=").append(wD)
            append(" elapsedMsDelta=").append(tD)
            append(" rebuildHard=").append(rebuild.hard)
            append(" upstreamHard=").append(upstream.hard)
            append(" rebuildWeighted=").append(rebuild.weighted)
            append(" upstreamWeighted=").append(upstream.weighted)
            append(" rebuildMs=").append(rebuild.elapsedMs)
            append(" upstreamMs=").append(upstream.elapsedMs)
            append(" winnerAccuracy=").append(
                when {
                    hardD < 0 -> "rebuild"
                    hardD > 0 -> "upstream"
                    wD < 0 -> "rebuild"
                    wD > 0 -> "upstream"
                    else -> "tie"
                },
            )
            append(" winnerSpeed=").append(
                when {
                    tD < 0 -> "rebuild"
                    tD > 0 -> "upstream"
                    else -> "tie"
                },
            )
        }
        println(line)
        return line
    }

    fun snapshot(): List<Event> = events.toList()

    fun clear() {
        events.clear()
        runId = ""
    }

    fun drainMirrorLogs(): List<MirrorLog> = events.map { it.toMirrorLog() }

    private fun log(e: Event) {
        println(e.line())
    }
}
