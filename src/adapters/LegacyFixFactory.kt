package com.magi.app.v6.engine.adapters

import com.magi.app.v6.Problem
import java.util.Random

/**
 * main の find*Fix を LegacyCellFix にまとめるファクトリ。
 * 呼び出し側で DeltaEvaluator と各 find 関数をラムダで渡す。
 *
 * ```kotlin
 * val legacy = LegacyFixFactory.fromDispatchers { focus, rng ->
 *   when (focus) {
 *     "covO" -> findCovOFix(problem, eval, rng)
 *     "c2" -> findC2Fix(problem, eval, rng)
 *     "low" -> findRangeLowFix(problem, eval, rng)
 *     "high" -> findRangeHighFix(problem, eval, rng)
 *     "apt" -> findAptFix(problem, eval, rng)
 *     else -> null
 *   }
 * }
 * ```
 */
object LegacyFixFactory {
    fun fromDispatchers(dispatch: (focus: String?, rng: Random) -> IntArray?): LegacyCellFix =
        LegacyCellFix { focus, rng -> dispatch(focus, rng) }
}
