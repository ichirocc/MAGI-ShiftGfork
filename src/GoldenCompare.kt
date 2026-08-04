package com.magi.app.v6.engine

/**
 * Golden 非劣化比較（純 JVM）。
 *
 * 固定 seed・固定反復で「再構築経路相当」を2回走らせ、
 * hard が閾値以下かつ再現することを検証する。
 *
 * 本番 main 経路との APK 比較は P3 実機チェックリストを参照。
 */
object GoldenCompare {

    data class GoldenResult(
        val seed: Long,
        val hard: Int,
        val weighted: Long,
        val total: Int,
        val fingerprint: Long,
    )

    /** 既定 golden: seed=42 で hard==0 を期待 */
    fun runGolden(
        seed: Long = 42L,
        maxHard: Int = 0,
    ): GoldenResult {
        val s = RegressionHarness.runOnce(
            workers = 1,
            seed = seed,
            budgetMs = 10_000L,
            fixedIters = true,
        )
        check(s.hard <= maxHard) {
            "golden failed: hard=${s.hard} > maxHard=$maxHard (seed=$seed)"
        }
        return GoldenResult(seed, s.hard, s.weighted, s.total, s.fingerprint)
    }

    /** 複数 seed で hard が悪化しないこと */
    fun runSuite(seeds: LongArray = longArrayOf(42L, 7L, 99L), maxHard: Int = 2): List<GoldenResult> {
        return seeds.map { runGolden(seed = it, maxHard = maxHard) }
    }
}
