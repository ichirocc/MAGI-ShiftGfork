package com.magi.app.v6.engine.nativex

import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.ViolationReports
import com.magi.app.v6.engine.DeltaEvaluateHook
import com.magi.app.v6.engine.HardFamilyDelta
import java.util.concurrent.atomic.AtomicLong

/**
 * ホットパスは Native 差分のみ。フル評価は失敗時・パリティ周期のみ。
 *
 * [SearchSessionFull] は apply 後盤面を渡すため、undoSnap で適用前へ戻してから
 * nativeDeltaEval(before, writes) する。
 */
class NativeDeltaEvaluateHook(
    private val problem: Problem,
    private val probe: NativeBridgeProbe,
    private val fullEvaluate: (Array<IntArray>) -> ViolationReport,
    /** 何手に1回フル評価で breakdown / パリティを取り直す */
    private val parityEvery: Int = 8_000,
    /** 差分ヒット時に covU/c3n を軽量再集計して breakdown に載せる */
    private val partialHardBreakdown: Boolean = true,
    /**
     * [soft全族] 差分ヒット時に soft 15族（[MirrorKeys.all] − [MirrorKeys.hard]）の内訳を breakdown へ
     * 混ぜる。HardFamilyDelta と違い soft 族専用の軽量再集計は持たない — weekly(S×K×7) を筆頭に
     * 19族フルの再走査を毎 delta hit（300秒予算で数百万回に達しうる）で払うと、native 差分ホットパス
     * の高速化を打ち消しかねないため。代わりに [parityEvery] 周期で既に呼んでいる fullEvaluate() の
     * 結果（本物の UnifiedViolationChecker.check() が返す正確な breakdown）から soft 部分だけを
     * キャッシュし、次の parityEvery 境界まで使い回す。新規計算コストは実質ゼロ（既存の周期評価に
     * 相乗り）・鮮度は parityEvery 周期（既定8000手）に一致。
     */
    private val partialSoftBreakdown: Boolean = true,
) : DeltaEvaluateHook {

    private val deltaHits = AtomicLong(0)
    private val fullFalls = AtomicLong(0)
    private val parityHits = AtomicLong(0)
    private var moves = 0
    private var cachedSoftBreakdown: Map<String, Int> = emptyMap()

    /**
     * [メモリ削減] evaluateAfterMove は SA の探索ループから accept 判定のたび（=SearchSessionFull.prepare
     * 経由で毎手）呼ばれるホットパス。盤面の形（S×T）は1回の optimize() 実行中は不変なので、毎回
     * IntArray(S*T) を新規確保する代わりに使い回すスクラッチバッファへ書き込む（GC 圧迫の削減）。
     * NativeDeltaEvaluateHook は MainOptimizeBridge.optimize が1回の optimize() ごとに1個だけ生成し
     * 単一の SearchSessionFull へ渡すため、単一スレッドからしか呼ばれない（MAX_PARALLEL=1 が
     * EngineFacade.optimize の workers.coerceIn で保証）。将来 MAX_PARALLEL を上げる場合は
     * ワーカーごとに別インスタンスにするか、このスクラッチを分離すること。
     */
    private var flatScratch: IntArray? = null

    override fun evaluateAfterMove(
        schedule: Array<IntArray>,
        writes: IntArray,
        undoSnap: IntArray,
    ): ViolationReport {
        moves++
        if (parityEvery > 0 && moves % parityEvery == 0) {
            parityHits.incrementAndGet()
            fullFalls.incrementAndGet()
            val full = fullEvaluate(schedule)
            if (partialSoftBreakdown) {
                cachedSoftBreakdown = full.breakdown.filterKeys { it !in MirrorKeys.hard }
            }
            return full
        }
        val t = schedule.firstOrNull()?.size ?: 0
        if (t <= 0 || writes.isEmpty()) {
            fullFalls.incrementAndGet()
            return fullEvaluate(schedule)
        }
        val need = schedule.size * t
        val flat = flatScratch?.takeIf { it.size == need } ?: IntArray(need).also { flatScratch = it }
        ScheduleFlat.flattenInto(schedule, flat)
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
        val hardBd = if (partialHardBreakdown) {
            HardFamilyDelta.breakdown(HardFamilyDelta.count(problem, schedule))
        } else {
            emptyMap()
        }
        // soft 側は再集計せず、直近の parityEvery 境界でキャッシュした値をそのまま乗せる
        // （鮮度は parityEvery 周期。新規計算なし＝ホットパスへの追加コストなし）。
        val bd = if (partialSoftBreakdown && cachedSoftBreakdown.isNotEmpty()) {
            hardBd + cachedSoftBreakdown
        } else {
            hardBd
        }
        return ViolationReports.fromDeltaPacked(hard, soft, d.afterPacked, breakdown = bd)
    }

    fun statsLine(): String =
        "deltaHits=${deltaHits.get()} fullFalls=${fullFalls.get()} parityHits=${parityHits.get()} moves=$moves"
}
