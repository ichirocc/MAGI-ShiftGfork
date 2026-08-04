package com.magi.app.v6.engine.integration

import com.magi.app.model.MagiState
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.engine.SearchProgress
import com.magi.app.v6.engine.SearchProgressListener

object RebuildOptimizeEntry {
    /**
     * 実行時トグル。起動時に [applyBuildConfigDefault] で BuildConfig.REBUILD_ENGINE を反映可能。
     */
    @JvmField
    @Volatile
    var enabled: Boolean = false

    /** Application.onCreate から1回呼ぶ */
    fun applyBuildConfigDefault() {
        val v = runCatching {
            val cl = Class.forName("com.magi.app.BuildConfig")
            cl.getField("REBUILD_ENGINE").getBoolean(null)
        }.getOrDefault(false)
        enabled = v
    }

    fun optimize(
        state: MagiState,
        schedule: Array<IntArray>,
        budgetSec: Int,
        seed: Long,
        workers: Int = 1,
        shouldStop: () -> Boolean = { false },
        onProgress: ((SearchProgress) -> Unit)? = null,
    ): ScheduleRunResult {
        val listener = onProgress?.let { cb -> SearchProgressListener { p -> cb(p) } }
        return V6RebuildPort.optimize(
            state = state,
            schedule = schedule,
            budgetMs = budgetSec.coerceAtLeast(1) * 1000L,
            seed = seed,
            workers = workers.coerceIn(1, 16),
            shouldStop = shouldStop,
            autoNative = true,
            progressListener = listener,
        )
    }
}
