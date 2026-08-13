package com.magi.app.v6.engine.nativex

import com.magi.app.v6.ViolationReport
import com.magi.app.v6.ViolationReports
import com.magi.app.v6.engine.DeltaEvaluateHook
import java.util.concurrent.atomic.AtomicLong

/**
 * ホットパスは Native 差分のみ。フル評価は失敗時・パリティ周期のみ。
 *
 * [SearchSessionFull] は apply 後盤面を渡すため、undoSnap で適用前へ戻してから
 * nativeDeltaEval(before, writes) する。
 */
class NativeDeltaEvaluateHook(
    private val probe: NativeBridgeProbe,
    private val fullEvaluate: (Array<IntArray>) -> ViolationReport,
    /** 何手に1回フル評価で breakdown / パリティを取り直す */
    private val parityEvery: Int = 8_000,
) : DeltaEvaluateHook {

    private val deltaHits = AtomicLong(0)
    private val fullFalls = AtomicLong(0)
    private val parityHits = AtomicLong(0)
    private var moves = 0

    override fun evaluateAfterMove(
        schedule: Array<IntArray>,
        writes: IntArray,
        undoSnap: IntArray,
    ): ViolationReport {
        moves++
        if (parityEvery > 0 && moves % parityEvery == 0) {
            parityHits.incrementAndGet()
            fullFalls.incrementAndGet()
            return fullEvaluate(schedule)
        }
        val t = schedule.firstOrNull()?.size ?: 0
        if (t <= 0 || writes.isEmpty()) {
            fullFalls.incrementAndGet()
            return fullEvaluate(schedule)
        }
        val flat = ScheduleFlat.flatten(schedule)
        // 適用前盤面へ戻す（native は before + writes）
        var u = 0
        while (u + 2 < undoSnap.size) {
            val s = undoSnap[u]
            val d = undoSnap[u + 1]
            val old = undoSnap[u + 2]
            if (s in schedule.indices && d in 0 until t) {
                flat[s * t + d] = old
            }
            u += 3
        }
        val d = probe.deltaScore(flat, writes)
        if (d == null) {
            fullFalls.incrementAndGet()
            return fullEvaluate(schedule)
        }
        deltaHits.incrementAndGet()
        val hard = d.hardAfter.coerceAtLeast(0)
        val soft = d.softAfter.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return ViolationReports.fromDeltaPacked(hard, soft, d.afterPacked)
    }

    fun statsLine(): String =
        "deltaHits=${deltaHits.get()} fullFalls=${fullFalls.get()} parityHits=${parityHits.get()} moves=$moves"
}
