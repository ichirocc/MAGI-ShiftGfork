package com.magi.app.v6.engine

import com.magi.app.v6.ViolationReport

/** 探索進捗（UI / Worker 向け）。schedule は任意の途中スナップショット。 */
data class SearchProgress(
    val phase: String,
    val report: ViolationReport,
    val iters: Long = 0L,
    val elapsedMs: Long = 0L,
    /** 非 null のとき Worker が途中最良をファイル退避できる */
    val schedule: Array<IntArray>? = null,
)

fun interface SearchProgressListener {
    fun onProgress(p: SearchProgress)
}
