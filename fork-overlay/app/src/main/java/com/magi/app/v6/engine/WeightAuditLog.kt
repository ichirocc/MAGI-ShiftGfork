package com.magi.app.v6.engine

import android.util.Log
import com.magi.app.v6.ViolationReport
import kotlin.math.abs

/**
 * 重みの妥当性をログで検証する。
 *
 * 出力例:
 * ```
 * MAGI_WEIGHTS hash=a1b2c3 ready=true hard=c3n,covU,groupViol,... soft=c1:15,c3:3,low:90,...
 * MAGI_WEIGHT_CONTRIB phase=final c1:12x15=180 c3:84x3=252 weekly:161x1=161 softSum=1046 hard=1
 * MAGI_WEIGHT_AUDIT ok=true notes=soft_below_hard_unit;top=c1:180,c3:252,weekly:161
 * ```
 */
object WeightAuditLog {
    const val TAG = "MAGI_WEIGHTS"

    fun logTable(tag: String = TAG) {
        ObjectiveWeightsSource.ensureDefaults()
        val hard = ObjectiveWeightsSource.hardKeys().joinToString(",")
        val soft = ObjectiveWeightsSource.softEntries()
            .sortedByDescending { it.weight }
            .joinToString(",") { "${it.key}:${it.weight}" }
        val line = buildString {
            append("MAGI_WEIGHTS")
            append(" hash=").append(ObjectiveWeightsSource.hash())
            append(" ready=").append(ObjectiveWeightsSource.isReady())
            append(" version=").append(AppVersion.info.compact())
            append(" hard=").append(hard)
            append(" soft=").append(soft)
        }
        runCatching { Log.i(tag, line) }
        runCatching { Log.i(OptimizeBenchLog.TAG, line) }
    }

    /**
     * breakdown（件数）× 重み = 寄与。ソフト合計と HARD の桁を比較して妥当性メモを付ける。
     */
    fun logContribution(
        phase: String,
        report: ViolationReport?,
        tag: String = TAG,
    ) {
        if (report == null) return
        ObjectiveWeightsSource.ensureDefaults()
        val parts = ArrayList<Pair<String, Double>>()
        for ((k, cnt) in report.breakdown) {
            if (cnt <= 0) continue
            if (ObjectiveWeightsSource.isHard(k) || k in ObjectiveWeightsSource.hardKeys()) continue
            val w = ObjectiveWeightsSource.softWeight(k)
            parts.add(k to cnt * w)
        }
        parts.sortByDescending { it.second }
        val softSum = parts.sumOf { it.second }
        val top = parts.take(8).joinToString(",") { (k, c) ->
            val cnt = report.breakdown[k] ?: 0
            val w = ObjectiveWeightsSource.softWeight(k)
            "$k:${cnt}x${w.toInt().coerceAtLeast(0).let { if (w == it.toDouble()) "$it" else "$w" }}=${c.toLong()}"
        }
        val line = buildString {
            append("MAGI_WEIGHT_CONTRIB")
            append(" phase=").append(phase)
            append(" hard=").append(report.hard)
            append(" softCount=").append(report.soft)
            append(" total=").append(report.total)
            append(" weightedScore=").append(report.weightedScore)
            append(" softSum=").append(softSum.toLong())
            append(" top=").append(top)
            append(" version=").append(AppVersion.info.compact())
        }
        runCatching { Log.i(tag, line) }
        runCatching { Log.i(OptimizeBenchLog.TAG, line) }

        // 妥当性ヒューリスティック
        val notes = ArrayList<String>()
        val hardUnit = ViolationReport.HARD_UNIT.toDouble()
        if (softSum >= hardUnit) {
            notes.add("WARN_soft_overflow_hard_unit")
        } else {
            notes.add("soft_below_hard_unit")
        }
        // 1件の soft が hard 1 件相当を超えないか（重み < HARD_UNIT）
        val maxW = ObjectiveWeightsSource.softEntries().maxOfOrNull { it.weight } ?: 0.0
        if (maxW >= hardUnit) notes.add("WARN_single_soft_weight_ge_hard_unit")
        // low/high が極端に支配していないか
        val lowC = parts.firstOrNull { it.first == "low" }?.second ?: 0.0
        if (softSum > 0 && lowC / softSum > 0.6) notes.add("note_low_dominates")
        val weeklyC = parts.firstOrNull { it.first == "weekly" }?.second ?: 0.0
        if (softSum > 0 && weeklyC / softSum > 0.5) notes.add("note_weekly_dominates")
        // 寄与と report.soft の乖離（表示 soft が重み付きか件数か）
        val softDiff = abs(softSum - report.soft.toDouble())
        if (softDiff > 1.0 && report.soft > 0) {
            notes.add("note_softCount_vs_softSum_diff=${softDiff.toLong()}")
        }
        val ok = notes.none { it.startsWith("WARN_") }
        val audit = buildString {
            append("MAGI_WEIGHT_AUDIT")
            append(" phase=").append(phase)
            append(" ok=").append(ok)
            append(" notes=").append(notes.joinToString(";"))
            append(" maxSoftWeight=").append(maxW)
            append(" hash=").append(ObjectiveWeightsSource.hash())
        }
        runCatching { Log.i(tag, audit) }
        runCatching { Log.i(OptimizeBenchLog.TAG, audit) }
    }
}
