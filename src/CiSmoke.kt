package com.magi.app.v6.engine

import com.magi.app.v6.engine.nativex.GlobalNativeSkipGate

/**
 * CI / スモーク入口。
 *
 * ```
 * java -cp out:... com.magi.app.v6.engine.CiSmokeKt
 * ```
 *
 * 環境変数:
 * - MAGI_BUDGET_MS (既定 2000)
 * - MAGI_SEEDS (既定 3)
 * - MAGI_WORKERS (既定 1 と 4 を比較)
 */
fun main() {
    val budget = System.getenv("MAGI_BUDGET_MS")?.toLongOrNull() ?: 2_000L
    val seeds = System.getenv("MAGI_SEEDS")?.toIntOrNull() ?: 3

    println("=== CiSmoke budgetMs=$budget seeds=$seeds ===")

    // 1) workers=1 決定性
    print("determinism w1... ")
    val (a, b) = RegressionHarness.assertDeterministic1Worker(seed = 42L, budgetMs = budget)
    println("OK hard=${a.hard} weighted=${a.weighted} fp=${a.fingerprint}")

    // 2) worker 比較（短縮）
    print("compare workers... ")
    val cmp = ParallelBenchmark.compareWorkers(
        workerCounts = intArrayOf(1, 4),
        seeds = seeds,
        budgetMs = budget,
    )
    for ((w, st) in cmp) {
        println("  w$w hard p50=${st.hardP50} p95=${st.hardP95} weightedP50=${st.weightedP50}")
    }

    // 3) Native skip gate stats（未使用なら 0）
    println("nativeSkip: ${GlobalNativeSkipGate.gate.stats()}")

    // 4) Golden
    print("golden suite... ")
    val goldens = GoldenCompare.runSuite()
    println("OK " + goldens.joinToString { "s${it.seed}:h${it.hard}" })

    // 5) 非劣化ガード: w1 hard が極端に悪くないこと
    val w1 = cmp[1]!!
    check(w1.hardP95 <= 20) {
        "w1 hard p95=${w1.hardP95} too high for tiny problem — investigate regression"
    }
    println("=== CiSmoke PASS ===")
}
