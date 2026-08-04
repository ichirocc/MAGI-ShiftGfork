package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import java.util.Random

/**
 * SearchSessionFull / SessionWithDelta の共通面。
 * G1 等が実装差に依存しないための完成インタフェース。
 */
interface TransitionSession : BoardView {
    val best: Array<IntArray>
    val currentReport: ViolationReport
    val bestReport: ViolationReport

    fun tryTransition(move: Move, mode: TransitionMode): TransitionResult
    fun tryMetropolis(
        move: Move,
        temperature: Double,
        rng: Random,
        packedScore: (ViolationReport) -> Long = PackedScore::of,
    ): TransitionResult
    fun tryLahc(
        move: Move,
        historyThreshold: Long,
        packedScore: (ViolationReport) -> Long = PackedScore::of,
    ): TransitionResult
}
