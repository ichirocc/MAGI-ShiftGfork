package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

/**
 * alternatives / elite のソートを betterReport に一本化する。
 * alternatives / elite を betterReport 順に並べる。
 */
object ReportOrdering {
    fun compare(
        a: ViolationReport,
        b: ViolationReport,
        better: (ViolationReport, ViolationReport) -> Boolean,
    ): Int = when {
        better(a, b) -> -1
        better(b, a) -> 1
        else -> 0
    }

    fun <T> sortedByReport(
        items: List<T>,
        reportOf: (T) -> ViolationReport,
        better: (ViolationReport, ViolationReport) -> Boolean,
    ): List<T> = items.sortedWith { x, y -> compare(reportOf(x), reportOf(y), better) }
}
