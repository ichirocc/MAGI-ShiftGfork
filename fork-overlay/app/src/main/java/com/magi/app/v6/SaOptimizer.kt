package com.magi.app.v6

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlin.math.min

/**
 * SA tunables. Defaults mirror the Web baseline SA-ver1 (t0=10, tf=0.1, a=0.975, ll=20).
 *
 * [softPolish] (default OFF) enables a faithful port of the Web PhaseB late-acceptance
 * SOFT-polish (LAHC, lahcHistoryLen=200). A worker switches into PhaseB only after its HARD
 * best has not improved for [hardStallMs] (i.e. the HARD floor is reached), and PhaseB is
 * HARD-guarded — it never accepts a move that raises the achieved HARD level, so it can only
 * reduce SOFT. Left off by default because on short, HARD-time-bound budgets uninterrupted
 * PhaseA SA is at least as good (see README, "Phase 4" — high run-to-run variance).
 */
data class SaParams(
    val t0: Double = 10.0,
    val tf: Double = 0.1,
    val alpha: Double = 0.975,
    val chain: Int = 20,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val budgetMs: Long = 8_000,
    val softPolish: Boolean = false,
    val hardStallMs: Long = 2_500,
    val lahcLen: Int = 200,
    /** 外部からの協調停止（停滞早期脱出・ユーザー停止）。true で各ワーカーは現在の best を返して終了。 */
    val shouldStop: () -> Boolean = { false },
    /** MagiConductor（UCB1で停滞脱出戦略を自律選択）を有効化。既定ON。停滞前は既定の reset-to-best 再加熱。 */
    val conductor: Boolean = true,
    /** Conductor の停滞しきい値（最良未更新の反復数）。これを超えると再加熱境界で脱出戦略を選ぶ。 */
    val conductorStag: Int = 3000,
    /** [多様化] 乱数シード。0=従来通り System.nanoTime()。多仮説では各仮説に異なる seed を渡して
     *  探索を多様化・再現可能にする（各ワーカーは内部で seed xor (w*定数) に分散）。 */
    val seed: Long = 0L,
)

data class SaProgress(val bestScore: Long, val totalIters: Long, val elapsedMs: Long)

data class SaResult(
    val schedule: Array<IntArray>,
    val score: Long,
    val totalIters: Long,
    val elapsedMs: Long,
    /**
     * [3.356.0] 各SAチェーンが「全体の最良」を更新した回数。設定タブの**並列ワーカー**は
     * V5(高速)経路ではそのままチェーン数になるが、旧ログはチェーン数も内訳も出さず、
     * 増やした意味があったかを判断できなかった。1本しか勝っていなければ残りは無駄と読める。
     */
    val chainWins: IntArray = IntArray(0),
)

/**
 * Parallel SA with incremental (delta) evaluation, a multi-operator neighbourhood, and an
 * optional HARD-guarded PhaseB SOFT-polish. Each coroutine worker owns a [DeltaEvaluator],
 * runs independently with its own RNG, and the global best is kept. The final best is
 * reconciled once with the full Evaluator as a safety net.
 */
/** ShiftGfork: 並列 SA はワーカー専用 native handle（共有禁止）。 */
class SaOptimizer(private val problem: Problem, private val evaluator: Evaluator) {

    private val M = SCORE_HARD_UNIT

