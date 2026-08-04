package com.magi.app.v6.engine.nativex

import com.magi.app.v6.ViolationReport
import com.magi.app.v6.engine.Move
import com.magi.app.v6.engine.NativeWriteBatch
import com.magi.app.v6.engine.TransitionMode
import com.magi.app.v6.engine.TransitionResult

/**
 * ## Native (JNI) 再設計方針
 *
 * 旧: C++ が schedule を直接書き換え + strongPerturb 専用経路 → pin/契約破壊の温床。
 * 新: **Native は「正規化済み writes バッチの適用・差分評価・一括 revert」専用**。
 * 採否・best 更新・pin 検査の最終権限は Kotlin [SearchSessionFull] が持つ。
 *
 * ### 二層防御
 * 1. Native 内部: writes の範囲・重複・canDo 相当を再検査（防御的）
 * 2. Kotlin 番兵: チャンク終了ごとに full Checker で照合。不一致なら NativeGate off → Kotlin 退化
 *
 * ### 並列
 * Problem handle は **ワーカーごとに1つ**（共有禁止）。旧共有 handle + join 問題を根絶。
 *
 * ### ABI（magi_native が実装すべき最小集合）
 * ```
 * jlong  nativeCreateProblem(flatProblemBytes)
 * void   nativeDestroyProblem(jlong)
 * // 0=reject 1=accept current 2=accept best-hint (Kotlin が best を再検証)
 * jint   nativeTryWrites(jlong, jlong baseVer, jintArray writes, jint mode, jdouble temp, jlong lahcThr)
 * jlong  nativePackedScore(jlong)  // hard*1e9 + softApprox — 番兵用
 * ```
 */
interface NativeMoveEngine {
    val available: Boolean
    fun tryWrites(batch: NativeWriteBatch, temperature: Double = 0.0): TransitionResult
    fun packedScore(): Long
    fun close()
}

/**
 * Native 未リンク時。常に Kotlin [com.magi.app.v6.engine.KotlinOnlyTransitionBridge] 相当。
 */
class NullNativeMoveEngine : NativeMoveEngine {
    override val available: Boolean = false
    override fun tryWrites(batch: NativeWriteBatch, temperature: Double) =
        TransitionResult.Rejected(com.magi.app.v6.engine.RejectReason.INVALID_RANGE)
    override fun packedScore(): Long = 0L
    override fun close() {}
}

/**
 * 番兵: Native 主張スコアと Kotlin Checker が一致しないとき閉じる。
 */
object NativeGate {
    @Volatile var enabled: Boolean = true
        private set
    fun disable(reason: String) {
        enabled = false
        lastReason = reason
    }
    fun reset() { enabled = true; lastReason = "" }
    @Volatile var lastReason: String = ""
        private set
}

/** Move → Native バッチ（mode 付き） */
fun Move.toNativeBatch(mode: TransitionMode, lahcThreshold: Long = Long.MAX_VALUE): NativeWriteBatch =
    NativeWriteBatch(
        baseVersion = baseVersion,
        writes = writes,
        mode = when (mode) {
            TransitionMode.STRICT -> 0
            TransitionMode.ANNEAL -> 1
            TransitionMode.LAHC -> 2
        },
        lahcThreshold = lahcThreshold,
    )
