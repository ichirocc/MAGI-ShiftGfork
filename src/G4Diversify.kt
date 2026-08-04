package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

data class EliteEntry(val schedule: Array<IntArray>, val report: ViolationReport)
data class BridgeEntry(val schedule: Array<IntArray>, val report: ViolationReport, val fromHard: Int)

class G4Diversify(
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val maxElites: Int = 8,
    private val maxBridges: Int = 16,
) {
    private val elites = ArrayList<EliteEntry>()
    private val bridges = ArrayList<BridgeEntry>()

    fun considerStrict(schedule: Array<IntArray>, report: ViolationReport) {
        val copy = SearchSessionFull.deepCopy(schedule)
        elites.removeAll { e -> better(report, e.report) && !better(e.report, report) }
        elites.add(EliteEntry(copy, report))
        compactElites()
    }

    fun considerBridge(schedule: Array<IntArray>, report: ViolationReport, fromHard: Int) {
        if (bridges.size >= maxBridges) return
        bridges.add(BridgeEntry(SearchSessionFull.deepCopy(schedule), report, fromHard))
    }

    private fun compactElites() {
        elites.sortWith { a, b -> ReportOrdering.compare(a.report, b.report, better) }
        while (elites.size > maxElites) elites.removeAt(elites.lastIndex)
    }

    fun outputCandidates(): List<Array<IntArray>> =
        elites.map { SearchSessionFull.deepCopy(it.schedule) }

    fun outputCandidatesSorted(): List<EliteEntry> {
        compactElites()
        return elites.toList()
    }

    fun bridgeCount(): Int = bridges.size
    fun eliteCount(): Int = elites.size
    fun bridgesForRelinkOnly(): List<BridgeEntry> = bridges.toList()
}
