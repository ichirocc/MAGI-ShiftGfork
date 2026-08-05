package com.magi.app.v6.engine

import com.magi.app.v6.Problem

/**
 * 境界・空問題のガード。rng.nextInt(0) や 0 除算経路を入口で遮断する。
 */
object ProblemGuards {
    fun isRunnable(problem: Problem): Boolean =
        problem.S > 0 && problem.T > 0 && problem.K > 0

    fun requireRunnable(problem: Problem) {
        require(problem.S > 0) { "Problem.S must be > 0" }
        require(problem.T > 0) { "Problem.T must be > 0" }
        require(problem.K > 0) { "Problem.K must be > 0" }
    }

    fun scheduleShapeOk(problem: Problem, schedule: Array<IntArray>): Boolean {
        if (!isRunnable(problem)) return false
        if (schedule.size != problem.S) return false
        for (row in schedule) {
            if (row.size != problem.T) return false
        }
        return true
    }
}