    suspend fun run(
        params: SaParams = SaParams(),
        onProgress: (SaProgress) -> Unit = {},
    ): SaResult = coroutineScope {
        val init = problem.initialAssignment()
        val start = (System.nanoTime() / 1_000_000L)

        var globalBest = evaluator.fullEval(init)
        var globalBestSol = copyOf(init)
        var totalIters = 0L
        val chainWins = IntArray(params.workers.coerceAtLeast(1))
        val lock = Any()

        fun report() { onProgress(SaProgress(globalBest, totalIters, (System.nanoTime() / 1_000_000L) - start)) }
        report()

        // [MAGI-ShiftGfork 根本修正] 共有 nativeHandle を並列ワーカーが同時に nativeSaChunk すると
        //   C++ 内部の評価バッファ／オペレータ経路で SIGSEGV→強制終了し得る（実機: V5 SA 開始直後）。
        //   問題データは論理的に read-only でも、実装がワーカー横断の共有状態を持つ前提は置けない。
        //   → ワーカーごとに createHandle / destroy し、解放は各ワーカーの finally で完結させる。
        // [Stage11] PhaseB(LAHC) も C++ チャンク。softPolish 有効時もネイティブ経路を使う。
        val jobs = (0 until params.workers).map { w ->
            async(Dispatchers.Default) {
                val sbase = if (params.seed != 0L) params.seed else System.nanoTime()
                val seed = sbase xor (w.toLong() * -0x61c8864680b583ebL)
                val flush: (Long, Array<IntArray>, Long) -> Unit = { localBest, localSol, iters ->
                    synchronized(lock) {
                        totalIters += iters
                        if (localBest < globalBest) {
                            globalBest = localBest; globalBestSol = localSol; chainWins[w]++
                        }
                        report()
                    }
                }
                val nativeHandle = if (NativeGate.usable)
                    runCatching { NativeEval.createHandle(problem) }.getOrDefault(0L) else 0L
                try {
                    val nativeOk = if (nativeHandle != 0L && NativeGate.enabled)
                        runWorkerNative(nativeHandle, init, params, Random(seed), start, flush) else false
                    if (!nativeOk) runWorker(init, params, Random(seed), start, flush)
                } finally {
                    if (nativeHandle != 0L) {
                        runCatching { NativeBridge.nativeDestroyProblem(nativeHandle) }
                    }
                }
            }
        }
        try {
            jobs.awaitAll()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                jobs.forEach { job -> job.cancel(); runCatching { job.join() } }
            }
        }

