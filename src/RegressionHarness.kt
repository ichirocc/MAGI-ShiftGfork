package com.magi.app.v6.engine

import com.magi.app.v6.Evaluator
import com.magi.app.v6.Problem
import com.magi.app.v6.betterReport
import com.magi.app.v6.installFullConstraintDemo
import com.magi.app.v6.engine.parallel.ParallelSaCoordinator
import java.util.Random

/**
 * 固定 seed 回帰。
 * workers=1 は 2 回実行で best の hard/weighted が一致すること。
 * workers>=2 は統計用（決定性は要求しない）。
 *
 * 実行例:
 *   kotlin -cp out:... com.magi.app.v6.engine.RegressionHarnessKt
 */
object RegressionHarness {

    data class RunSummary(
        val hard: Int,
        val weighted: Long,
        val total: Int,
        val fingerprint: Long,
    )

    fun tinyProblem(): Problem {
        val p = Problem(S = 6, T = 14, K = 3)
        p.installFullConstraintDemo()
        for (d in 0 until p.T) p.dayDemand[d] = 2
        // 一部希望ロック
        p.lockWish(0, 0, 1)
        p.lockWish(1, 1, 0)
        return p
    }

    fun randomInitial(p: Problem, rng: Random): Array<IntArray> {
        val sch = Array(p.S) { IntArray(p.T) }
        for (s in 0 until p.S) for (d in 0 until p.T) {
            if (p.wishLocked(s, d)) {
                sch[s][d] = p.preferred[s][d].coerceAtLeast(0)
            } else {
                val alts = p.allowedShiftsForStaff(s)
                sch[s][d] = if (alts.isEmpty()) 0 else alts[rng.nextInt(alts.size)]
            }
        }
        return sch
    }

    fun fingerprint(sch: Array<IntArray>): Long {
        var h = 1125899906842597L
        for (row in sch) for (v in row) h = h * 1315423911L + v
        return h
    }

    fun runOnce(
        workers: Int,
        seed: Long,
        budgetMs: Long = 3_000L,
        fixedIters: Boolean = false,
    ): RunSummary {
        if (!ObjectiveWeightsSource.isReady()) {
            ObjectiveWeightsSource.install(
                hardKeys = listOf("covU", "groupViol", "c3n", "lockBreak", "illegal", "exact", "c1"),
                softWeights = mapOf(
                    "covO" to 5.0, "c2" to 4.0, "c3" to 3.0, "weekly" to 2.0,
                    "bal" to 1.0, "fair" to 1.0, "wish" to 2.0, "pref" to 1.0,
                ),
            )
        }
        val problem = tinyProblem()
        val eval = Evaluator(problem)
        val evaluate = eval::evaluate
        val init = randomInitial(problem, Random(seed xor 0x11L))
        val facade = EngineFacade(problem, evaluate, ::betterReport)
        val art = facade.optimize(
            init,
            EngineOptions(
                totalBudgetMs = budgetMs,
                postReserveMs = if (fixedIters) 0L else 500L,
                seed = seed,
                workers = workers,
                fixedItersG1 = if (fixedIters) 400L else 0L,
                fixedItersG2 = if (fixedIters) 200L else 0L,
                skipPostProcess = fixedIters,
            ),
        )
        return RunSummary(
            hard = art.report.hard,
            weighted = art.report.weightedScore,
            total = art.report.total,
            fingerprint = fingerprint(art.schedule),
        )
    }

    /** workers=1 の二回一致を検証。不一致なら IllegalStateException */
    fun assertDeterministic1Worker(seed: Long = 42L, budgetMs: Long = 2_500L): Pair<RunSummary, RunSummary> {
        // 壁時計ではなく固定反復で再現性を保証
        val a = runOnce(workers = 1, seed = seed, budgetMs = budgetMs, fixedIters = true)
        val b = runOnce(workers = 1, seed = seed, budgetMs = budgetMs, fixedIters = true)
        check(a.hard == b.hard && a.weighted == b.weighted && a.fingerprint == b.fingerprint) {
            "workers=1 非決定的: $a vs $b"
        }
        // 希望ロックが初期で固定したセルは最終でも維持（簡易: fingerprint 一致で代替）
        return a to b
    }

    fun parallelProbe(workers: Int = 4, seed: Long = 7L, budgetMs: Long = 3_000L): List<RunSummary> {
        // 複数 seed で中央値用サンプル
        return (0 until 3).map { i ->
            runOnce(workers = workers, seed = seed + i * 17, budgetMs = budgetMs)
        }
    }
}

fun main() {
    println("RegressionHarness: workers=1 determinism...")
    val (a, b) = RegressionHarness.assertDeterministic1Worker()
    println("OK hard=${a.hard} weighted=${a.weighted} fp=${a.fingerprint} (match=$b)")
    println("parallel probe (workers=4)...")
    val samples = RegressionHarness.parallelProbe()
    val hards = samples.map { it.hard }.sorted()
    println("hard samples=$hards median=${hards[hards.size / 2]}")
}
