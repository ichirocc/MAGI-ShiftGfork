package com.magi.app.v6

/**
 * [ネイティブ加速] C++ (NDK) ライブラリ magi_native との JNI 境界。
 *
 * 方針（ユーザー指示「ネイティブ開発言語にして実行速度改善」・ホットパス限定で合意）:
 * - Kotlin 実装（Evaluator/DeltaEvaluator/探索）を常に「正」として残す。
 * - C++ 側はホットパスの高速版。実行時に Kotlin Evaluator と同一盤面のパリティ照合を行い
 *   （NativeEval.parityCheck）、一致した場合のみネイティブ経路を使う。
 * - .so のロード失敗（未同梱ABI・JVMユニットテスト等）は available=false となり、
 *   全経路が従来どおり Kotlin で動く。機能差ゼロ・速度のみの最適化に限定する。
 *
 * Stage2: 平坦化 Problem の受け渡し（nativeCreateProblem）＋ C++ フル評価器（nativeFullEval）。
 */
object NativeBridge {
    /** Kotlin 側が期待する ABI バージョン。C++ 側と不一致ならネイティブ経路を使わない。 */
    const val ABI_VERSION = 7

    /** .so がロードでき、ABI が一致するときだけ true（初回参照時に一度だけ判定）。 */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("magi_native")
            nativeAbiVersion() == ABI_VERSION
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeAbiVersion(): Int

    /** 平坦化 Problem からネイティブ側の問題を構築（レイアウトは NativeEval.flatten と一致）。0=失敗。 */
    @JvmStatic
    external fun nativeCreateProblem(
        meta: IntArray, staff: IntArray, canDo: IntArray, wish: IntArray,
        needs: IntArray, ranges: IntArray, cons: IntArray, c3: IntArray,
        bucket: IntArray,
    ): Long

    @JvmStatic
    external fun nativeDestroyProblem(handle: Long)

    /** C++ フル評価（Evaluator.fullEvalParts と同値であるべき）。戻り値 [hard, soft]。失敗時 [-1,-1]。 */
    @JvmStatic
    external fun nativeFullEval(handle: Long, schedule: IntArray): LongArray

    /**
     * Move 契約: 正規化済み writes を schedule(S*T) に適用して採否。
     * 戻り値 [status, score, hard, soft]。status 0=reject 1=accept 2=accept_better。
     * 受理時のみ schedule を書き戻す。
     */
    @JvmStatic
    external fun nativeTryWrites(
        handle: Long,
        schedule: IntArray,
        writes: IntArray,
        mode: Int,
        temperature: Double,
        lahcThreshold: Long,
    ): LongArray

    /** [Stage3] SA チャンク（冷却ラダー1本）。cur/best は S*T 平坦・status==0 のときのみ書き戻し。
     *  戻り値 [status, curScore, bestScore, iters, improvedInChunk, tailIters]。 */
    @JvmStatic
    external fun nativeSaChunk(
        handle: Long, cur: IntArray, best: IntArray, bestScore: Long, seed: Long,
        t0: Double, tf: Double, alpha: Double, chain: Int,
    ): LongArray

    // ---- [Stage8] ALNS チャンク ----
    /** ALNS チャンク状態を生成（problemHandle＋初期盤面 cur）。accept: 0=SA 1=GD 2=Lam / opSel: 0=roulette 1=thompson。0=失敗。 */
    @JvmStatic
    external fun nativeAlnsCreate(problemHandle: Long, cur: IntArray, seed: Long, accept: Int, opSel: Int, explore: Double): Long
    @JvmStatic
    external fun nativeAlnsDestroy(handle: Long)
    /** 1チャンク実行。戻り値 [status, curScore, bestScore, bestImproved, iters, kicks]。status!=0 で退化。 */
    @JvmStatic
    external fun nativeAlnsChunk(handle: Long, iters: Int, frac: Double): LongArray
    /** which: 0=best, 1=cur を out(S*T) へ読み出す。 */
    @JvmStatic
    external fun nativeAlnsRead(handle: Long, which: Int, out: IntArray)

    // ---- [Stage10/第3期] Polish チャンク（hf80PostPolish の C++ 版） ----
    /** Polish チャンク状態を生成（problemHandle＋初期盤面）。0=失敗。 */
    @JvmStatic
    external fun nativePolishCreate(problemHandle: Long, cur: IntArray, seed: Long): Long
    @JvmStatic
    external fun nativePolishDestroy(handle: Long)
    /** 1チャンク実行。戻り値 [status, curScore, bestScore, bestImproved, iters]。status!=0 で退化。 */
    @JvmStatic
    external fun nativePolishChunk(handle: Long, iters: Int): LongArray
    /** which: 0=best, 1=cur を out(S*T) へ読み出す。 */
    @JvmStatic
    external fun nativePolishRead(handle: Long, which: Int, out: IntArray)

    // ---- [Stage11/第3期] LAHC チャンク（SaOptimizer PhaseB の C++ 版） ----
    /** LAHC チャンク状態を生成（problemHandle＋PhaseB 入口の best 盤面＋履歴長）。0=失敗。 */
    @JvmStatic
    external fun nativeLahcCreate(problemHandle: Long, board: IntArray, seed: Long, lahcLen: Int): Long
    @JvmStatic
    external fun nativeLahcDestroy(handle: Long)
    /** 1チャンク実行。戻り値 [status, curScore, bestScore, bestImproved, iters]。status!=0 で退化。 */
    @JvmStatic
    external fun nativeLahcChunk(handle: Long, iters: Int): LongArray
    /** which: 0=best, 1=cur を out(S*T) へ読み出す。 */
    @JvmStatic
    external fun nativeLahcRead(handle: Long, which: Int, out: IntArray)
}

/**
 * [退化不能設計] ネイティブ経路のセッション内ゲート。番兵（チャンク自己整合 or Kotlin照合）が
 * 一度でも発火したら以後このプロセスでは Kotlin のみで走る（クラッシュでなく退化）。
 * 理由は診断ログ（V6FinalPort の NativeBridge 行）に出す。
 */
object NativeGate {
    /** ユーザーの設定トグル（設定タブ「ネイティブ加速」）。番兵ゲート enabled とは独立。 */
    @Volatile var userEnabled: Boolean = true

    /** [照合トグル] Kotlin パリティ照合の実施可否（設定タブ「Kotlin照合」・既定ON・セッション内のみ）。
     *  OFF=純ネイティブ（C++結果を Kotlin と照合せず信頼＝検証/ベンチ用・誤結果の可能性）。
     *  番兵ゲート enabled / C++内部の自己整合(status) とは独立。OFF でも status 番兵は生きるため暴走はしない。 */
    @Volatile var parityCheckEnabled: Boolean = true
    @Volatile var enabled: Boolean = true
        private set
    @Volatile var reason: String? = null
        private set

    fun disable(why: String) { enabled = false; reason = why }

    /** ネイティブSAチャンクを使ってよいか（ロード成功×番兵ゲート開×ユーザーON）。 */
    val usable: Boolean get() = enabled && userEnabled && NativeBridge.available
}
