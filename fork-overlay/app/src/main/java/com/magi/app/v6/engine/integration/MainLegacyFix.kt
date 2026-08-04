package com.magi.app.v6.engine.integration

import com.magi.app.v6.DeltaEvaluator
import com.magi.app.v6.Problem
import com.magi.app.v6.engine.adapters.LegacyCellFix
import com.magi.app.v6.engine.adapters.LegacyFixFactory
import com.magi.app.v6.findAptFix
import com.magi.app.v6.findC2Fix
import com.magi.app.v6.findCovOFix
import com.magi.app.v6.findRangeHighFix
import com.magi.app.v6.findRangeLowFix
import java.util.Random

/**
 * main の find*Fix を LegacyCellFix に配線。
 * DeltaEvaluator(problem) + reset(schedule) — 第2引数は c3RunMode: Boolean。
 */
object MainLegacyFix {
    fun create(problem: Problem, schedule: Array<IntArray>): LegacyCellFix {
        val eval = DeltaEvaluator(problem).also { it.reset(schedule) }
        return LegacyFixFactory.fromDispatchers { focus, rng ->
            when (focus) {
                "covO" -> findCovOFix(problem, eval, rng)
                "c2" -> findC2Fix(problem, eval, rng)
                "low" -> findRangeLowFix(problem, eval, rng)
                "high" -> findRangeHighFix(problem, eval, rng)
                "apt" -> findAptFix(problem, eval, rng)
                else -> null
            }
        }
    }
}
