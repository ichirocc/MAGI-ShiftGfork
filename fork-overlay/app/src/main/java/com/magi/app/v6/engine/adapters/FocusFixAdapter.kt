package com.magi.app.v6.engine.adapters

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import com.magi.app.v6.engine.BoardView
import com.magi.app.v6.engine.FocusFixProvider
import com.magi.app.v6.engine.Move
import com.magi.app.v6.engine.buildSingleMove
import java.util.Random

/**
 * 既存 V6SearchOperators の find*Fix を FocusFixProvider に接続するアダプタ。
 *
 * main 側で次を import して配線する:
 *   findCovOFix, findC2Fix, findRangeLowFix, findRangeHighFix,
 *   findC41Fix, findC41sFix, findC3WantFix, findAptFix, ...
 *
 * 戻り値 intArrayOf(staff, day, newShift) を Move に変換する。
 */
fun interface LegacyCellFix {
    /** @return intArrayOf(i, j, newK) or null */
    fun find(focus: String?, rng: Random): IntArray?
}

class FocusFixAdapter(
    private val problem: com.magi.app.v6.Problem,
    private val legacy: LegacyCellFix,
) : FocusFixProvider {
    override fun propose(
        focus: String?,
        board: BoardView,
        problem: com.magi.app.v6.Problem,
        rng: Random,
    ): Move? {
        val triple = legacy.find(focus, rng) ?: return null
        if (triple.size < 3) return null
        return buildSingleMove(
            board,
            problem,
            triple[0],
            triple[1],
            triple[2],
            source = "g2.legacy.${focus ?: "total"}",
        )
    }
}

/**
 * focus 名から既存オペレータを選ぶディスパッチ例（main 接続時に本文を有効化）。
 *
 * ```kotlin
 * val provider = FocusFixAdapter(problem) { focus, rng ->
 *   val eval = /* DeltaEvaluator on board.current */
 *   when (focus) {
 *     "covO" -> findCovOFix(problem, eval, rng)
 *     "c2" -> findC2Fix(problem, eval, rng)
 *     "low" -> findRangeLowFix(problem, eval, rng)
 *     "high" -> findRangeHighFix(problem, eval, rng)
 *     "c41" -> findC41Fix(problem, eval, rng)
 *     "c41s" -> findC41sFix(problem, eval, rng)
 *     "c3", "c3m", "c3mn" -> findC3WantFix(problem, eval, rng)
 *     "apt" -> findAptFix(problem, eval, rng)
 *     else -> null
 *   }
 * }
 * ```
 */
