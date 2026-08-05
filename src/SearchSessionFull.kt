package com.magi.app.v6.engine

import com.magi.app.v6.MirrorLog
import com.magi.app.v6.Problem
import com.magi.app.v6.ViolationReport

import java.util.Random
import kotlin.math.exp

/**
 * 再構築の正本 Session（完成版）。
 * evaluate = { UnifiedViolationChecker.check(state, it) }
 * better   = MirrorCore::betterReport
 */
class SearchSessionFull(
    private val problem: Problem,
    initial: Array<IntArray>,
    private val evaluate: (Array<IntArray>) -> ViolationReport,
    private val better: (ViolationReport, ViolationReport) -> Boolean,
    private val exactCountPolicy: ExactCountPolicy = ExactCountPolicy.FORBID_WORSEN,
    private val useBitMasks: Boolean = problem.S <= 64 && problem.T <= 64,
    private val deltaHook: DeltaEvaluateHook? = null,
) : BoardView, TransitionSession {
    override var version: Long = 0L
        private set
    override var current: Array<IntArray> = deepCopy(initial)
        private set
    override var best: Array<IntArray> = deepCopy(current)
        private set
    override var currentReport: ViolationReport = evaluate(current)
        private set
    override var bestReport: ViolationReport = currentReport
        private set

    private val undo = UndoStack()
    private val counts = StaffShiftCounts(problem.S, problem.K)
    private val bits: BitMasks? =
        if (useBitMasks) BitMasks(problem.S, problem.T, problem.K) else null
    private var lastReject: TransitionResult = TransitionResult.Rejected(RejectReason.NOOP)
    private val touchBuf = LongArray(128)

    init {
        ProblemGuards.requireRunnable(problem)
        require(ProblemGuards.scheduleShapeOk(problem, initial)) {
            "schedule shape must be SxT"
        }
        counts.rebuildFrom(current, problem.T)
        bits?.let {
            it.rebuildFrom(current)
            it.setWishLockFrom(problem)
        }
    }

    override fun tryTransition(move: Move, mode: TransitionMode): TransitionResult {
        val prepared = prepare(move) ?: return lastReject
        val (rep, snap) = prepared
        return when (mode) {
            TransitionMode.STRICT -> {
                if (!better(rep, currentReport)) {
                    revertSnap(snap)
                    TransitionResult.Rejected(RejectReason.NOT_BETTER)
                } else {
                    commit(rep)
                    promoteBest(rep)
                }
            }
            // ANNEAL: soft 悪化は current のみ許可。HARD 増加は却下（best は promoteBest で better のみ）。
            TransitionMode.ANNEAL -> {
                if (rep.hard > currentReport.hard) {
                    revertSnap(snap)
                    TransitionResult.Rejected(RejectReason.NOT_BETTER)
                } else {
                    commit(rep)
                    promoteBest(rep)
                }
            }
            // LAHC: 閾値判定は tryLahc 専用。ここ経由は誤用防止のため STRICT 相当。
            TransitionMode.LAHC -> {
                if (!better(rep, currentReport)) {
                    revertSnap(snap)
                    TransitionResult.Rejected(RejectReason.NOT_BETTER)
                } else {
                    commit(rep)
                    promoteBest(rep)
                }
            }
        }
    }

    /**
     * Metropolis。盤面は provisional 適用し、却下時は version を進めずに revert。
     * tryTransition(ANNEAL) は「受理確定後」専用。温度判定には本メソッドを使うこと。
     */
    override fun tryMetropolis(
        move: Move,
        temperature: Double,
        rng: Random,
        packedScore: (ViolationReport) -> Long,
    ): TransitionResult {
        val scoreBefore = packedScore(currentReport)
        val prepared = prepare(move) ?: return lastReject
        val (rep, snap) = prepared
        // HARD 増加は温度に関係なく却下（再加熱 hot=1e9 でも best 以外の current を壊さない）
        if (rep.hard > currentReport.hard) {
            revertSnap(snap)
            return TransitionResult.Rejected(RejectReason.NOT_BETTER)
        }
        val scoreAfter = packedScore(rep)
        if (scoreAfter > scoreBefore) {
            val delta = (scoreAfter - scoreBefore).toDouble()
            if (!Metropolis.accept(delta, temperature, rng)) {
                revertSnap(snap)
                return TransitionResult.Rejected(RejectReason.NOT_BETTER)
            }
        }
        commit(rep)
        return promoteBest(rep)
    }

    override fun tryLahc(
        move: Move,
        historyThreshold: Long,
        packedScore: (ViolationReport) -> Long,
    ): TransitionResult {
        val hardBefore = currentReport.hard
        val prepared = prepare(move) ?: return lastReject
        val (rep, snap) = prepared
        if (rep.hard > hardBefore) {
            revertSnap(snap)
            return TransitionResult.Rejected(RejectReason.NOT_BETTER)
        }
        if (packedScore(rep) > historyThreshold) {
            revertSnap(snap)
            return TransitionResult.Rejected(RejectReason.NOT_BETTER)
        }
        commit(rep)
        return promoteBest(rep)
    }

    private fun prepare(move: Move): Pair<ViolationReport, IntArray>? {
        if (move.baseVersion != version) {
            lastReject = TransitionResult.Rejected(RejectReason.STALE_VERSION)
            return null
        }
        val w = move.writes
        val n = w.size
        if (n == 0 || n % 3 != 0) {
            lastReject = TransitionResult.Rejected(RejectReason.INVALID_RANGE)
            return null
        }
        val seen = HashSet<Long>(move.cellCount * 2)
        var anyChange = false
        var i = 0
        while (i < n) {
            val s = w[i]; val d = w[i + 1]; val sh = w[i + 2]
            if (s !in 0 until problem.S || d !in 0 until problem.T || sh !in 0 until problem.K) {
                lastReject = TransitionResult.Rejected(RejectReason.INVALID_RANGE)
                return null
            }
            val key = (s.toLong() shl 32) or (d.toLong() and 0xffffffffL)
            if (!seen.add(key)) {
                lastReject = TransitionResult.Rejected(RejectReason.DUPLICATE_CELL)
                return null
            }
            if (current[s][d] != sh) anyChange = true
            i += 3
        }
        if (!anyChange) {
            lastReject = TransitionResult.Rejected(RejectReason.NOOP)
            return null
        }
        // exact-pin 追跡バッファ超過は不正（部分適用禁止）
        if (move.cellCount > touchBuf.size) {
            lastReject = TransitionResult.Rejected(RejectReason.INVALID_RANGE)
            return null
        }
        i = 0
        while (i < n) {
            val s = w[i]; val d = w[i + 1]; val sh = w[i + 2]
            val old = current[s][d]
            val locked = bits?.isWishLocked(s, d) ?: problem.wishLocked(s, d)
            if (old != sh && locked) {
                lastReject = TransitionResult.Rejected(RejectReason.WISH_PIN)
                return null
            }
            if (!problem.canDo(s, sh)) {
                lastReject = TransitionResult.Rejected(RejectReason.NOT_ALLOWED)
                return null
            }
            if (exactCountPolicy == ExactCountPolicy.IMMUTABLE && isExactCountPin(s, old) && old != sh) {
                lastReject = TransitionResult.Rejected(RejectReason.EXACT_COUNT)
                return null
            }
            i += 3
        }
        // touch キーと変更前カウントを先に集め、適用後に「充足ピンの悪化」だけ見る
        undo.clear()
        undo.ensureCapacity(n / 3)
        var nTouch = 0
        val beforeCnt = IntArray(touchBuf.size)
        i = 0
        while (i < n) {
            val s = w[i]; val d = w[i + 1]; val sh = w[i + 2]
            val old = current[s][d]
            if (old != sh) {
                fun touch(keySh: Int) {
                    if (keySh !in 0 until problem.K) return
                    val key = (s.toLong() shl 32) or (keySh.toLong() and 0xffffffffL)
                    // 重複キーは before を最初の値のままにする
                    var exists = false
                    for (t in 0 until nTouch) if (touchBuf[t] == key) { exists = true; break }
                    if (!exists) {
                        touchBuf[nTouch] = key
                        beforeCnt[nTouch] = counts.get(s, keySh)
                        nTouch++
                    }
                }
                touch(old)
                touch(sh)
            }
            i += 3
        }
        i = 0
        while (i < n) {
            val s = w[i]; val d = w[i + 1]; val sh = w[i + 2]
            val old = current[s][d]
            undo.record(s, d, old)
            current[s][d] = sh
            counts.applyChange(s, old, sh)
            bits?.reassign(s, d, old, sh)
            i += 3
        }
        val snap = undo.snapshot()
        if (exactCountPolicy == ExactCountPolicy.FORBID_WORSEN &&
            counts.regressesSatisfiedExactPins(problem, touchBuf, beforeCnt, nTouch)
        ) {
            revertSnap(snap)
            lastReject = TransitionResult.Rejected(RejectReason.EXACT_COUNT)
            return null
        }
        return try {
            val rep = if (deltaHook != null) {
                deltaHook.evaluateAfterMove(current, w, snap)
            } else {
                evaluate(current)
            }
            rep to snap
        } catch (e: Exception) {
            revertSnap(snap)
            lastReject = TransitionResult.Rejected(RejectReason.INVALID_RANGE)
            null
        }
    }

    private fun commit(rep: ViolationReport) {
        currentReport = rep
        version++
        undo.clear()
    }

    private fun promoteBest(rep: ViolationReport): TransitionResult {
        if (better(rep, bestReport)) {
            best = deepCopy(current)
            bestReport = rep
            return TransitionResult.AcceptedBest
        }
        return TransitionResult.AcceptedCurrent
    }

    private fun revertSnap(snap: IntArray) {
        var u = snap.size - 3
        while (u >= 0) {
            val s = snap[u]; val d = snap[u + 1]; val oldSh = snap[u + 2]
            val curSh = current[s][d]
            current[s][d] = oldSh
            counts.revertChange(s, oldSh, curSh)
            bits?.reassign(s, d, curSh, oldSh)
            u -= 3
        }
        undo.clear()
    }

    private fun isExactCountPin(staff: Int, shift: Int): Boolean {
        if (shift !in 0 until problem.K) return false
        val lo = problem.rangeLo[staff][shift]
        val hi = problem.rangeHi[staff][shift]
        return lo != Int.MIN_VALUE && lo == hi
    }

    /**
     * 大量変更後の best 差し替え（Hotfix / reseed 用の正式 API）。
     * better(report, bestReport) のときだけ best を更新し version を進める。
     * current も同期する（探索の継続点を best に合わせる）。
     */
    fun replaceBestIfBetter(schedule: Array<IntArray>, report: ViolationReport): Boolean {
        if (!better(report, bestReport)) return false
        best = deepCopy(schedule)
        bestReport = report
        current = deepCopy(schedule)
        currentReport = report
        counts.rebuildFrom(current, problem.T)
        bits?.rebuildFrom(current)
        version++
        undo.clear()
        return true
    }

    fun snapshotBest(
        alternatives: List<Array<IntArray>> = emptyList(),
        fusionElites: List<Array<IntArray>> = emptyList(),
        infeasibleFamilies: Set<String> = emptySet(),
        stopReason: StopReason = StopReason.DEADLINE,
        phaseLogs: List<MirrorLog> = emptyList(),
    ): RunArtifacts = RunArtifacts(
        schedule = deepCopy(best),
        report = bestReport,
        alternatives = alternatives,
        fusionElites = fusionElites,
        infeasibleFamilies = infeasibleFamilies,
        stopReason = stopReason,
        phaseLogs = phaseLogs,
    )

    companion object {
        fun deepCopy(a: Array<IntArray>): Array<IntArray> =
            Array(a.size) { i -> a[i].clone() }
    }
}
