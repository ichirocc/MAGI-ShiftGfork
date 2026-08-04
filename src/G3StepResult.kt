package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport

/**
 * G3 1ステップの結果。副作用フィールド (lastImproved*) を置き換える。
 */
data class G3StepResult(
    val improved: Boolean,
    val schedule: Array<IntArray>? = null,
    val report: ViolationReport? = null,
) {
    companion object {
        val NONE = G3StepResult(false)
        fun of(session: SearchSessionFull, beforeHard: Int, beforeWeighted: Long): G3StepResult {
            val improved =
                session.bestReport.hard < beforeHard ||
                    (session.bestReport.hard == beforeHard &&
                        session.bestReport.weightedScore < beforeWeighted)
            if (!improved) return NONE
            return G3StepResult(
                improved = true,
                schedule = SearchSessionFull.deepCopy(session.best),
                report = session.bestReport,
            )
        }
    }
}