        val finalScore = evaluator.fullEval(globalBestSol)
        synchronized(lock) { globalBest = finalScore; report() }
        SaResult(globalBestSol, finalScore, totalIters, (System.nanoTime() / 1_000_000L) - start, chainWins)
    }

    /**
     * [ネイティブ加速 Stage3] PhaseA を「1チャンク=1冷却ラダー」で C++ に委譲するワーカー。
     * Kotlin が保持するもの: 予算/キャンセル（チャンク間で確認）・進捗 flush・MagiConductor の境界処理
     * （reset-to-best / 脱出戦略）・**2層目の番兵**（チャンク best 更新時に Kotlin Evaluator でフル照合）。
     * 番兵発火時は NativeGate を閉じて false を返し、呼び出し側が Kotlin ワーカーへ退化させる。
     * 戻り値 true=予算まで走了（best は flush 済み）。
     */
    private suspend fun runWorkerNative(
        handle: Long,
        init: Array<IntArray>,
        params: SaParams,
        rng: Random,
        start: Long,
        flush: (Long, Array<IntArray>, Long) -> Unit,
    ): Boolean {
        val s = problem.S; val t = problem.T
        val cur = NativeEval.flatten(init)
        val best = cur.copyOf()
        var bestScore = evaluator.fullEval(init)   // 初期スコアは Kotlin（正）で確定
        val conductor = if (params.conductor) MagiConductor(params.conductorStag) else null
        var pendingAction: ConductorAction? = null
        var bestAtAction = bestScore
        // [Stage11] PhaseB(LAHC) 切替用: HARD 水準が最後に下がった時刻（Kotlin runWorker と同じ hardStallMs 判定。
        //   検知粒度はラダー境界＝1チャンク（数千反復・ms級）で、毎反復判定の Kotlin より僅かに粗いだけ）。
        var lastHardImprove = (System.nanoTime() / 1_000_000L)
        // [全体計算の最小化] 直近の照合済み best 盤面キャッシュ（非改善チャンクの flush 用）。
        var lastSol: Array<IntArray> = NativeEval.unflatten(best, s, t)
        // [敵対的レビュー: NativeGate伝播] 兄弟ワーカーが番兵発火でゲートを閉じたら、このワーカーも
        //   次チャンク前に停止する（自チャンクは全て個別に自己整合/照合済みのため、ここまでの進捗
        //   flush済みの結果はそのまま採用＝退化ではなく単なる早期終了。予算超過と同じ扱い）。
        fun timeUp() = params.shouldStop() || !NativeGate.enabled || (System.nanoTime() / 1_000_000L) - start >= params.budgetMs

        while (!timeUp()) {
            coroutineContext.ensureActive()
            val ret = runCatching {
                NativeBridge.nativeSaChunk(handle, cur, best, bestScore, rng.nextLong(),
                    params.t0, params.tf, params.alpha, params.chain)
            }.getOrNull()
            if (ret == null || ret.size < 6 || ret[0] != 0L) {
                NativeGate.disable("SAチャンク整合性NG (status=${ret?.getOrNull(0)})")
                return false
            }
            val newBest = ret[2]
            if (newBest < bestScore) {
                // [2層目の番兵] 採用前に Kotlin フル再評価で照合（Long の == 比較・許容誤差なし）。
                //   [照合トグル] OFF=純ネイティブ（照合せず C++結果を信頼）。C++自己整合(status)は上で常時検査済。
                val bestSol = NativeEval.unflatten(best, s, t)
                if (NativeGate.parityCheckEnabled) {
                    TuningTelemetry.parityChecks.incrementAndGet()
                    val kotlinScore = evaluator.fullEval(bestSol)
                    if (!NativeParityGate.assertPackedOrDisable(newBest, kotlinScore, "SA-chunk")) {
                        return false
                    }
                }
                if (newBest / M < bestScore / M) lastHardImprove = (System.nanoTime() / 1_000_000L)
                bestScore = newBest
                lastSol = bestSol
                flush(bestScore, bestSol, ret[3])
            } else {
                // [全体計算の最小化] 非改善チャンクは直近の照合済み best（キャッシュ）を渡す。
                //   flush は localBest < globalBest のときしか盤面を読まず、非改善では勝てないため
                //   毎チャンクの unflatten（全面コピー＋確保）は純粋な無駄だった。
                flush(bestScore, lastSol, ret[3])
            }
            if (timeUp()) return true
            // [Stage11] HARD 床到達（hardStallMs 無改善）で PhaseB=LAHC ソフト研磨へ恒久切替
            //   （Kotlin runWorker の phaseB=true と同じ一方向遷移。以後は予算末まで LAHC）。
            if (params.softPolish && (System.nanoTime() / 1_000_000L) - lastHardImprove > params.hardStallMs) {
                TuningTelemetry.lahcEntered.incrementAndGet()
                return runLahcNative(handle, best, bestScore, params, rng, start, flush)
            }

            // ---- ラダー境界: 従来 PhaseA の reheat / MagiConductor 脱出戦略と同じ分岐 ----
            conductor?.updateStagnationBulk(ret[4] == 1L, ret[5].toInt())
            if (conductor == null) {
                best.copyInto(cur)   // 既定の reset-to-best 再加熱
            } else {
                pendingAction?.let { conductor.updateReward(it, if (bestScore < bestAtAction) 1.0 else 0.0) }
                when (conductor.selectAction()) {
                    ConductorAction.STRONG_PERTURB -> { best.copyInto(cur); strongPerturbFlat(cur, rng); pendingAction = ConductorAction.STRONG_PERTURB }
                    ConductorAction.SCALE_TEMP -> { pendingAction = ConductorAction.SCALE_TEMP }   // 現在解を保持（温度倍率なし＝次ラダーで t0 再加熱、REHEAT との差は盤面のみ）
                    ConductorAction.REHEAT -> { best.copyInto(cur); pendingAction = ConductorAction.REHEAT }
                    ConductorAction.NOOP -> { best.copyInto(cur); pendingAction = null }
                }
                bestAtAction = bestScore
            }
        }
        return true
    }

    /**
     * [Stage11] PhaseB=LAHC を C++ チャンクで回す。Kotlin runWorker PhaseB と同一意味論
     * （hist/bestHard/bIt は C++ 側 LahcState がチャンク跨ぎで保持・一方向遷移で予算末まで）。
     * Kotlin が保持: 予算/キャンセル（チャンク間）・進捗 flush・2層目番兵（best 改善チャンクを
     * Kotlin Evaluator.fullEval で Long== 照合）。番兵発火時は NativeGate を閉じ false を返し、
     * 呼び出し側（run()）がそのワーカーを Kotlin runWorker で走らせ直す（退化・クラッシュなし）。
     */
    private suspend fun runLahcNative(
        handle: Long,
        bestFlat: IntArray,
        bestScoreIn: Long,
        params: SaParams,
        rng: Random,
        start: Long,
        flush: (Long, Array<IntArray>, Long) -> Unit,
    ): Boolean {
        val s = problem.S; val t = problem.T
        var bestScore = bestScoreIn
        // [敵対的レビュー: NativeGate伝播] runWorkerNativeと同じ理由でゲート閉鎖を停止条件へ加える。
        fun timeUp() = params.shouldStop() || !NativeGate.enabled || (System.nanoTime() / 1_000_000L) - start >= params.budgetMs
        val h = NativeBridge.nativeLahcCreate(handle, bestFlat, rng.nextLong(), params.lahcLen)
        if (h == 0L) { NativeGate.disable("LAHC状態生成NG"); return false }
        try {
            // [全体計算の最小化] 直近の照合済み best 盤面キャッシュ（非改善チャンクの flush 用）。
            var lastSol: Array<IntArray> = NativeEval.unflatten(bestFlat, s, t)
            while (!timeUp()) {
                coroutineContext.ensureActive()
                val ret = runCatching { NativeBridge.nativeLahcChunk(h, 4000) }.getOrNull()
                if (ret == null || ret.size < 5 || ret[0] != 0L) {
                    NativeGate.disable("LAHCチャンク整合性NG (status=${ret?.getOrNull(0)})")
                    return false
                }
                if (ret[2] < bestScore) {
                    // [2層目の番兵] best 更新チャンクの盤面を Kotlin フル評価で照合（Long== 許容誤差なし）。
                    //   [照合トグル] OFF=純ネイティブ（照合せず信頼）。C++自己整合(status)は上で常時検査済。
                    NativeBridge.nativeLahcRead(h, 0, bestFlat)
                    val sol = NativeEval.unflatten(bestFlat, s, t)
                    if (NativeGate.parityCheckEnabled) {
                        TuningTelemetry.parityChecks.incrementAndGet()
                        val kotlinScore = evaluator.fullEval(sol)
                        if (kotlinScore != ret[2]) {
                            NativeGate.disable("LAHC Kotlin照合NG (native=${ret[2]} kotlin=$kotlinScore)")
                            return false
                        }
                    }
                    bestScore = ret[2]
                    lastSol = sol
                    flush(bestScore, sol, ret[4])
                } else {
                    flush(bestScore, lastSol, ret[4])
                }
            }
            return true
        } finally {
            NativeBridge.nativeLahcDestroy(h)
        }
    }

    /** strongPerturb の平坦配列版（従来: best から数手の単発移動で離す。スコア不要＝次チャンクが再計算）。
     *
     *  [3.336.0/敵対レビュー H1] **3.334.0 の取り残し**。Kotlin 側の `strongPerturb()` は `opSingle()` 経由で
     *  希望固定を避けるのに、ネイティブ経路のこちらだけが見ていなかった。既定はネイティブ経路なので、
     *  停滞のたびに Conductor がここを呼び、入口で盤面へ載せた実現可能な希望を上書きしていた。
     *  最終解は fullEval 番兵と keep-best が守るが、「探索は実現可能な希望を動かさない」という契約は
     *  破れていた（`opSingle` と同じく日を4回まで引き直し、それでも固定なら見送る）。 */
    //  ホスト JVM は `.so` を読めずネイティブ経路（唯一の呼出元）が走らないため、**間接テストでは
    //  この関数のガードを検証できない**（実際、バグを戻しても間接テストは通ってしまった）。直接呼ぶために internal。
    internal fun strongPerturbFlat(cur: IntArray, rng: Random) {
        val s = problem.S; val t = problem.T
        repeat(4 + rng.nextInt(8)) {
            val i = rng.nextInt(s)
            var j = rng.nextInt(t)
            var tries = 0
            while (problem.wishLocked(i, j) && tries < 4) { j = rng.nextInt(t); tries++ }
            if (problem.wishLocked(i, j)) return@repeat
            val b = problem.bucket[problem.sgrp[i]]
            if (b.isNotEmpty()) cur[i * t + j] = b[rng.nextInt(b.size)]
        }
    }

    private suspend fun runWorker(
        init: Array<IntArray>,
        params: SaParams,
        rng: Random,
        start: Long,
        flush: (Long, Array<IntArray>, Long) -> Unit,
    ) {
        val S = problem.S; val T = problem.T
        val de = DeltaEvaluator(problem)
        de.reset(init)
        var curVal = de.score()
        var best = curVal
        var bestSol = de.snapshot()
        var bestHard = best / M
        var lastHardImprove = (System.nanoTime() / 1_000_000L)

        val cap = T + S + 16
        val bi = IntArray(cap); val bj = IntArray(cap); val bOld = IntArray(cap)
        var bn = 0
        fun applyCell(i: Int, j: Int, nw: Int) {
            if (bn >= cap) return
            bi[bn] = i; bj[bn] = j; bOld[bn] = de.at(i, j); bn++
            de.apply(i, j, nw)
        }
        fun revert() { var k = bn - 1; while (k >= 0) { de.apply(bi[k], bj[k], bOld[k]); k-- }; bn = 0 }
        fun randShiftFor(i: Int): Int {
            val b = problem.bucket[problem.sgrp[i]]
            return if (b.isEmpty()) de.at(i, 0) else b[rng.nextInt(b.size)]
        }
        // [3.334.0] 近傍は**実現可能な希望が入ったセルを触らない**。後処理研磨の全パスと C++ の修復
        //   オペレータは元から `wishLocked` を見ているのに、探索の近傍だけが見ていなかった（非対称）。
        //   採点は元から正しい（pref は hard＝希望を破ると差分が 1e9 単位で増え Metropolis はほぼ必ず却下）
        //   ので誤った勤務表は出ないが、**手の 35〜36% がその却下される手に費やされていた**（実測）。
        //   入口の hf67HardRepair が実現可能な希望を先に盤面へ入れるので、触らなければ正しいまま残る。
        fun locked(i: Int, j: Int) = problem.wishLocked(i, j)
        fun opSingle() {
            val i = rng.nextInt(S)
            var j = rng.nextInt(T)
            var tries = 0
            while (locked(i, j) && tries < 4) { j = rng.nextInt(T); tries++ }
            if (locked(i, j)) return
            val b = problem.bucket[problem.sgrp[i]]
            if (b.isEmpty()) return
            applyCell(i, j, b[rng.nextInt(b.size)])
        }
        fun opSwapDays() {
            val i = rng.nextInt(S)
            if (T < 2) return
            val j1 = rng.nextInt(T); var j2 = rng.nextInt(T)
            if (j1 == j2) j2 = (j2 + 1) % T
            if (locked(i, j1) || locked(i, j2)) return
            val o1 = de.at(i, j1); val o2 = de.at(i, j2)
            if (o1 == o2) return
            applyCell(i, j1, o2); applyCell(i, j2, o1)
        }
        fun opBlockFill() {
            val cs = problem.cons1
            if (cs.isEmpty()) { opSingle(); return }
            val c = cs[rng.nextInt(cs.size)]
            val pool = problem.staffForShift[c.shiftIdx]
            if (pool.isEmpty()) { opSingle(); return }
            val i = pool[rng.nextInt(pool.size)]
            val maxStart = T - c.day1
            if (maxStart < 0) { opSingle(); return }
            val js = rng.nextInt(maxStart + 1)
            // [3.341.0] 固定セルを飛ばして「部分的に埋まった窓」を作らない。窓を埋めるのがこの手の
            //   意図で、途中が抜けた窓はその意図を果たさないまま多数のセルを壊すだけだった。
            var q = 0
            while (q < c.day1) { if (locked(i, js + q)) return; q++ }
            var l = 0
            while (l < c.day1) { applyCell(i, js + l, c.shiftIdx); l++ }
        }
        fun opLns() {
            // [3.341.0] 破壊する集合を先に決め、固定セルが混ざっていたら手ごと見送る（部分適用しない）。
            when (rng.nextInt(3)) {
                0 -> { val i = rng.nextInt(S); val cnt = 2 + rng.nextInt(min(7, T))
                    val js = IntArray(cnt) { rng.nextInt(T) }
                    if (js.any { locked(i, it) }) return
                    for (j in js) applyCell(i, j, randShiftFor(i)) }
                1 -> { val j = rng.nextInt(T)
                    for (i in 0 until S) if (locked(i, j)) return
                    for (i in 0 until S) applyCell(i, j, randShiftFor(i)) }
                else -> { val cnt = 3 + rng.nextInt(8)
                    val cells = Array(cnt) { intArrayOf(rng.nextInt(S), rng.nextInt(T)) }
                    if (cells.any { locked(it[0], it[1]) }) return
                    for (c2 in cells) applyCell(c2[0], c2[1], randShiftFor(c2[0])) }
            }
        }
        val hasC1 = problem.cons1.isNotEmpty()
        fun pickOperator() {
            when (val r = rng.nextInt(100)) {
                in 0 until 60 -> opSingle()
                in 60 until 80 -> opSwapDays()
                in 80 until 92 -> if (hasC1) opBlockFill() else opSingle()
                else -> opLns()
            }
        }

        var itersSinceFlush = 0L
        val flushEvery = 8000
        var phaseB = false
        var hist = LongArray(0)
        var bIt = 0L

        // MagiConductor: 停滞時に UCB1 で脱出戦略を自律選択。停滞前は NoOp＝既定の reset-to-best 再加熱。
        val conductor = if (params.conductor) MagiConductor(params.conductorStag) else null
        var pendingAction: ConductorAction? = null   // 前境界で適用した脱出戦略（報酬を次境界で評価）
        var bestAtAction = best
        // strongPerturb 用: best から数手だけ単発移動して離す（コミット＝undo破棄）。
        fun strongPerturb() {
            val moves = 4 + rng.nextInt(8)
            bn = 0
            repeat(moves) { opSingle() }
            bn = 0
        }

        fun timeUp() = params.shouldStop() || (System.nanoTime() / 1_000_000L) - start >= params.budgetMs

        while (!timeUp()) {
            coroutineContext.ensureActive()

            if (!phaseB) {
                // ----- PhaseA: SA, reset-to-best reheat at cooling completion -----
                var t = params.t0
                cooling@ while (t >= params.tf && !timeUp()) {
                    // [review A] 冷却（温度）ステップ毎にキャンセルを検出。flush境界(flushEvery反復)を
                    //   待たずに兄弟ワーカーの停止・スコープ取消へ素早く応答する（ensureActive は軽量）。
                    coroutineContext.ensureActive()
                    var ls = 0
                    while (ls < params.chain) {
                        bn = 0
                        pickOperator()
                        val cand = de.score()
                        val dE = cand - curVal
                        var improvedBest = false
                        if (dE <= 0 || exp(-dE.toDouble() / t) > rng.nextDouble()) {
                            curVal = cand
                            if (cand < best) {
                                if (cand / M < bestHard) { bestHard = cand / M; lastHardImprove = (System.nanoTime() / 1_000_000L) }
                                best = cand; de.snapshotInto(bestSol); improvedBest = true
                            }
                            bn = 0
                        } else revert()
                        conductor?.updateStagnation(improvedBest)

                        itersSinceFlush++
                        if (itersSinceFlush >= flushEvery) {
                            flush(best, copyOf(bestSol), itersSinceFlush); itersSinceFlush = 0
                            coroutineContext.ensureActive()
                            if (timeUp()) { flush(best, copyOf(bestSol), 0); return }
                        }
                        if (params.softPolish && (System.nanoTime() / 1_000_000L) - lastHardImprove > params.hardStallMs) {
                            TuningTelemetry.lahcEntered.incrementAndGet()
                            phaseB = true; break@cooling
                        }
                        ls++
                    }
                    t *= params.alpha
                }
                // reheat / escape (MagiConductor) / or enter PhaseB from the best
                if (phaseB || conductor == null) {
                    de.reset(bestSol); curVal = best
                } else {
                    // 前回の脱出戦略の効果を報酬として学習（best が改善したか）。
                    pendingAction?.let { conductor.updateReward(it, if (best < bestAtAction) 1.0 else 0.0) }
                    when (conductor.selectAction()) {
                        ConductorAction.STRONG_PERTURB -> { de.reset(bestSol); strongPerturb(); curVal = de.score(); pendingAction = ConductorAction.STRONG_PERTURB }
                        ConductorAction.SCALE_TEMP -> { curVal = de.score(); pendingAction = ConductorAction.SCALE_TEMP }   // best へ戻さず現在解を保持（温度倍率なし＝次ラダーで t0 再加熱）
                        ConductorAction.REHEAT -> { de.reset(bestSol); curVal = best; pendingAction = ConductorAction.REHEAT }
                        ConductorAction.NOOP -> { de.reset(bestSol); curVal = best; pendingAction = null }   // 既定の reset-to-best
                    }
                    bestAtAction = best
                }
                if (phaseB) { hist = LongArray(params.lahcLen) { curVal }; bIt = 0 }
            } else {
                // ----- PhaseB: HARD-guarded LAHC SOFT polish -----
                bn = 0
                pickOperator()
                val cand = de.score()
                val candHard = cand / M
                val v = hist[(bIt % params.lahcLen).toInt()]
                if (candHard <= bestHard && (cand <= v || cand <= curVal)) {
                    curVal = cand
                    if (candHard < bestHard) bestHard = candHard
                    if (cand < best) { best = cand; de.snapshotInto(bestSol) }
                    bn = 0
                } else revert()
                if (curVal < hist[(bIt % params.lahcLen).toInt()]) hist[(bIt % params.lahcLen).toInt()] = curVal
                bIt++

                itersSinceFlush++
                if (itersSinceFlush >= flushEvery) {
                    flush(best, copyOf(bestSol), itersSinceFlush); itersSinceFlush = 0
                    coroutineContext.ensureActive()
                    if (timeUp()) { flush(best, copyOf(bestSol), 0); return }
                }
            }
        }
        flush(best, copyOf(bestSol), itersSinceFlush)
    }

    private fun copyOf(a: Array<IntArray>): Array<IntArray> = Array(a.size) { a[it].copyOf() }
}
