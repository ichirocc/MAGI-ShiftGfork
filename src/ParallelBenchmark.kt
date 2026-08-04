package com.magi.app.v6.engine

/**
 * 並列 SA の中央値・p95 ベンチ。
 * 本番相当は [runProduction]（既定 300s は重いので呼び出し側で seeds を絞る）。
 */
object ParallelBenchmark {

    data class Stats(
        val workers: Int,
        val n: Int,
        val budgetMs: Long,
        val hardP50: Int,
        val hardP95: Int,
        val weightedP50: Long,
        val weightedP95: Long,
        val samples: List<RegressionHarness.RunSummary>,
    )

    fun percentileInt(sorted: List<Int>, p: Double): Int {
        if (sorted.isEmpty()) return 0
        val i = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[i]
    }

    fun percentileLong(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val i = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[i]
    }

    fun run(
        workers: Int = 4,
        seeds: Int = 11,
        budgetMs: Long = 2_000L,
        baseSeed: Long = 100L,
    ): Stats {
        val samples = (0 until seeds).map { i ->
            RegressionHarness.runOnce(
                workers = workers,
                seed = baseSeed + i * 31L,
                budgetMs = budgetMs,
            )
        }
        val hards = samples.map { it.hard }.sorted()
        val weights = samples.map { it.weighted }.sorted()
        return Stats(
            workers = workers,
            n = seeds,
            budgetMs = budgetMs,
            hardP50 = percentileInt(hards, 0.50),
            hardP95 = percentileInt(hards, 0.95),
            weightedP50 = percentileLong(weights, 0.50),
            weightedP95 = percentileLong(weights, 0.95),
            samples = samples,
        )
    }

    /**
     * 本番予算プロファイル。
     * @param budgetMs 既定 300_000（探索全体に近い枠）。CI では budgetMs を短縮すること。
     */
    fun runProduction(
        workers: Int = 4,
        seeds: Int = 5,
        budgetMs: Long = 300_000L,
        baseSeed: Long = 2026L,
    ): Stats = run(workers = workers, seeds = seeds, budgetMs = budgetMs, baseSeed = baseSeed)

    fun compareWorkers(
        workerCounts: IntArray = intArrayOf(1, 4),
        seeds: Int = 3,
        budgetMs: Long = 5_000L,
    ): Map<Int, Stats> =
        workerCounts.associateWith { w -> run(workers = w, seeds = seeds, budgetMs = budgetMs) }
}
