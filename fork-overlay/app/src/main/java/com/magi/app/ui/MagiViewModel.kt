package com.magi.app.ui

import com.magi.app.toHankakuKigou
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.magi.app.v6.Evaluator
import com.magi.app.v6.betterReport
import com.magi.app.v6.LightMirrorOptimizer
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.SaOptimizer
import com.magi.app.v6.SaParams
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.cachedProblem
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.SettingIssue
import com.magi.app.v6.SettingFixAction
import com.magi.app.v6.FixSuggester
import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.FixCell
import com.magi.app.v6.FixKind
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.ForbiddenRunDiagnosis
import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.PinBlockAttribution
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.engine.integration.RebuildOptimizeEntry
import com.magi.app.v6.engine.integration.FullOptimizePipeline
import com.magi.app.v6.ScheduleRunResult
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.V6SanityReport
import com.magi.app.v6.Hf63Infeasibility
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.Ws1Result
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.canDo
import com.magi.app.v6.copy2D
import com.magi.app.v6.toIntArray2D
import com.magi.app.v6.withSchedule
import com.magi.app.v6.restShiftIndex
import com.magi.app.v6.wishLocked
import com.magi.app.work.OptimizationRepository
import com.magi.app.work.OptimizationWorker
import com.magi.app.model.Range
import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.MagiState
import com.magi.app.model.MojibakeRepair
import com.magi.app.model.StateParser
import com.magi.app.v6.V6WebCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 勤務表最適化のタイムアウト上限（秒）。高精度を保ったまま5分(300s)以内に圧縮。
 * 唯一の真実源はエンジン層の [com.magi.app.v6.MAX_OPTIMIZE_SEC]。UI 側はそれを参照し、
 * UI 設定の上限とエンジンの頭打ちが乖離しないようにする。
 */
const val MAX_BUDGET_SEC = com.magi.app.v6.MAX_OPTIMIZE_SEC

class MagiViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())

    init {
        RebuildOptimizeEntry.applyBuildConfigDefault()
        RebuildOptimizeEntry.enabled = true
        val toggles = OptimizeToggleStore.load(getApplication())
        OptimizeToggleStore.applyToRuntime(getApplication(), toggles)
        _ui.value = _ui.value.copy(
            wideC3nBreak = toggles.wideC3nBreak,
            adaptiveEscape = toggles.adaptiveEscape,
            softPolish = toggles.softPolish,
            blockSwapC3nFilter = toggles.blockSwapC3nFilter,
            nativeAccel = toggles.nativeAccel,
            nativeParity = toggles.nativeParity,
        )
    }
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var originalJson: String? = null
    private var state: MagiState? = null
    private var currentSchedule: Array<IntArray>? = null
    private var resultSchedule: Array<IntArray>? = null
    private var job: Job? = null
    private var checkJob: Job? = null
    private var fixJob: Job? = null   // [競合解消] 改善提案探索。連続タップ時に前探索をキャンセルし古い結果で上書きしない
    private var checkSeq = 0L

    /**
     * [3.328.0/外部レビュー・最重要] **長い最適化が動いているか**。
     *
     * `UiState.running` は「短い違反チェック」と「数十〜300秒の最適化」を1つの旗で兼ねていた。
     * `refreshCheck()` は完了時に必ず `running = false` を立て、`checkJob?.cancel()` は checkJob しか
     * 止めないので、**最適化の最中に設定を編集すると、その編集が起こす違反チェックの完了で
     * 実行中フラグが落ち、以降 `!ui.running` を見ている全てのガード（セル編集=3.161.0・一括シート=
     * 3.127.0・回数の緩和=3.326.0）が素通りになる**。旗を分けて、検査の完了では最適化の実行中表示を
     * 解除しないようにする。
     */
    @Volatile private var optimizeActive = false

    /** 前景の最適化（[optimizeActive]）と背景 Worker のどちらかが動いていれば真。 */
    /**
     * [3.328.0 → 3.336.0/外部レビュー P1] **編集・実行の可否はここだけを見る**。`ui.running` は
     * 画面へ出すための写しで、初期化時の WorkManager 問い合わせが失敗すれば false のまま残る
     * （＝背景で走っているのにガードが全部開く）。3.336.0 で早期 return するガード14箇所を
     * こちらへ寄せ、`ui.running` は表示専用へ降格した。
     */
    private fun optimizeInFlight(): Boolean =
        optimizeActive || OptimizationRepository.running.value

    // ===== [v2.22] 自動保存・復元（端末内）と「元に戻す」 =====
    private val autosaveFile get() = getApplication<Application>().filesDir.resolve("magi_autosave.json")
    // [判断設計監査 #3] 「データを開く」直前の状態を1世代だけ退避（開く=取消不能な置換だった穴を塞ぐ）。
    private val prevBackupFile get() = getApplication<Application>().filesDir.resolve("magi_prev_before_open.json")
    private var hydrated = false           // 復元完了前の自動保存を抑止（Web HF514 と同思想）
    private var saveJob: Job? = null
    private data class UndoSnap(val st: MagiState, val sched: Array<IntArray>)
    private val undoStack = ArrayDeque<UndoSnap>()
    private val redoStack = ArrayDeque<UndoSnap>()   // [Web反映] undo で退避→redo で復元（手動修正ループ）
    private fun snapNow(): UndoSnap? {
        val st = state ?: return null; val sc = currentSchedule ?: return null
        return UndoSnap(st, Array(sc.size) { sc[it].clone() })
    }

    // ===== プロセス強制終了の耐性: 実行中マーカー（中断検知 / 仕様書 §3.4 補完） =====
    // 実行開始時にマーカーを書き、正常終了で消す。プロセスがkillされるとマーカーが残るので、
    // 次回起動時に「前回の計算は中断された（入力は自動保存済み）」と気づかせ、再実行へ導く。
    private val runMarkerFile get() = getApplication<Application>().filesDir.resolve("magi_run_marker.json")
    private fun writeRunMarker(mode: String) {
        runCatching {
            val o = org.json.JSONObject()
            o.put("startedAt", System.currentTimeMillis())
            o.put("mode", mode) // "fg" | "bg"
            o.put("budgetSec", _ui.value.budgetSec)
            o.put("workers", _ui.value.workers)
            o.put("algorithm", _ui.value.v6Algorithm.name)
            runMarkerFile.writeText(o.toString())
        }
    }
    private fun clearRunMarker() { runCatching { if (runMarkerFile.exists()) runMarkerFile.delete() } }
    fun dismissInterrupted() {
        _ui.update { it.copy(interruptedRun = false, interruptedInfo = null) }
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 破棄で途中状態ファイルを削除
    }

    // ===== 操作ログ（監査）: 追記式・新しい順・時刻/レベル付き =====
    data class OpLogEntry(val timeMs: Long, val level: String, val message: String)
    private val opLog = ArrayDeque<OpLogEntry>()
    // 診断ログの「非圧縮・全文」を保持（画面表示は compressDiagLogs で圧縮、出力はこちらの全文を使う）。
    private var rawDiagLogs: List<String> = emptyList()
    private val opLogFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)

    /** 操作ログに1件追記し、UIへ反映（新しい順、最大300件）。 */
    private fun logOp(level: String, message: String) {
        opLog.addFirst(OpLogEntry(System.currentTimeMillis(), level, message))
        while (opLog.size > 1000) opLog.removeLast()
        _ui.update { it.copy(opLog = opLog.map { "${opLogFmt.format(java.util.Date(it.timeMs))} [${it.level}] ${it.message}" }) }
    }

    // 操作再現用デコード（現stateを参照。staff/shift一覧は操作中に不変）。
    private fun opNm(i: Int): String = state?.staff?.getOrNull(i)?.name ?: "#$i"
    private fun opSy(k: Int): String = state?.shifts?.getOrNull(k)?.kigou?.let { toHankakuKigou(it) } ?: "#$k"
    private fun opDays(days: List<Int>): String = if (days.size <= 10) days.joinToString(",") { "${it + 1}日" } else "${days.size}日分"

    init {
        // 起動時: 前回の自動保存があれば復元（無ければ何もしない）
        viewModelScope.launch {
            // [並行I/O] 独立した3つのファイル読み込み（自動保存・中断マーカー・完了結果）は互いに依存しない
            //   ＝async で待ち時間を重ね合わせ起動レイテンシを縮める（snapTxt/bgActive は resultTxt に依存
            //   するため後段で逐次に読む）。marker=中断検知（loadAsync より先にフラグを立て base 経由で保持）、
            //   resultTxt=[C1] bg最適化の完了結果（UI不在でも完走済み＝最優先で採用）。
            val (txt, marker, resultTxt) = coroutineScope {
                val a = async(Dispatchers.IO) { runCatching { autosaveFile.takeIf { it.exists() }?.readText() }.getOrNull() }
                val b = async(Dispatchers.IO) { runCatching { runMarkerFile.takeIf { it.exists() }?.readText() }.getOrNull() }
                val c = async(Dispatchers.IO) { runCatching { OptimizationWorker.resultFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull() }
                Triple(a.await(), b.await(), c.await())
            }
            // [判断設計監査 #3] 前回「データを開く」時の退避があれば復元導線（設定タブ）を有効化。
            val hasPrev = withContext(Dispatchers.IO) { runCatching { prevBackupFile.exists() }.getOrDefault(false) }
            if (hasPrev) _ui.update { it.copy(prevBackupAvailable = true) }
            if (!resultTxt.isNullOrBlank()) {
                clearRunMarker()
                OptimizationWorker.clearFiles(getApplication<Application>())
                if (state == null) loadAsync(resultTxt, markResult = true)   // initialAssignment が state.schedule を返すため結果が復元される
                logOp("I", "前回のバックグラウンド最適化の結果を反映しました")
            } else {
                // [#4/C1] 中断時、途中最良解のスナップショットがあれば「途中結果から再開」する。
                val snapTxt = withContext(Dispatchers.IO) {
                    runCatching { OptimizationWorker.snapshotFile(getApplication<Application>()).takeIf { it.exists() }?.readText() }.getOrNull()
                }
                // [監査A4] WorkManagerが実行継続中なら「中断」でなく継続中と案内（marker/snapshotは温存し、
                //   結果到着(applyBgResult)側で回収）。従来は再起動時に誤って中断扱い＋marker消去していた。
                val bgActive = withContext(Dispatchers.IO) {
                    runCatching {
                        androidx.work.WorkManager.getInstance(getApplication())
                            .getWorkInfosForUniqueWork(OptimizationWorker.UNIQUE).get()
                            .any { !it.state.isFinished }
                    }.getOrDefault(false)
                }
                if (bgActive) {
                    _ui.update { it.copy(running = true, message = "バックグラウンド計算を継続中…（完了時に自動反映）") }
                    if (state == null && !txt.isNullOrBlank()) loadAsync(txt)
                    logOp("I", "バックグラウンド最適化の継続を検知（進捗を購読）")
                } else {
                if (marker != null) {
                    val hasSnap = !snapTxt.isNullOrBlank()
                    val info = if (hasSnap)
                        "前回の計算は中断されましたが、途中までの最良の勤務表から再開できます。『もう一度実行』で仕上げられます。"
                    else runCatching {
                        val o = org.json.JSONObject(marker)
                        val modeJp = if (o.optString("mode") == "bg") "バックグラウンド" else ""
                        "前回の${modeJp}計算は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。"
                    }.getOrNull() ?: "前回の計算は完了前に中断されました。入力は自動保存済みです。"
                    _ui.update { it.copy(interruptedRun = true, interruptedInfo = info) }
                    clearRunMarker()
                    logOp("W", if (hasSnap) "前回の中断を検知（途中結果あり＝再開可）" else "前回の計算の中断を検知しました（入力は復元済み）")
                }
                if (state == null) {
                    // 途中最良解を優先して復元（無ければ自動保存の入力）。
                    val resumeTxt = snapTxt?.takeIf { it.isNotBlank() } ?: txt
                    if (!resumeTxt.isNullOrBlank()) loadAsync(resumeTxt)
                    if (!snapTxt.isNullOrBlank()) OptimizationWorker.clearFiles(getApplication<Application>())   // 消費後は掃除
                }
                }
            }
            hydrated = true
        }
        // バックグラウンド最適化（WorkManager）の進捗・結果を購読して画面へ反映（仕様書 §6.3）
        viewModelScope.launch {
            OptimizationRepository.progress.collect { p ->
                if (p != null && _ui.value.running) {
                    _ui.update { it.copy(
                        bestHard = p.hard.toLong(), bestSoft = p.soft.toLong(),
                        totalViolations = p.total, iters = p.iters, elapsedMs = p.elapsedMs,
                        message = "バックグラウンド ${p.phase}",
                    ) }
                }
            }
        }
        viewModelScope.launch {
            OptimizationRepository.result.collect { r -> if (r != null) applyBgResult(r) }
        }
    }

    /** バックグラウンド（WorkManager / Expedited）で最適化を開始。完了時に通知＋画面反映。 */
    fun runInBackground() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (optimizeInFlight()) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        OptimizationRepository.clear()
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 旧途中状態を掃除（Workerが開始時に再保存）
        // [3.327.0/外部レビュー High3] この実行の識別子を先に確定する。ファイル名は固定なので、これが無いと
        //   置き換えられた旧実行が新実行の入力を消したり、別データの結果を書き残したりできてしまう。
        //   ミリ秒だと同一ミリ秒での二重 enqueue が衝突しうるので、下位に乱数を混ぜて一意にする。
        val runId = System.currentTimeMillis() * 1000L + (0..999).random()
        OptimizationWorker.beginRun(getApplication(), runId)
        // [3.328.0] この結果を後で当ててよいかを判断するための入力の指紋。
        bgStateKey = stateKey(st0)
        // [監査A7] enqueue前に入力を退避。Worker初回書込前にプロセスが死ぬと request=null かつ
        //   ファイル無しで復元不能だった穴を閉じる（同期・数ms・Workerが同内容で上書き）。
        runCatching { OptimizationWorker.inputFile(getApplication()).writeText(StateParser.serialize(st0, sched0)) }
        OptimizationRepository.request = st0 to sched0.copy2D()
        OptimizationRepository.seconds = _ui.value.budgetSec
        OptimizationRepository.workers = _ui.value.workers
        val work = androidx.work.OneTimeWorkRequestBuilder<OptimizationWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // [P2修正/レビュー指摘] 予算秒数・並列数を WorkManager の inputData に永続化。プロセス再起動後の
            //   再実行でも開始時の条件（例: 300秒/8並列）が保たれる（旧: インメモリのみで既定60秒/4並列に化けた）。
            .setInputData(androidx.work.workDataOf(
                OptimizationWorker.KEY_SECONDS to _ui.value.budgetSec,
                OptimizationWorker.KEY_WORKERS to _ui.value.workers,
                OptimizationWorker.KEY_RUN_ID to runId,
            ))
            .build()
        androidx.work.WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(OptimizationWorker.UNIQUE, androidx.work.ExistingWorkPolicy.REPLACE, work)
        _ui.update { it.copy(running = true, hasResult = false, interruptedRun = false, interruptedInfo = null, message = "バックグラウンドで最適化を開始しました（完了時に通知）") }
        writeRunMarker("bg")
        logOp("I", "バックグラウンド最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers})")
    }

    private suspend fun applyBgResult(r: OptimizationRepository.BgResult) {
        val st0 = state ?: return
        // [3.328.0/外部レビュー] 背景の結果は「開始時の入力」に対して計算されたもの。実行中に別のデータを
        //   開く・取り込むなどで入力が変わっていたら、その結果は今の入力の答えではないので捨てる
        //   （旧: 現在の state へ無条件に当てていた）。指紋が未記録(0)の経路＝プロセス再起動後の
        //   ファイル復元は、結果ファイルが state ごと持つので自己整合＝従来どおり通す。
        if (bgStateKey != 0L && bgStateKey != stateKey(st0)) {
            bgStateKey = 0L
            logOp("W", "バックグラウンド計算の結果を破棄しました（計算中に設定またはデータが変わったため）")
            _ui.update { it.copy(running = false, message = "計算中に設定が変わったため、結果は反映しませんでした。もう一度つくってください。") }
            return
        }
        bgStateKey = 0L
        // [再実行 keep-best] 背景完了結果が前回採用解より悪化なら前回を維持（前景と同じ方針）。
        val prev = resultSchedule
        if (prev != null) {
            val prevReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, prev) }
            val newHard = r.report.hard.toLong(); val newTotal = r.report.total
            // [3.287.0 keep-best統一 → 3.289.0 で単一ソースへ委譲] 手書きの3節複製をやめ betterReport
            //   （hard→weightedScore→total）に一本化。将来の順序変更でここだけ取り残される事故を防ぐ。
            val worse = betterReport(prevReport, r.report)
            if (worse) {
                val kept = prev.copy2D()
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                autoSave()
                pushReport(state ?: st0, kept, prevReport) { it.copy(
                    running = false, hasResult = true,
                    message = "今回(必須$newHard/合計$newTotal)は前回(必須${prevReport.hard}/合計${prevReport.total})より改善せず。前回の結果を維持しました。",
                ) }
                logOp("I", "バックグラウンド: 今回 必須$newHard/合計$newTotal は前回 以下に改善せず → 前回を維持")
                clearRunMarker()
                OptimizationWorker.clearFiles(getApplication<Application>())
                OptimizationRepository.request = null
                OptimizationRepository.publishResult(null)
                return
            }
        }
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        resultSchedule = sched
        state = st0.withSchedule(sched)
        autoSave()
        pushReport(state ?: st0, sched, r.report) { it.copy(
            running = false, hasResult = true,
            message = "バックグラウンド最適化 完了: 必須=${r.report.hard} 合計=${r.report.total}",
        ) }
        logOp("I", "バックグラウンド最適化 完了 必須=${r.report.hard} 合計=${r.report.total}")
        lastResultHard = r.report.hard.toLong()
        clearRunMarker()
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] 完了で途中状態ファイルを削除
        // 消費したらクリア（再生成時の二重適用を防ぐ）
        OptimizationRepository.request = null
        OptimizationRepository.publishResult(null)
    }

    /** 1.2秒デバウンスで状態をアプリ専用領域に保存。失敗は黙殺（次回操作で再試行）。 */
    private fun autoSave() {
        if (!hydrated) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val json = exportJson() ?: return@launch
            withContext(Dispatchers.IO) { runCatching { autosaveFile.writeText(json) } }
        }
    }

    /**
     * 即時保存（デバウンスなし・同期書込）。バックグラウンド遷移(onStop/onPause)から呼び、
     * 保留中の編集を確実に永続化する。autoSave の1200msデバウンス中に
     * プロセスが破棄されても編集が失われないようにするための保険。
     */
    fun saveNow() {
        if (!hydrated) return
        saveJob?.cancel()
        val json = exportJson() ?: return
        runCatching { autosaveFile.writeText(json) }
    }

    private fun pushUndo() {
        val snap = snapNow() ?: return
        undoStack.addLast(snap)
        while (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()   // 新しい操作は redo 履歴を無効化（標準的な undo/redo 挙動）
        _ui.update { it.copy(canUndo = true, canRedo = false) }
    }

    private fun clearUndo() {
        undoStack.clear()
        redoStack.clear()
        _ui.update { it.copy(canUndo = false, canRedo = false) }
    }

    /** 直前の編集・取込・計算開始前の状態へ戻す（最大30段）。現在状態は redo へ退避。 */
    fun undo() {
        if (job?.isActive == true || optimizeInFlight()) return   // [3.328.0] 背景の最適化中も抑止（job は前景のみ）
        val snap = undoStack.removeLastOrNull() ?: return
        snapNow()?.let { redoStack.addLast(it) }   // [Web反映] 現在をやり直し用に退避
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        _ui.update { it.copy(structureEdited = true, canUndo = undoStack.isNotEmpty(), canRedo = true, message = "1つ前に戻しました") }
        logOp("I", "元に戻す")
        refreshCheck()
        autoSave()
    }

    /** [Web反映] 元に戻した操作をやり直す。手動修正のループ（修正→戻す→やり直し）を支える。 */
    fun redo() {
        if (job?.isActive == true || optimizeInFlight()) return   // [3.328.0] 背景の最適化中も抑止（job は前景のみ）
        val snap = redoStack.removeLastOrNull() ?: return
        snapNow()?.let { undoStack.addLast(it) }
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        _ui.update { it.copy(structureEdited = true, canUndo = true, canRedo = redoStack.isNotEmpty(), message = "やり直しました") }
        logOp("I", "やり直し")
        refreshCheck()
        autoSave()
    }

    fun load(json: String) = loadAsync(json)

    /**
     * [⛏6] ゼロから作る起点。最小の有効データ(1シフト/1グループ/1スタッフ/31日)を
     * 既存の load() 経路(StateParser→validate→Problem→makeUi)にそのまま流す。サンプル
     * (assets/sample_state_v6.json)と同じ構造を最小化したものなので、専用の初期化ロジックを
     * 持たず実行時の不整合リスクを抑える。読み込み後はユーザーが編集タブ(年次マスター)で
     * シフト/グループ/スタッフを一括追加して育てる想定。
     */
    fun initBlankState() {
        val days = 31
        val sched = (0 until days).joinToString(",") { "0" }
        val seed = """
            {"startDate":"2026-01-01","endDate":"2026-01-31",
            "shifts":[{"name":"休み","kigou":"休","need1":"","need2":""}],
            "groups":[{"name":"グループA","kigou":"A"}],
            "staff":[{"name":"職員1","groupIdx":0}],
            "use2Patterns":true,
            "groupShift":[[1]],"groupShiftApt":[[""]],
            "cons1":[],"cons2":[],"cons3":[],"cons3n":[],"cons3m":[],"cons3mn":[],"cons41":[],"cons42":[],
            "wishes":{},"staffRange":{},"needDay1":{},"needDay2":{},
            "schedule":[[$sched]]}
        """.trimIndent()
        load(seed)
    }

    fun loadAsync(rawJson: String, markResult: Boolean = false) {
        val json = MojibakeRepair.repair(rawJson)
        // [3.282.0/新領域ログ監査] 旧: 参照比較(`!==`)のため BOM 除去だけの健全なファイルでも毎回
        //   「文字化けを自動修復」と誤警告していた。実際に二重エンコードを復号したときだけ警告し、
        //   元ファイル自体は直らない（再取込のたび修復が走る）ことも案内する。
        val repaired = MojibakeRepair.wasDecoded(rawJson, json)
        job?.cancel()
        _ui.update { it.copy(running = true, message = "読込中…") }
        job = viewModelScope.launch {
            try {
                if (repaired) logOp("W", "文字化け（二重エンコード）を自動修復して読み込みました。元のファイル自体は修復されません（「データを保存」で保存し直すと次回からこの警告は出ません）")
                val loaded = withContext(Dispatchers.Default) {
                    val st = StateParser.parse(json)
                    validate(st)?.let { return@withContext Result.failure<LoadedProblem>(IllegalArgumentException(it)) }
                    val p = Problem(st)
                    val ev = Evaluator(p)
                    val init = p.initialAssignment()
                    val baseEval = ev.split(ev.fullEval(init))
                    val report = UnifiedViolationChecker.check(st, init)
                    Result.success(LoadedProblem(st, init, baseEval.first, baseEval.second, report))
                }
                loaded.fold(
                    onSuccess = { lp ->
                        // [判断設計監査 #3] 置換直前の状態を1世代退避。「開く前のデータに戻す」
                        //   （restorePreviousData）で往復できる（戻す操作自体も退避を挟む＝スワップ）。
                        // [3.289.0/外部レビューMedium] 旧: fire-and-forget の launch で退避していたため、
                        //   ①「戻す」を連続で押すと退避ファイルの更新前の内容を読み、同じデータが2回出る
                        //   （＝「もう一度押すと入れ替わる」というUI契約が破れる）②状態切替後・書込前の
                        //   プロセス終了で退避が消えるのに復元可能表示だけ残る、の2つが起こり得た。
                        //   状態を切り替える前に **同期で・一時ファイル経由の原子的置換** で書き、
                        //   書込に成功したときだけ復元可能フラグを立てる。
                        val prevJson = if (state != null) exportJson() else null
                        val prevSaved = if (prevJson == null) false else withContext(Dispatchers.IO) {
                            runCatching {
                                val tmp = java.io.File(prevBackupFile.parentFile, prevBackupFile.name + ".tmp")
                                tmp.writeText(prevJson)
                                if (!tmp.renameTo(prevBackupFile)) {
                                    // rename 不可の環境向けフォールバック（同一FS内なので通常は成立する）。
                                    prevBackupFile.writeText(prevJson)
                                    tmp.delete()
                                }
                                true
                            }.getOrDefault(false)
                        }
                        originalJson = json
                        state = lp.state.withSchedule(lp.schedule)
                        currentSchedule = lp.schedule.copy2D()
                        // [bg復元] markResult=true は「バックグラウンド最適化の結果 JSON」の読込。schedule が
                        //   結果そのものなので resultSchedule/hasResult を立て、上位バーの「未計算」を防ぐ。
                        resultSchedule = if (markResult) lp.schedule.copy2D() else null
                        clearUndo()
                        autoSave()
                        pushReport(lp.state, lp.schedule, lp.report) {
                            it.copy(
                                loaded = true,
                                running = false,
                                hasResult = markResult,
                                constraintsEdited = false,
                                structureEdited = false,
                                staff = lp.state.staffCount,
                                days = lp.state.dayCount,
                                shifts = lp.state.shiftCount,
                                groups = lp.state.groupCount,
                                use2 = lp.state.use2Patterns,
                                initHard = lp.nativeHard,
                                // [3.313.0] 単位を checker 基準へ揃える。旧: Evaluator の**重み付き**soft を
                                //   入れていたが、`makeUi`（中央のUI構築）が書く bestSoft は
                                //   `report.soft`＝**生件数**なので、完了後の「改善 N%」が
                                //   重み付き→生件数の引き算になり大幅に水増しされていた。
                                initSoft = lp.report.soft.toLong(),
                                iters = 0,
                                itersPerSec = 0,
                                elapsedMs = 0,
                                // [3.289.0] 書込が実際に成功したときだけ立てる（既存の退避があれば維持）。
                                prevBackupAvailable = prevSaved || it.prevBackupAvailable,
                                message = "読込完了: ${lp.state.staffCount}名 / ${lp.state.dayCount}日 / ${lp.state.shiftCount}シフト",
                            )
                        }
                        logOp("I", "読込 ${lp.state.staffCount}名/${lp.state.dayCount}日/${lp.state.shiftCount}シフト")
                    },
                    onFailure = {
                        _ui.update { it.copy(running = false, message = "読込失敗: ${it.message}") }
                    },
                )
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, message = "読込失敗: ${e.message}") }
            }
        }
    }

    /** [判断設計監査 #3] 「データを開く」直前に退避した1世代前の状態へ戻す。loadAsync 経由のため
     *  現在のデータが再び退避される＝もう一度押すと元へ戻る（スワップ）。 */
    fun restorePreviousData() {
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は操作できません") }; return }
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) {
                runCatching { prevBackupFile.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            if (txt.isNullOrBlank()) {
                _ui.update { it.copy(message = "開く前のデータの退避がありません") }
                return@launch
            }
            logOp("I", "開く前のデータに戻します（もう一度押すと入れ替わります）")
            loadAsync(txt)
        }
    }

    /** Returns a human-readable error message if the state is structurally invalid, else null. */
    private fun validate(st: MagiState): String? {
        if (st.staffCount == 0) return "staff が空です"
        if (st.dayCount == 0) return "schedule が空です"
        if (st.shiftCount == 0) return "shifts が空です"
        if (st.groupCount == 0) return "groups が空です"
        if (st.schedule.size != st.staffCount) return "schedule の行数が staff 数と一致しません"
        if (st.groupShift.size < st.groupCount) return "groupShift の行数が groups より少ないです"
        st.groupShift.forEachIndexed { g, row ->
            if (row.size < st.shiftCount) return "groupShift[$g] の列数が shifts より少ないです"
            if (row.take(st.shiftCount).none { it == 1 }) return "groupShift[$g] に担当可能シフトがありません"
        }
        st.groupShiftApt.forEachIndexed { g, row ->
            if (g < st.groupCount && row.isNotEmpty() && row.size < st.shiftCount) return "groupShiftApt[$g] の列数が shifts より少ないです"
        }
        st.staff.forEachIndexed { i, s ->
            if (s.groupIdx !in 0 until st.groupCount) return "staff[$i].groupIdx が範囲外です (${s.groupIdx})"
        }
        st.schedule.forEachIndexed { i, row ->
            if (row.size != st.dayCount) return "schedule[$i] の日数が不揃いです"
            row.forEachIndexed { j, k ->
                if (k != -1 && k !in 0 until st.shiftCount) return "schedule[$i][$j] のシフト番号が範囲外です ($k)"
            }
        }
        return null
    }

    /**
     * [native堅牢化] 最適化・生成の実行前に構造を検証する。期間/スタッフ/シフトの不整合や
     * 未割当グループ・範囲外シフト等があれば、クラッシュさせず理由を表示して中止する
     * （添付資料 doc#5/#6/#7 起因の事故をネイティブ側でも明示的に防止）。
     */
    private fun ensureValidForRun(st: MagiState, sched: Array<IntArray>): Boolean {
        val err = validate(st.withSchedule(sched)) ?: return true
        _ui.update { it.copy(running = false, message = "実行できません: $err。編集内容を確認してください") }
        return false
    }

    fun setWorkers(n: Int) { val v = n.coerceIn(1, 16); _ui.update { it.copy(workers = v) }; logOp("I", "設定変更: 並列数 → $v") }
    // タイムアウト上限は5分(300s)。エンジンは budgetMs を全フェーズで厳守し、超過しない（停滞時はさらに早期終了）。
    /** [ネイティブ加速 Stage4] ユーザートグル。OFF=C++チャンク不使用（番兵ゲートとは独立の意思表示）。 */
    fun setNativeAccel(on: Boolean) {
        com.magi.app.v6.NativeGate.userEnabled = on
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_NATIVE_ACCEL, on)
        _ui.update { it.copy(nativeAccel = on) }
        logOp("I", "設定変更: ネイティブ加速 → ${if (on) "ON" else "OFF"}")
    }

    /** [照合トグル] Kotlinパリティ照合の ON/OFF。OFF=純ネイティブ（Kotlin照合を行わず C++結果を信頼＝
     *  検証/ベンチ用・誤結果の可能性）。C++内部の自己整合(status)番兵は独立に常時有効。 */
    fun setNativeParity(on: Boolean) {
        com.magi.app.v6.NativeGate.parityCheckEnabled = on
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_NATIVE_PARITY, on)
        _ui.update { it.copy(nativeParity = on) }
        logOp(if (on) "I" else "W", "設定変更: Kotlinパリティ照合 → ${if (on) "ON" else "OFF（純ネイティブ・誤結果の可能性）"}")
    }

    /**
     * [3.298.0 配線] ブロック巡回交換の c3n 事前フィルタ ON/OFF（既定OFF＝捨てない）。
     * c3n は HARD なので増える候補は `isBetter` が必ず却下する＝**採用結果は ON/OFF で変わらない**
     * （3.296.0 の A/B 実測で最終盤面・採用数の完全一致を確認済み）。ON は「詰んだ候補へフル checker を
     * 呼ばない」ぶんの節約だけで、評価枠を soft 判定まで進める候補へ回せる。
     */
    fun setBlockSwapC3nFilter(on: Boolean) {
        com.magi.app.v6.PolishGate.filterC3nIncrease = on
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_BLOCK_SWAP_C3N, on)
        _ui.update { it.copy(blockSwapC3nFilter = on) }
        logOp("I", "設定変更: 禁止連続の事前フィルタ → ${if (on) "ON" else "OFF"}")
    }

    /**
     * [3.304.0] 禁止連続を崩しに行く日を j±1 から「違反パターンがまたぐ全日」へ広げる。
     * 3連（`Dﾃ→休→A4`）の先頭に届くようになる一般化だが、実データ3件で利得が一貫しなかったため既定 OFF
     * （詳細は `PolishGate.wideC3nBreakDays` の docstring）。検証用に切り替えられるようにしてある。
     */
    fun setWideC3nBreak(on: Boolean) {
        com.magi.app.v6.PolishGate.wideC3nBreakDays = on
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_WIDE_C3N, on)
        _ui.update { it.copy(wideC3nBreak = on) }
        logOp("I", "設定変更: 禁止連続の崩し範囲 → ${if (on) "パターン全域" else "前後1日"}")
    }

    /**
     * [3.306.0] 並列で走る複数案が行き詰まったとき、次に何を試すかを「残っている違反の種類」から選び、
     * 行き詰まりの深さを手を替えても保持する。既定 OFF（実データ3件×各4回の A/B で有意差を検出できず、
     * 詳細は `PolishGate.adaptiveEscapeControl` の docstring）。
     */
    fun setAdaptiveEscape(on: Boolean) {
        com.magi.app.v6.PolishGate.adaptiveEscapeControl = on
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_ADAPTIVE, on)
        _ui.update { it.copy(adaptiveEscape = on) }
        logOp("I", "設定変更: 行き詰まりからの立て直し方 → ${if (on) "残りの違反に合わせて選ぶ" else "順ぐりに試す"}")
    }

    fun setBudget(sec: Int) { val v = sec.coerceIn(10, MAX_BUDGET_SEC); _ui.update { it.copy(budgetSec = v) }; logOp("I", "設定変更: 予算 → ${v}秒") }
    fun setSoftPolish(b: Boolean) {
        OptimizeToggleStore.put(getApplication(), OptimizeToggleStore.KEY_SOFT_POLISH, b)
        _ui.update { it.copy(softPolish = b) }
        logOp("I", "設定変更: ソフト研磨 → ${if (b) "ON" else "OFF"}")
    }
    fun setV6Algorithm(a: V6Algorithm) { _ui.update { it.copy(v6Algorithm = a) }; logOp("I", "設定変更: 方式 → $a") }

    fun refreshCheck() {
        val st = state ?: return
        val sched = currentSchedule?.copy2D() ?: return
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.update { it.copy(running = true, message = "違反チェック中…") }
        checkJob = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleCheck(st, sched)
                if (seq != checkSeq) return@launch   // [review #6] a newer check started; drop stale result
                pushReport(st, res.schedule, res.report) { it.copy(
                    // [3.328.0] 最適化が動いていれば実行中のまま。旧: 無条件に false で、
                    //   最適化中の設定編集→検査完了で全ガードが素通りになっていた。
                    running = optimizeInFlight(),
                    message = "違反チェック完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ) }
                logOp("I", "違反チェック 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] 停止時の running 固着を解消。新しいチェックによるキャンセル
                //   （seq != checkSeq＝後続が直後に running=true を立て直す）では触らず、stop() による
                //   キャンセルのときだけ実行中表示を戻す。
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "違反チェックを停止しました") }
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "違反チェック失敗: ${e.message}") }
            }
        }
    }

    /**
     * [初期解生成(賢い版)] 希望シフト→C1(窓の要件)→日別必要人数→個人下限→残り埋め の順で
     * 初期解を組み立てる。本最適化(SA/ALNS)へは続けない（続けて最適化したい場合は
     * この後に別途「勤務表をつくる」を押す）。ホームの「勤務表をつくる」の隣に新設。
     */
    fun generateSmartInitial() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        // [3.271.0, 実機ログ起因] 実行中ガード。旧: ガード無しのため「勤務表をつくる」の直後に隣接する
        //   本ボタンを連続タップすると、走行中の最適化と初期解生成が併走し、job 参照の上書き
        //   （走行中jobが stop() 不能のゾンビ化）と currentSchedule の同時書き換え（3.161.0 と同じ
        //   別名共有クラス）が起きていた（実機ログ 19:56:41 最適化開始→19:56:48 初期解生成完了 で実証）。
        //   runV6FullOptimize/start/runSoftPolish と同じガードに統一（3.161.0 のセル編集ガードと同方針）。
        if (optimizeInFlight()) {
            _ui.update { it.copy(message = "計算の実行中は初期解を作れません（完了または「やめる」の後にどうぞ）") }
            return
        }
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.update { it.copy(running = true, hasResult = false, message = "初期解生成中…") }
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleSmartInitial(st.withSchedule(sched), allowImpossible = true)
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                pushReport(state ?: st, res.schedule, res.report) { it.copy(
                    running = false,
                    hasResult = true,
                    iters = 0,
                    itersPerSec = 0,
                    elapsedMs = 0,
                    message = "初期解生成完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ) }
                logOp("I", "初期解生成 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                // [3.271.0] 失敗を操作ログにも残す（旧: message のみ＝書き出したログから消えた実行が
                //   追跡不能だった。実機ログ解析で「開始したのに完了も停止も無い実行」の死因特定を阻んだ）。
                logOp("W", "初期解生成 失敗: ${e.message}")
                _ui.update { it.copy(running = false, message = "初期解生成失敗: ${e.message}") }
            }
        }
    }

    // [3.126.0] UI導線（下書きをつくる）はユーザー判断で撤去。API として温存。
    fun generateSimple() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        // [3.271.0] generateSmartInitial と同じ実行中ガード（API温存中だが同じ穴を残さない）。
        if (optimizeInFlight()) {
            _ui.update { it.copy(message = "計算の実行中は下書きを作れません（完了または「やめる」の後にどうぞ）") }
            return
        }
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.update { it.copy(running = true, hasResult = false, message = "簡易作成中…") }
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleSimple(st.withSchedule(sched), allowImpossible = true)
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                pushReport(state ?: st, res.schedule, res.report) { it.copy(
                    running = false,
                    hasResult = true,
                    iters = 0,
                    itersPerSec = 0,
                    elapsedMs = 0,
                    message = "簡易作成完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ) }
                logOp("I", "簡易作成 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                logOp("W", "簡易作成 失敗: ${e.message}")   // [3.271.0] 操作ログにも残す
                _ui.update { it.copy(running = false, message = "簡易作成失敗: ${e.message}") }
            }
        }
    }

    // [3.112.0] UI導線（ホーム「ほかの作り方」の速くつくる）はユーザー指示で撤去済み。API として温存。
    fun start() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (optimizeInFlight()) return
        if (!ensureValidForRun(st0, sched0)) return
        val runState = st0.withSchedule(sched0)
        val params = SaParams(
            workers = _ui.value.workers,
            budgetMs = _ui.value.budgetSec * 1000L,
            softPolish = _ui.value.softPolish,
        )
        pushUndo()
        writeRunMarker("fg")   // [監査A8] 高速計算にも中断マーカー（kill時案内をv6本線と統一）
        _ui.update { it.copy(running = true, hasResult = false, message = "高速計算中…") }
        var lastUiMs = 0L
        optimizeActive = true   // [3.328.0] 検査の完了でこの実行中が解除されないようにする
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    // [Main負荷回避] Problem/Evaluator の構築（同期CPU）も Default で行う（他経路と統一）。
                    val p = Problem(runState)
                    val ev = Evaluator(p)
                    SaOptimizer(p, ev).run(params) { pr ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= 200) {
                            lastUiMs = now
                            val (h, s) = ev.split(pr.bestScore)
                            val ips = if (pr.elapsedMs > 0) pr.totalIters * 1000 / pr.elapsedMs else 0
                            _ui.update { it.copy(
                                bestHard = h,
                                bestSoft = s,
                                iters = pr.totalIters,
                                itersPerSec = ips,
                                elapsedMs = pr.elapsedMs,
                            ) }
                        }
                    }
                }
                val report = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(runState, res.schedule) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = runState.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.totalIters * 1000 / res.elapsedMs else 0
                pushReport(state ?: runState, res.schedule, report) { it.copy(
                    running = false,
                    hasResult = true,
                    iters = res.totalIters,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "高速計算完了: 必須=${report.hard} 合計=${report.total} (${res.totalIters}反復, ${res.elapsedMs}ms)",
                ) }
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は途中(未採用)盤面ではなく直前に確定していた入力解を保持し表示も整合させる。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(runState, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = runState.withSchedule(kept)
                pushReport(state ?: runState, kept, keptReport, nonCancellable = true) { it.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ) }
                throw e
            } catch (e: Exception) {
                // [review D] 失敗時は進捗中に書き込んだメトリクス（反復数・速度・経過）を消す。
                //   「事故前データ」を失敗メッセージの脇に残さない。hasResult は開始時に false 済み。
                logOp("W", "高速計算 失敗: ${e.message}")   // [3.271.0] 操作ログにも残す（サイレント死の防止）
                _ui.update { it.copy(
                    running = false, hasResult = false,
                    iters = 0, itersPerSec = 0, elapsedMs = 0,
                    message = "最適化失敗: ${e.message}",
                ) }
            } finally {
                optimizeActive = false   // [3.328.0] 長い最適化の終了（正常・停止・失敗すべて）
                clearRunMarker()   // [監査A8]
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    // [3.112.0] UI導線（ホーム「ほかの作り方」のかんたんに）はユーザー指示で撤去済み。API として温存。
    fun runLightOptimize() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (optimizeInFlight()) return
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        writeRunMarker("fg")   // [監査A8]
        _ui.update { it.copy(running = true, hasResult = false, message = "軽量最適化中…") }
        optimizeActive = true   // [3.328.0]
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { LightMirrorOptimizer.optimize(st, sched, _ui.value.budgetSec.toDouble()) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.iterations * 1000 / res.elapsedMs else 0
                pushReport(state ?: st, res.schedule, res.report) { it.copy(
                    running = false,
                    hasResult = true,
                    iters = res.iterations,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "軽量最適化完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ) }
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は途中(未採用)盤面ではなく直前に確定していた入力解を保持し表示も整合させる。
                val kept = sched.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st.withSchedule(kept)
                pushReport(state ?: st, kept, keptReport, nonCancellable = true) { it.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ) }
                throw e
            } catch (e: Exception) {
                logOp("W", "軽量最適化 失敗: ${e.message}")   // [3.271.0] 操作ログにも残す
                _ui.update { it.copy(running = false, message = "軽量最適化失敗: ${e.message}") }
            } finally {
                optimizeActive = false   // [3.328.0] 長い最適化の終了（正常・停止・失敗すべて）
                clearRunMarker()   // [監査A8]
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    // 操作コパイロット用: 直前の実行設定と結果（ガチャ操作検知に使用）
    private var lastSettingsSig: String? = null
    private var lastResultHard: Long = -1L
    private var lastTopHardFamily: String? = null

    /**
     * [3.322.0] 直近の最適化で「窓の要件(c1)がなぜ直せなかったか」の構造化診断。
     * 研磨が候補を作って却下した記録が唯一の根拠＝盤面から再計算できないため保持する
     * （CoverageDiag/ForbiddenDiag が毎回作り直せるのとはここが違う）。
     */
    private var lastC1Plateau: C1PlateauDiagnosis? = null

    /**
     * [3.323.0] 直近の最適化で、厳密ピン(lo==hi)だけが止めた手の**計測できた**試行数。
     * `isBetter` が採用を認めた手をピンのガードだけが却下した回数。ただし全件ではない
     * （[com.magi.app.v6.V6PostOptimizationResult.observedPinBlockedAttempts] の注記参照）。
     */
    private var lastObservedPinAttempts: Int = 0

    /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
    private var lastPinBlocks: PinBlockAttribution? = null

    /**
     * [3.324.0/外部レビュー] 上の2つは「その盤面で研磨が却下した記録」であって盤面から再計算できない。
     * よって**どの盤面に対する観測か**を指紋で持ち、盤面が変わったら自動的に黙る。
     * 手編集・元に戻す・データ読込・CSV取込・初期解生成…と変更サイトごとにフックを足す方式は
     * 必ずどこかを漏らすので、makeUi 側で毎回突き合わせる自己無効化にする。
     */
    private var lastDiagBoardKey: Long = 0L

    /** [3.327.0→3.328.0] 診断を取ったときの入力の指紋。設定が編集されたら診断は失効する。 */
    private var lastDiagStateKey: Long = 0L

    /** [3.328.0] 背景の最適化を開始したときの入力の指紋。結果を当てる前に一致を確かめる。 */
    private var bgStateKey: Long = 0L

    /** 盤面の内容から決まる指紋。S×T が小さい（30×31）ので毎回の計算コストは無視できる。 */
    private fun boardKey(schedule: Array<IntArray>): Long {
        var h = 1125899906842597L
        for (row in schedule) for (v in row) h = h * 31L + v
        return h
    }

    /**
     * [3.328.0/外部レビュー → 3.330.0 で v6 へ移動] 勤務表の意味を決める入力すべての指紋。
     * 実体は [com.magi.app.v6.StateFingerprint]（Android 非依存＝ホストでテストできる）。
     */
    private fun stateKey(st: MagiState): Long = com.magi.app.v6.StateFingerprint.of(st)

    /** 研磨診断を「この盤面のもの」として保存する。null/0 は診断なし。 */
    private fun setPolishDiagnostics(
        plateau: C1PlateauDiagnosis?,
        observedPinBlockedAttempts: Int,
        forSchedule: Array<IntArray>,
        pinBlocks: PinBlockAttribution? = null,
    ) {
        lastC1Plateau = plateau
        lastObservedPinAttempts = observedPinBlockedAttempts
        lastPinBlocks = pinBlocks
        val fresh = plateau != null || observedPinBlockedAttempts > 0
        lastDiagBoardKey = if (fresh) boardKey(forSchedule) else 0L
        lastDiagStateKey = if (fresh) state?.let { stateKey(it) } ?: 0L else 0L
    }

    private fun hardFamilyJp(key: String): String = when (key) {
        "covU" -> "人員不足（必要人数）"
        "c3n" -> "禁止の並び（連勤など）"
        "pref" -> "希望シフト"
        "groupViol" -> "担当外シフト"
        "low" -> "個人の回数下限"
        "high" -> "個人の回数上限"
        else -> key
    }

    private fun topHardFamilyJp(breakdown: Map<String, Int>): String? {
        val keys = listOf("covU", "c3n", "pref", "groupViol", "low", "high")
        val top = keys.maxByOrNull { breakdown[it] ?: 0 } ?: return null
        return if ((breakdown[top] ?: 0) > 0) hardFamilyJp(top) else null
    }

    fun runV6FullOptimize() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (optimizeInFlight()) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        val sig = "${_ui.value.budgetSec}|${_ui.value.workers}|${_ui.value.v6Algorithm}|${_ui.value.softPolish}"
        val hint = if (sig == lastSettingsSig && lastResultHard > 0L)
            "前回と同じ設定での再実行です。最大の未解決は『${lastTopHardFamily ?: "未解決の制約"}』。編集タブでこれを1つ緩めると改善の可能性が高いです。"
        else null
        lastSettingsSig = sig
        _ui.update { it.copy(running = true, hasResult = false, copilotHint = hint, alternatives = emptyList(), liveSchedule = emptyList(), interruptedRun = false, interruptedInfo = null, message = "計算エンジン実行中…") }
        logOp("I", "最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers}, 方式${_ui.value.v6Algorithm})")
        writeRunMarker("fg")
        OptimizationWorker.clearFiles(getApplication<Application>())   // [C1] fg実行ではbg途中状態は無関係＝掃除
        val startMs = System.currentTimeMillis()
        // HF63: 探索の改善ストリームを追跡し、構造的に充足困難な制約族を検出（重み系は非改変＝安全）。
        val hf63 = Hf63Infeasibility()
        // 最適化中ログ強化用のスロットル状態（操作ログへマイルストーンだけを残しスパムを防ぐ）。
        var liveHard = Long.MAX_VALUE
        var livePhase = ""
        val runWall0 = System.currentTimeMillis()   // [N6] 経過表示は壁時計基準（onProgressのelapsedは仮説ローカルで巻き戻る）
        var lastPhaseLogMs = -10_000L
        val phaseNameLastLogMs = HashMap<String, Long>()   // [3.283.0] 同名フェーズの再ログ抑制（60s窓・スパム対策）
        var lastHardLogMs = -10_000L
        optimizeActive = true   // [3.328.0]
        job = viewModelScope.launch {
            try {
                // [再実行 keep-best] 実行開始時の入力解(sched0)の違反を評価し、完了時の採用判定の基準にする。
                //   sched0 はデータ編集直後なら新データの初期解なので、編集をまたいでも公平な基準になる。
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                // [一括修正] 「必須違反 残りN件 に改善」の基準を入力盤面の必須数でシード。旧: Long.MAX_VALUE 始まりの
                //   ため、探索シードが入力より悪い局面(例: 入力1→シード2)でも最初の報告を「改善」と表示していた。
                liveHard = baseReport.hard.toLong()
                val res = if (RebuildOptimizeEntry.enabled) {
                    // 再構築エンジン完全経路（初期解・G1–G4・HARD残差）
                    val run = withContext(Dispatchers.Default) {
                        RebuildOptimizeEntry.optimize(
                            state = st0,
                            schedule = sched0.copy2D(),
                            budgetSec = _ui.value.budgetSec,
                            seed = System.currentTimeMillis(),
                            workers = _ui.value.workers,
                            shouldStop = { !optimizeActive },
                            onProgress = { sp ->
                                _ui.update {
                                    it.copy(message = "再構築 ${sp.phase} 実行中…")
                                }
                            },
                        )
                    }
                    // handleOptimize 互換の ActionResult へ
                    V6FinalPort.ActionResult(
                        schedule = run.schedule,
                        report = run.report,
                        phase = "optimize:rebuild",
                        busyDetail = V6FinalPort.buildBusyDetail(st0, "rebuild"),
                        logs = run.report.logs,
                    )
                } else V6FinalPort.handleOptimize(
                    state = st0,
                    schedule = sched0.copy2D(),
                    secondsRaw = _ui.value.budgetSec,
                    workers = _ui.value.workers,
                    softPolish = _ui.value.softPolish,
                    requestedAlgorithm = _ui.value.v6Algorithm,
                    allowImpossible = true,
                ) { phase, report, iters, elapsed ->
                    val rep = report
                    // [3.93.1と同クラスの補正 / 実機ログ起因] 旧: 累積iter(数千万)を渡すと閾値5000が「約20msの無改善」
                    //   相当になり、違反>0の族がほぼ全て即 infeasible 判定＝9族ノイズ警告になっていた。経過時間ベース
                    //   (100単位/秒)に補正し、閾値5000＝「50秒無改善」で発火させる(class は Web 忠実移植のまま)。
                    //   [監査(6)] callback の elapsed はフェーズ境界で巻き戻るローカル時計＝長いフェーズ後に族が永久に
                    //   フラグ不能になるため、最適化開始からの単調な壁時計(startMs基準)を使う。
                    if (rep != null) hf63.updateFromBreakdown(rep.breakdown, ((System.currentTimeMillis() - startMs) / 10L).toInt())
                    _ui.update { it.copy(
                        bestHard = rep?.hard?.toLong() ?: it.bestHard,
                        bestSoft = rep?.soft?.toLong() ?: it.bestSoft,
                        totalViolations = rep?.total ?: it.totalViolations,
                        // 実行中も breakdown をライブ更新（export時に hard と breakdown が食い違う不整合を防ぐ）
                        breakdown = if (rep != null) emptyBreakdown + rep.breakdown else it.breakdown,
                        iters = iters,
                        itersPerSec = if (elapsed > 0) iters * 1000 / elapsed else 0,
                        // [実機報告「残り時間表示が5分から何度も巡回する」修正] onProgressのelapsedは
                        //   フェーズ境界で巻き戻るローカル時計（727/751行のコメントと同じ既知の性質）。
                        //   progressSummary の「残り」表示はこれを budgetSec から引くため、V5→ALNS→RSI
                        //   ラウンド等の頻繁なフェーズ遷移のたびに残り時間が予算近くまで跳ね戻って見えていた。
                        //   HF63(753行)と同じ単調な壁時計(startMs基準)に統一する。
                        elapsedMs = System.currentTimeMillis() - startMs,
                        // [DefragLiveView] 計算中の最良盤面をライブ表示用に反映（節目で更新される）。
                        liveSchedule = V6NativeOptimizer.liveBest ?: it.liveSchedule,
                        message = "V6 $phase 実行中…",
                    ) }
                    // ---- 最適化中ログ強化（スロットル付き）----
                    // フェーズ遷移と「必須違反が減った瞬間」だけを操作ログへ。頻度上限を設けてスパムを防ぐ。
                    // [ログ欠落バグ修正] スロットル判定に onProgress の仮説ローカル elapsed をそのまま使うと、
                    //   フェーズ境界で elapsed が巻き戻る（N6コメント参照）ため「elapsed - lastXxxMs」が新フェーズ
                    //   開始直後に大きく負になり得た。lastXxxMs は前フェーズ終盤の(大きい)値のまま残るため、新フェーズの
                    //   持続時間がその残存値+閾値に届かない場合、遷移ログが1件も出ないまま丸ごと欠落していた
                    //   （実機ログでRSI++のALNS Refineフェーズ(約90秒)が操作ログから完全に消えていたのはこれが原因）。
                    //   表示に既に使っている壁時計(runWall0基準)へスロットル判定・保持値とも統一する。
                    val wallElapsed = System.currentTimeMillis() - runWall0
                    val base = phase.substringAfter("/ ").trim().ifEmpty { phase }
                    if (base != livePhase && wallElapsed - lastPhaseLogMs >= 2_500) {
                        // [3.283.0/スパムログ対策] 適応ポートフォリオは V5 SA→RSI→ALNS を数秒周期で循環し、
                        //   遷移ごとにログすると操作ログが探索フェーズ行で埋まる（実機ログ: 68件中約60件が
                        //   フェーズ行＝読込/完了/改善などの重要イベントがリングから押し出される）。
                        //   同名フェーズ（数字を # に正規化: "ALNS restart 1/2" と "2/2" は同一視）は60秒に
                        //   1回まで。最良更新・改善を含む行は情報価値が高いため窓の対象外（従来どおり）。
                        val nameKey = base.replace(Regex("[0-9]+"), "#")
                        val important = base.contains("最良更新") || base.contains("改善")
                        // [3.283.1/実機ログで発覚した自己回帰の修正] 旧: 未出フェーズの番兵に Long.MIN_VALUE を
                        //   使い `wallElapsed - Long.MIN_VALUE` が**負へオーバーフロー**＝初出判定が恒偽で
                        //   通常フェーズ行が1件も出なくなっていた（意図は同名60秒窓＝20行台、実機は0行）。
                        //   null 判定へ是正（初出は常にログ・以後は60秒窓）。
                        val lastForName = phaseNameLastLogMs[nameKey]
                        if (important || lastForName == null || wallElapsed - lastForName >= 60_000) {
                            logOp("I", "探索フェーズ: $base（経過${wallElapsed / 1000}秒）")
                            phaseNameLastLogMs[nameKey] = wallElapsed
                            lastPhaseLogMs = wallElapsed
                        }
                        livePhase = base
                    }
                    if (rep != null && rep.hard.toLong() < liveHard) {
                        if (rep.hard == 0 || wallElapsed - lastHardLogMs >= 1_500) {
                            logOp("I", "必須違反 残り${rep.hard}件 に改善（経過${wallElapsed / 1000}秒・合計${rep.total}）")
                            lastHardLogMs = wallElapsed
                        }
                        liveHard = rep.hard.toLong()
                    }
                }
                // [再実行 keep-best] 完了結果が入力より悪化なら、入力解を維持して通知する。
                //   「もう一度つくる」を繰り返したとき、稀に多様化フェーズ等で入力より悪い解が返り、それを採用して
                //   良い結果(例 HARD=1)を捨てる事象があった(実機ログで確認)。入力以上の結果のみ採用する。
                // [3.289.0/自己監査で発見・runSoftPolish と同型] 判定を betterReport（hard→weightedScore→total）へ
                //   統一。旧: (必須, 合計) のみで weightedScore を見ておらず、3.287.0 で keep-best が正しく採用する
                //   ようになった「HARD同値・weighted改善・total増」の結果を**メイン最適化経路のこの保険が捨てて
                //   入力へ戻していた**（外部レビューが指摘した runSoftPolish より影響が大きい経路）。
                val newHard = res.report.hard.toLong(); val newTotal = res.report.total
                val baseHard = baseReport.hard.toLong(); val baseTotal = baseReport.total
                val worseThanInput = betterReport(baseReport, res.report)
                if (worseThanInput) {
                    val kept = sched0.copy2D()
                    // [3.324.0/外部レビュー] 採用しなかった盤面の診断は保存しない。旧実装は分岐に関わらず
                    //   res.post を無条件に保存しており、「捨てた盤面で直せなかった理由」を「いま表示中の
                    //   勤務表の理由」として見せうる（維持した勤務表は別の探索の産物）。
                    setPolishDiagnostics(null, 0, kept)
                    currentSchedule = kept
                    autoSave()
                    resultSchedule = kept
                    state = st0.withSchedule(kept)
                    pushReport(state ?: st0, kept, baseReport) { it.copy(
                        running = false,
                        hasResult = true,
                        itersPerSec = if (it.elapsedMs > 0) it.iters * 1000 / it.elapsedMs else 0,
                        message = "今回(必須$newHard/合計$newTotal)は前回(必須$baseHard/合計$baseTotal)より改善しませんでした。前回の結果を維持します。",
                    ) }
                    logOp("I", "再実行: 今回 必須$newHard/合計$newTotal は前回 必須$baseHard/合計$baseTotal 以下に改善せず → 前回を維持")
                    lastResultHard = baseHard
                } else {
                    // [3.324.0/外部レビュー] pushReport(=makeUi の唯一の経路)より**前**に保存する。
                    //   旧実装は pushReport のあとに代入していたため、その回の画面には診断が入らず
                    //   次の再チェックでようやく（しかも古い盤面基準で）出るという順序の逆転だった。
                    setPolishDiagnostics(res.post?.c1Plateau, res.post?.observedPinBlockedAttempts ?: 0, res.schedule, res.post?.pinBlocks)
                    currentSchedule = res.schedule.copy2D()
                    autoSave()
                    resultSchedule = res.schedule.copy2D()
                    state = st0.withSchedule(res.schedule)
                    pushReport(state ?: st0, res.schedule, res.report) { it.copy(
                        running = false,
                        hasResult = true,
                        itersPerSec = if (it.elapsedMs > 0) it.iters * 1000 / it.elapsedMs else 0,
                        message = "最適化（${res.phase}）完了: 必須=${res.report.hard} 合計=${res.report.total} (${System.currentTimeMillis() - startMs}ms)",
                    ) }
                    lastResultHard = newHard
                }
                lastC1Plateau?.logLines()?.take(4)?.forEach { logOp("W", it.removePrefix("[W] ")) }
                lastTopHardFamily = if (res.report.hard > 0) topHardFamilyJp(res.report.breakdown) else null
                logOp(if (res.report.hard == 0) "I" else "W", "最適化 完了 必須=${res.report.hard} 合計=${res.report.total} (${res.phase})")
                // HF63 検出: 50秒改善のない制約族＝データ上満たせない可能性が高い（業務担当者へ提示）。
                // [実機ログ起因] 探索中の一時盤面でしか違反が無かった族（最終盤面で0）は「充足できている」ので
                //   警告から除外する（旧: 破棄された探索トラックの covO/LimMax まで列挙され誤解を招いた）。
                val staleKeys = hf63.infeasibleBreakdownKeys().filter { (res.report.breakdown[it] ?: 0) > 0 }
                if (staleKeys.isNotEmpty()) {
                    val names = staleKeys.mapNotNull { k -> Hf63Infeasibility.KEY_TO_INDEX[k]?.let { Hf63Infeasibility.CNAMES[it] } }
                    logOp("W", "構造的に充足が難しい制約を検出: ${names.joinToString(", ")}（データの見直しを推奨）")
                }
                captureAlternatives(res.alternatives)
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は実行中の(未採用の)途中盤面ではなく、直前に確定していた
                //   入力解(sched0)をそのまま保持し、表示の違反数も実際の盤面に合わせる。これにより
                //   「必須=0だったのに停止したら必須が増えて見える」不整合を防ぐ（完了時のkeep-bestと同じ思想）。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st0, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                pushReport(state ?: st0, kept, keptReport, nonCancellable = true) { it.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ) }
                logOp("I", "停止: 直前の勤務表 必須=${keptReport.hard}/合計=${keptReport.total} を保持")
                throw e
            } catch (e: Exception) {
                // [3.271.0, 実機ログ起因] 失敗を操作ログにも残す。旧: message のみのため、書き出した
                //   ログに「最適化 開始」だけあって完了も停止も無い実行が現れても死因（例外で静かに
                //   落ちたのか）を判別できなかった（実機ログ 19:56:41 の消えた実行の解析を阻んだ実例）。
                logOp("W", "最適化 失敗: ${e.message}")
                _ui.update { it.copy(running = false, message = "V6最適化失敗: ${e.message}") }
            } finally {
                optimizeActive = false   // [3.328.0] 長い最適化の終了（正常・停止・失敗すべて）
                clearRunMarker()  // 正常終了・停止・失敗いずれでもマーカーを消す（中断のみ残す）
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    /**
     * [ソフト研磨のみ] 現在の勤務表をHARDガード付きで局所研磨し、SOFT違反だけを削る。
     *   「もう一度つくる」と違い破壊/多様化を行わないため必須が一時的に増えることはなく、
     *   keep-best により入力より悪い結果は採用しない（HARD=0 を壊さない）。
     */
    fun runSoftPolish() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (optimizeInFlight()) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        writeRunMarker("fg")   // [監査A8]
        _ui.update { it.copy(running = true, hasResult = false, liveSchedule = emptyList(), message = "自動で整えています…") }
        logOp("I", "ソフト研磨 開始 (予算${_ui.value.budgetSec}s)")
        val startMs = System.currentTimeMillis()
        optimizeActive = true   // [3.328.0]
        job = viewModelScope.launch {
            try {
                val baseReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, sched0) }
                val polished = withContext(Dispatchers.Default) {
                    V6NativeOptimizer.softPolishOnly(st0, sched0.copy2D(), _ui.value.budgetSec)
                }
                val polishedReport = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(st0, polished) }
                // softPolishOnly は退化防止済みだが、VM側でも入力以上のみ採用（保険）。
                // [3.289.0/外部レビューHigh] 判定を betterReport（hard→weightedScore→total）へ統一。
                //   旧: (hard, total) のみを見ており weightedScore を一切参照しなかったため、3.287.0 で
                //   keep-best が正しく採用するようになった「HARD同値・weighted改善・total増」の結果を
                //   この保険だけが「悪化」と誤判定して入力へ戻し、ソフト研磨に限り目的関数の統一を打ち消していた。
                val worse = betterReport(baseReport, polishedReport)
                val finalSched = if (worse) sched0.copy2D() else polished.copy2D()
                val finalReport = if (worse) baseReport else polishedReport
                currentSchedule = finalSched
                autoSave()
                resultSchedule = finalSched
                state = st0.withSchedule(finalSched)
                val gain = baseReport.total - finalReport.total
                pushReport(state ?: st0, finalSched, finalReport) { it.copy(
                    running = false,
                    hasResult = true,
                    message = if (gain > 0)
                        "ソフト研磨 完了: 合計 ${baseReport.total} → ${finalReport.total}（-$gain）必須=${finalReport.hard} (${System.currentTimeMillis() - startMs}ms)"
                    else
                        "ソフト研磨 完了: これ以上の削減は見つかりませんでした（合計=${finalReport.total} 必須=${finalReport.hard}）。残りは構造的要因の可能性。",
                ) }
                logOp("I", "ソフト研磨 完了 必須=${finalReport.hard} 合計=${finalReport.total}（${if (gain > 0) "-$gain" else "増減なし"}）")
            } catch (e: CancellationException) {
                // [停止 keep-best] 中断時は直前の確定盤面を保持し表示も整合させる。
                val kept = sched0.copy2D()
                val keptReport = withContext(NonCancellable + Dispatchers.Default) {
                    UnifiedViolationChecker.check(st0, kept)
                }
                currentSchedule = kept
                resultSchedule = kept
                state = st0.withSchedule(kept)
                pushReport(state ?: st0, kept, keptReport, nonCancellable = true) { it.copy(
                    running = false,
                    hasResult = true,
                    message = "停止しました。直前の勤務表（必須=${keptReport.hard} 合計=${keptReport.total}）を保持しています。",
                ) }
                throw e
            } catch (e: Exception) {
                logOp("W", "ソフト研磨 失敗: ${e.message}")   // [3.271.0] 操作ログにも残す
                _ui.update { it.copy(running = false, message = "自動整えに失敗: ${e.message}") }
            } finally {
                optimizeActive = false   // [3.328.0] 長い最適化の終了（正常・停止・失敗すべて）
                clearRunMarker()   // [監査A8]
                if (_ui.value.running) _ui.update { it.copy(running = false) }
            }
        }
    }

    fun stop() {
        job?.cancel(); checkJob?.cancel(); fixJob?.cancel()
        // [監査A2] バックグラウンド実行(WorkManager)も停止する。従来は前景jobのみで、bg中は
        //   停止ボタンが実質無効・runningが結果到着まで固着していた。
        val bgWasRunning = OptimizationRepository.running.value
        runCatching { androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork(OptimizationWorker.UNIQUE) }
        if (bgWasRunning) {
            OptimizationRepository.clear()
            runCatching { OptimizationWorker.clearFiles(getApplication<Application>()) }
            _ui.update { it.copy(running = false, message = "停止しました（バックグラウンド計算を中断）") }
            logOp("I", "バックグラウンド最適化を停止")
        } else if (_ui.value.running || _ui.value.fixSearching) {
            // [3.284.0/外部レビューHigh③] 前景の違反チェック(checkJob)/改善探索(fixJob)を停止した場合、
            //   それらのコルーチンは running/fixSearching を戻す機会がなく実行中表示が固着していた。
            //   最適化ジョブ(job)自身は CancellationException 側で keep-best と running=false を行うため、
            //   ここでの即時リセットは冪等（後からジョブ側の確定メッセージが上書きする）。
            _ui.update { it.copy(running = false, fixSearching = false, message = "停止しました") }
        }
        clearRunMarker()
    }

    /** Shift indices a staff member may take (for the cell-edit bottom sheet). */
    // [メインスレッド負荷削減] cachedProblem を使用（兄弟の staffCellLimits/needCellLimits と統一）。
    //   本アクセサは StaffingRealityCard の `for i: allowedShiftsFor(i)` ループや ScheduleGrid の
    //   canDo ラムダ等、Compose の合成/再合成から O(職員数) 回呼ばれる。旧実装は呼び出し毎に
    //   Problem(st) を新規構築し（canDo/range/apt/wish 行列を毎回再割当）メインスレッドを浪費していた。
    //   Problem は state の純粋関数のため、state 参照で識別する ProblemCache のヒットに置換して等価かつ
    //   スコアリング不変（allowedShiftsForStaff は bucket を返す読み取り専用）。
    fun allowedShiftsFor(i: Int): IntArray {
        val st = state ?: return IntArray(0)
        return cachedProblem(st).allowedShiftsForStaff(i)
    }

    /** 入力ガイド（月次/年次の入力手順）用の各項目の件数。 */
    data class SetupCounts(
        val days: Int, val staff: Int, val shifts: Int, val groups: Int,
        val wishes: Int, val needDay: Int, val constraints: Int, val ranges: Int, val use2: Boolean,
    )
    fun setupCounts(): SetupCounts {
        val st = state ?: return SetupCounts(0, 0, 0, 0, 0, 0, 0, 0, false)
        val cons = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size +
            st.cons3m.size + st.cons3mn.size + st.cons41.size + st.cons42.size
        return SetupCounts(
            st.dayCount, st.staffCount, st.shiftCount, st.groupCount,
            st.wishes.size, st.needDay1.size + st.needDay2.size, cons, st.staffRange.size, st.use2Patterns,
        )
    }

    /** 担当外（そのスタッフのグループで担当不可）な希望の件数。希望で上書き時の確認に使う。 */
    fun wishOutOfScopeCount(): Int {
        val st = state ?: return 0
        val p = Problem(st)
        var n = 0
        for ((key, k) in st.wishes) {
            val i = key.split(',').getOrNull(0)?.toIntOrNull() ?: continue
            if (i in 0 until p.S && k in 0 until p.K && !p.canDo(i, k)) n++
        }
        return n
    }

    /**
     * 希望シフトを勤務表へ上書き反映（Web版の「希望で上書き」相当）。担当外の希望は
     * [includeOutOfScope]=true のときのみ反映。Undo・操作ログ付き。
     */
    fun applyWishes(includeOutOfScope: Boolean) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val p = Problem(st)
        pushUndo()
        var applied = 0
        var oos = 0
        for ((key, k) in st.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) continue
            val can = p.canDo(i, k)
            if (!can && !includeOutOfScope) continue
            if (i in sched.indices && j in sched[i].indices && sched[i][j] != k) {
                sched[i][j] = k
                applied++
                if (!can) oos++
            }
        }
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        val note = if (oos > 0) "（担当外 ${oos}件含む）" else ""
        logOp(if (oos > 0) "W" else "I", "希望を勤務表へ反映 ${applied}件$note")
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "希望を反映: ${applied}件$note",
        ) }
        refreshCheck()
    }

    private var alternativeScheds: List<Array<IntArray>> = emptyList()

    /** 直近の並列最適化で得た「他の案」を取り込み、サマリをUIへ反映。 */
    /** [3.335.0/外部レビュー P1] 「他の案」は可変 static でなく `handleOptimize` の返り値から受け取る
     *  （実行が重なると static は新しい実行の値に置き換わり、別の実行の候補を掴み得た）。 */
    private suspend fun captureAlternatives(source: List<Array<IntArray>>) {
        val st = state ?: return
        val alts = source.map { it.copy2D() }
        alternativeScheds = alts
        // [Main負荷回避] 他案（最大3件）の違反チェックは同期CPU → Default で実行してから反映。
        val summaries = withContext(Dispatchers.Default) {
            alts.mapIndexed { idx, sch ->
                val rep = UnifiedViolationChecker.check(st, sch)
                "案${idx + 1}: 必須=${rep.hard} 合計=${rep.total}"
            }
        }
        _ui.update { it.copy(alternatives = summaries) }
    }

    /** 「他の案」を勤務表へ適用（Undo・操作ログ付き）。 */
    fun applyAlternative(i: Int) {
        val st = state ?: return
        val sch = alternativeScheds.getOrNull(i)?.copy2D() ?: return
        pushUndo()
        currentSchedule = sch
        resultSchedule = sch
        state = st.withSchedule(sch)
        autoSave()
        viewModelScope.launch {
            val rep = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(state ?: st, sch) }
            pushReport(state ?: st, sch, rep) { it.copy(hasResult = true, message = "他の案 ${i + 1} を適用") }
            logOp("I", "他の案 ${i + 1} を適用 必須=${rep.hard} 合計=${rep.total}")
        }
    }

    /** Set a specific shift in a cell (bottom-sheet picker). */
    fun setCell(i: Int, j: Int, shift: Int) {
        val st = state ?: return
        // [監査(未レビュー領域再監査) 実バグ修正] running中は currentSchedule が最適化ジョブの sched0 と
        //   同一の配列参照＝ここで in-place 変更すると、完了時の baseReport(旧盤面基準)と食い違うか、
        //   良化採用時に編集が無言で上書き消失する。ジョブ完了まで編集を拒否する（他の直接変異API=
        //   setCells/cycleCell/applyFixSuggestion も同根のため同じガードを持つ）。
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は編集できません（完了後にもう一度お試しください）") }; return }
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        if (sched[i][j] == shift) return
        pushUndo()
        sched[i][j] = shift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(shift)?.kigou ?: shift} に変更",
        ) }
        logOp("I", "編集: ${opNm(i)} ${j + 1}日 → ${opSy(shift)}")
        refreshCheck()
    }

    /** [プロ一括編集] 複数セル(i,j)を1シフトへ一括設定。Undoは1回・再チェックも1回（keep-best互換）。 */
    fun setCells(cells: Collection<Pair<Int, Int>>, shift: Int) {
        val st = state ?: return
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は編集できません（完了後にもう一度お試しください）") }; return }
        val sched = currentSchedule ?: return
        var changed = 0
        var first = true
        for ((i, j) in cells) {
            if (i !in sched.indices || j !in sched[i].indices) continue
            if (sched[i][j] == shift) continue
            if (first) { pushUndo(); first = false }
            sched[i][j] = shift
            changed++
        }
        if (changed == 0) return
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${changed}マスを ${st.shifts.getOrNull(shift)?.kigou ?: shift} に一括変更",
        ) }
        logOp("I", "一括編集: ${changed}マス → ${opSy(shift)}")
        refreshCheck()
    }

    /** [operator_ux §5] 「なおすのを手伝って」用：ある不足枠(日×シフト)に1タップで入れられる候補職員。 */
    data class FixCandidate(val staffIndex: Int, val name: String, val groupSymbol: String, val fromRest: Boolean)
    fun shortageFixCandidates(dayIndex: Int, shiftIndex: Int): List<FixCandidate> {
        val st = state ?: return emptyList()
        val sched = currentSchedule ?: return emptyList()
        val p = Problem(st)
        if (shiftIndex !in 0 until p.K || dayIndex !in 0 until p.T) return emptyList()
        val rest = restShiftIndex(st)   // [監査A5] 休は記号解決（raw"休"比較は「公」職場で全滅していた）
        val out = ArrayList<FixCandidate>()
        for (i in 0 until p.S) {
            if (i !in sched.indices || dayIndex !in sched[i].indices) continue
            if (!p.canDo(i, shiftIndex)) continue            // 担当できないシフトは出さない
            if (sched[i][dayIndex] == shiftIndex) continue   // すでにそのシフト
            // [監査A5] 実現可能な希望のみ固定扱い（#11①整合: 不可能希望のセルはエンジン同様に可動）。
            if (p.wishLocked(i, dayIndex) && p.wish[i][dayIndex] != shiftIndex) continue
            val g = st.staff.getOrNull(i)?.groupIdx ?: -1
            out.add(FixCandidate(i, st.staff.getOrNull(i)?.name ?: "#$i", st.groups.getOrNull(g)?.kigou ?: "", sched[i][dayIndex] == rest))
        }
        // 休みの人（動かしやすい）を先頭に。
        out.sortBy { if (it.fromRest) 0 else 1 }
        return out
    }

    fun cycleCell(i: Int, j: Int) {
        val st = state ?: return
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は編集できません（完了後にもう一度お試しください）") }; return }
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        val p = Problem(st)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        val old = sched[i][j]
        val idx = allowed.indexOf(old)
        val next = allowed[(idx + 1).floorMod(allowed.size)]
        pushUndo()
        sched[i][j] = next
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(next)?.kigou ?: next} に変更",
        ) }
        logOp("I", "編集: ${opNm(i)} ${j + 1}日 → ${opSy(next)}")
        refreshCheck()
    }

    // [D7] 読取(結果)モードUIは撤去済み（勤務表=常に直接編集）。以下2関数は結果スナップショット操作の
    //   API として温存（UI 参照ゼロ・テスト非依存。結果モデル自体は最適化完了時に充填され続ける）。
    /** [B1] 編集中(ws7) を結果(ws6) として確定する。表示・違反は不変なのでスナップショットのみ更新。 */
    fun commitEditingToResult() {
        val cur = currentSchedule ?: return
        resultSchedule = cur.copy2D()
        _ui.update { it.copy(
            resultSchedule = cur.map { it.toList() },
            hasResultSnapshot = true,
            // [backlog#1] 結果=編集中の確定なので、結果専用マップも現行(編集中)の検査結果をそのまま引き継ぐ。
            //   直前編集の refreshCheck が進行中でも、完了時の makeUi が schedule==resultSchedule で再充填（自己修復）。
            resultViolationCells = it.violationCells,
            resultNeedViolations = it.needViolations,
            resultCountViolations = it.countViolations,
            resultViolationCellFamilies = it.violationCellFamilies,
            message = "編集中の内容を「結果」として確定しました",
        ) }
        logOp("I", "編集中→結果に確定")
    }

    /** [B1] 結果(ws6) を編集中(ws7) に複製（編集中は破棄、元に戻すで取消可）。 */
    fun copyResultToEditing() {
        val st = state ?: return
        val res = resultSchedule ?: return
        pushUndo()
        val sched = res.copy2D()
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "「結果」を編集中に複製しました（元に戻すで取消可）",
        ) }
        refreshCheck()
        logOp("I", "結果→編集中に複製")
    }

    // [D7撤去] hintReadOnly（読取モードの案内）は読取モード撤去に伴い削除（UI 参照ゼロ）。

    // ---- constraint editing (ws3-5) -------------------------------------------

    /** A constraint family with its rows rendered for display (key used for add/remove). */
    data class ConstraintFamilyView(val key: String, val title: String, val rows: List<String>)

    fun shiftKigouList(): List<String> = state?.shifts?.map { it.kigou } ?: emptyList()

    // ---- ws2: 日別の必要人数（例外） needDay1/needDay2 の疎な上書きを編集 ----
    data class NeedDayView(val k: Int, val j: Int, val kigou: String, val p1: String, val p2: String)

    fun needDayOverrides(): List<NeedDayView> {
        val st = state ?: return emptyList()
        val keys = (st.needDay1.keys + st.needDay2.keys).toSet()
        return keys.mapNotNull { key ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            NeedDayView(k, j, st.shifts.getOrNull(k)?.kigou ?: k.toString(), st.needDay1[key] ?: "", st.needDay2[key] ?: "")
        }.sortedWith(compareBy({ it.j }, { it.k }))
    }

    fun setNeedDay(k: Int, j: Int, p1: String, p2: String) {
        val st = state ?: return
        val key = "$k,$j"
        val nd1 = st.needDay1.toMutableMap()
        val nd2 = st.needDay2.toMutableMap()
        if (p1.isBlank()) nd1.remove(key) else nd1[key] = p1.trim()
        if (p2.isBlank()) nd2.remove(key) else nd2[key] = p2.trim()
        logOp("I", "需要設定: ${opSy(k)} ${j + 1}日 → P1=${p1.ifBlank { "-" }} P2=${p2.ifBlank { "-" }}")
        applyStructure(st.copy(needDay1 = nd1, needDay2 = nd2))
    }

    fun removeNeedDay(k: Int, j: Int) {
        val st = state ?: return
        val key = "$k,$j"
        logOp("I", "需要削除: ${opSy(k)} ${j + 1}日"); applyStructure(st.copy(needDay1 = st.needDay1 - key, needDay2 = st.needDay2 - key))
    }

    // ---- ws5: 個人別の回数（LimMin/LimMax） staffRange["i,k"]=Range(lo,hi) を編集 ----
    data class StaffRangeView(val i: Int, val k: Int, val staffName: String, val kigou: String, val lo: String, val hi: String)

    fun staffRangeOverrides(): List<StaffRangeView> {
        val st = state ?: return emptyList()
        return st.staffRange.mapNotNull { (key, r) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val k = parts[1].toIntOrNull() ?: return@mapNotNull null
            StaffRangeView(i, k, st.staff.getOrNull(i)?.name ?: i.toString(), st.shifts.getOrNull(k)?.kigou ?: k.toString(), r.lo, r.hi)
        }.sortedWith(compareBy({ it.i }, { it.k }))
    }

    fun setStaffRange(i: Int, k: Int, lo: String, hi: String) {
        val st = state ?: return
        val key = "$i,$k"
        val m = st.staffRange.toMutableMap()
        if (lo.isBlank() && hi.isBlank()) m.remove(key) else m[key] = Range(lo.trim(), hi.trim())
        logOp("I", "個人レンジ: ${opNm(i)} ${opSy(k)} → ${if (lo.isBlank() && hi.isBlank()) "削除" else "${lo.ifBlank { "?" }}〜${hi.ifBlank { "?" }}"}")
        applyStructure(st.copy(staffRange = m))
    }

    /**
     * [3.326.0] 回数固定(lo==hi)の幅を1段だけ広げる。**利用者のタップでのみ動く**（HF77: 数値の変更は
     * 業務判断）。幅の決め打ちを避けるため下限側・上限側を別々に選ばせ、押した内容は操作ログへ残す。
     * `applyStructure` 経由なので「元に戻す」で戻せる。
     *
     * @param loDelta 下限へ足す量（負で緩める）。@param hiDelta 上限へ足す量（正で緩める）。
     */
    fun relaxStaffRangePin(i: Int, k: Int, loDelta: Int, hiDelta: Int) {
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は回数を変更できません。終わってから試してください。") }; return }
        val st = state ?: return
        val cur = st.staffRange["$i,$k"] ?: return
        val lo = cur.lo.trim().toIntOrNull() ?: return
        val hi = cur.hi.trim().toIntOrNull() ?: return
        val newLo = (lo + loDelta).coerceAtLeast(0)
        val newHi = (hi + hiDelta).coerceAtLeast(newLo)
        if (newLo == lo && newHi == hi) return
        logOp("I", "回数固定を緩和: ${opNm(i)} ${opSy(k)} $lo〜$hi → $newLo〜$newHi（もう一度つくると効果が分かります）")
        setStaffRange(i, k, newLo.toString(), newHi.toString())
    }

    fun removeStaffRange(i: Int, k: Int) {
        val st = state ?: return
        logOp("I", "個人レンジ削除: ${opNm(i)} ${opSy(k)}"); applyStructure(st.copy(staffRange = st.staffRange - "$i,$k"))
    }

    // ---- グループ単位の回数（一括）: 既存 staffRange をグループ所属職員に展開する。
    //   新しい制約種別やスコア評価器の変更は不要（low/high は既に重み90/45で最適化対象）＝退行リスクなし。
    //   業務担当者が値を入力しボタンで適用する operator ツール（HF77準拠）。 ----
    fun groupLabels(): List<String> = state?.groups?.map {
        if (it.kigou.isNotBlank() && it.kigou != it.name) "${it.name}·${it.kigou}" else it.name
    } ?: emptyList()

    fun groupMemberCount(g: Int): Int = state?.staff?.count { it.groupIdx == g } ?: 0

    /** グループの全メンバーが担当できるシフトの積集合（下限を全員が満たせる範囲に限定し構造的floorを防ぐ）。 */
    fun allowedShiftsForGroup(g: Int): Set<Int> {
        val st = state ?: return emptySet()
        val members = st.staff.indices.filter { st.staff[it].groupIdx == g }
        if (members.isEmpty()) return emptySet()
        return members.map { allowedShiftsFor(it).toHashSet() }.reduce { a, b -> a.apply { retainAll(b) } }
    }

    /** グループ g 所属の全職員に、ws5 個人別[lo,hi](staffRange, low/high 重み90/45=強い境界) を一括設定し、
     *  さらに ws1 C のグループ別 適切回数(groupShiftApt, apt 重み1=弱い目標) も同時に書く。
     *  apt は「最低=最高」の単一値のときのみ設定（範囲指定や空欄時はクリア）＝Excelの ws1 C→ws5 展開を1操作で再現。 */
    fun setGroupRange(g: Int, k: Int, lo: String, hi: String) {
        val st0 = state ?: return
        val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
        if (members.isEmpty()) return
        val loT = lo.trim(); val hiT = hi.trim()
        if (loT.isBlank() && hiT.isBlank()) return
        // [共有ws5・スキップ方式] ws5(個人レンジ)へ直接書く。ただし既に個人値が在るメンバーは上書きせず保持する。
        //   ※同一保存先のため、適用後の値は個人値と区別がつかない→グループ範囲の後からの一括変更は不可
        //     （変更したい場合は対象のws5を一旦クリアして再適用）。Web/VBAの「ws5のみ」と厳密一致。
        val m = st0.staffRange.toMutableMap()
        var wrote = 0; var skipped = 0
        for (i in members) {
            val key = "$i,$k"
            val ex = m[key]
            if (ex != null && (ex.lo.isNotBlank() || ex.hi.isNotBlank())) { skipped++; continue }
            m[key] = Range(loT, hiT); wrote++
        }
        // ws1 C: グループ別 適切回数（弱い目標）。単一値(最低=最高)のときのみ設定。
        val aptVal = if (loT == hiT) loT else ""
        val stNew = Ws1Ops.setGroupApt(st0.copy(staffRange = m), g, k, aptVal)
        val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
        logOp("I", "グループ一括: $gname ${opSy(k)} → ws5=${loT.ifBlank { "?" }}〜${hiT.ifBlank { "?" }} (書込${wrote}名/スキップ${skipped}名・既存個人値は保持)")
        applyStructure(stNew)
    }

    /** [共有ws5・スキップ方式] グループ既定の解除: 表示中レンジ(lo,hi)と一致するメンバーのws5だけ削除する。
     *  個人で別値にした職員(レンジが違う)は保持する。サマリの×から呼ぶ。 */
    fun clearGroupRange(g: Int, k: Int, lo: String, hi: String) {
        val st0 = state ?: return
        val members = st0.staff.indices.filter { st0.staff[it].groupIdx == g }
        if (members.isEmpty()) return
        val loT = lo.trim(); val hiT = hi.trim()
        val m = st0.staffRange.toMutableMap()
        var cleared = 0
        for (i in members) {
            val key = "$i,$k"; val r = m[key] ?: continue
            if (r.lo.trim() == loT && r.hi.trim() == hiT) { m.remove(key); cleared++ }
        }
        if (cleared == 0) return
        val stNew = Ws1Ops.setGroupApt(st0.copy(staffRange = m), g, k, "")
        val gname = st0.groups.getOrNull(g)?.name ?: "#$g"
        logOp("I", "グループ既定解除: $gname ${opSy(k)} → ${cleared}名のws5削除（個人別値は保持）")
        applyStructure(stNew)
    }

    data class GroupRangeView(val g: Int, val k: Int, val groupName: String, val kigou: String, val lo: String, val hi: String, val members: Int, val shared: Int = members)

    /** 「グループ単位の回数」適用済み一覧。グループ全メンバーが同一の非空レンジを持つ (g,k) のみ＝
     *  一括適用された(個別に変更されていない)グループ上下限を再構成して表示する。×で全員分をクリア。 */
    fun groupRangeSummary(): List<GroupRangeView> {
        val st = state ?: return emptyList()
        val out = mutableListOf<GroupRangeView>()
        st.groups.forEachIndexed { g, gr ->
            val members = st.staff.indices.filter { st.staff[it].groupIdx == g }
            if (members.isEmpty()) return@forEachIndexed
            st.shifts.forEachIndexed { k, sh ->
                // [緩和] メンバーの非空レンジを (lo,hi) で集計し、最多共有レンジを代表として出す。完全一致で
                //   なくても「2名以上が共有」なら表示し N/M名 を添える。これにより一括適用後に一部メンバーを
                //   個別編集しても、グループ単位のレンジが消えずに残って見える（旧実装は全員一致のみ表示）。
                val counts = HashMap<Pair<String, String>, Int>()
                for (i in members) {
                    val r = st.staffRange["$i,$k"] ?: continue
                    if (r.lo.isBlank() && r.hi.isBlank()) continue
                    val key = r.lo to r.hi
                    counts[key] = (counts[key] ?: 0) + 1
                }
                val best = counts.maxByOrNull { it.value }
                if (best != null && (best.value >= 2 || members.size == 1)) {
                    out.add(GroupRangeView(g, k, gr.name, sh.kigou, best.key.first, best.key.second, members.size, best.value))
                }
            }
        }
        return out.sortedWith(compareBy({ it.g }, { it.k }))
    }

    /** [直せる導線] 集計セル(職員別)の違反詳細用しきい値: 下限/上限(staffRange)・目標(apt実効)。未設定は null。 */
    fun staffCellLimits(i: Int, k: Int): Triple<Int?, Int?, Int?> {
        val st = state ?: return Triple(null, null, null)
        val p = cachedProblem(st)
        if (i !in 0 until p.S || k !in 0 until p.K) return Triple(null, null, null)
        val lo = p.rangeLo[i][k].let { if (it == Int.MIN_VALUE || it == 0) null else it }
        val hi = p.rangeHi[i][k].let { if (it == Int.MAX_VALUE) null else it }
        val apt = p.apt[i][k].let { if (it < 0) null else it }
        return Triple(lo, hi, apt)
    }

    /** [直せる導線] 集計セル(日別)の必要数レンジ lo..hi（need1/need2）。lo<0(対象外)は null。 */
    fun needCellLimits(k: Int, j: Int): Pair<Int, Int>? {
        val st = state ?: return null
        val p = cachedProblem(st)
        if (k !in 0 until p.K || j !in 0 until p.T) return null
        val lo = p.need1[k][j]
        if (lo < 0) return null
        val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else lo
        return lo to hi
    }

    /** [回数センター] 個人別の回数(上下限)と適切回数(apt)を職員×シフトで統合した一覧。
     *  staffRange または apt(実効=担当可＆クランプ後)が効くセルのみ返す。aptEff=実効目標(-1=なし),
     *  aptRaw=群目標の生値(-1=なし。aptEff と異なればクランプされている)。hasRange=個人別の上下限あり。 */
    data class CountRuleView(
        val i: Int, val k: Int, val staffName: String, val kigou: String,
        val lo: String, val hi: String, val aptEff: Int, val aptRaw: Int, val hasRange: Boolean,
    )

    fun staffCountRules(): List<CountRuleView> {
        val st = state ?: return emptyList()
        // [レビュー#1] 再描画毎に呼ばれるため cachedProblem で Problem 再構築(高コスト)を避ける。
        val p = cachedProblem(st)
        val rows = mutableListOf<CountRuleView>()
        for (i in 0 until p.S) {
            val g = st.staff.getOrNull(i)?.groupIdx ?: continue
            for (k in 0 until p.K) {
                val r = st.staffRange["$i,$k"]
                val hasRange = r != null && (r.lo.isNotBlank() || r.hi.isNotBlank())
                val aptEff = p.apt[i][k]
                if (!hasRange && aptEff < 0) continue
                val aptRaw = st.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toIntOrNull() ?: -1
                rows.add(
                    CountRuleView(
                        i, k, st.staff[i].name, st.shifts.getOrNull(k)?.kigou ?: k.toString(),
                        r?.lo ?: "", r?.hi ?: "", aptEff, if (aptEff >= 0) aptRaw else -1, hasRange,
                    )
                )
            }
        }
        return rows.sortedWith(compareBy({ it.i }, { it.k }))
    }

    // [3.286.0 冗長性B] 旧「回数設定画面」(CountSettingsCard, 2.60〜2.63世代)の集約ビュー
    //   （shiftRuleBlocks/staffRuleBlocks/setCons41＋GroupRule等のデータ型）は、画面本体の撤去後も
    //   呼出0のまま残存していた孤児クラスタのため削除（grep で外部参照0を確認済み）。

    // ---- ws3 移植: 希望シフト wishes["i,j"]=シフトindex（採点=pref/hard1。割当やcons3系とは別。UIのみ・モデル/エンジン不変）----
    data class WishView(val i: Int, val j: Int, val staffName: String, val day: Int, val kigou: String, val k: Int)

    fun wishOverrides(): List<WishView> {
        val st = state ?: return emptyList()
        return st.wishes.mapNotNull { (key, k) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            WishView(i, j, st.staff.getOrNull(i)?.name ?: i.toString(), j + 1, st.shifts.getOrNull(k)?.kigou ?: k.toString(), k)
        }.sortedWith(compareBy({ it.i }, { it.j }))
    }

    fun setWish(i: Int, j: Int, k: Int) {
        val st = state ?: return
        val m = st.wishes.toMutableMap()
        m["$i,$j"] = k
        logOp("I", "希望設定: ${opNm(i)} ${j + 1}日 → ${opSy(k)}")
        applyStructure(st.copy(wishes = m))
    }

    fun removeWish(i: Int, j: Int) {
        val st = state ?: return
        logOp("I", "希望削除: ${opNm(i)} ${j + 1}日")
        applyStructure(st.copy(wishes = st.wishes - "$i,$j"))
    }

    /** [一括] スタッフ(null=全員)×日群に希望 k を一括設定。Undo1回・再チェック1回。 */
    fun setWishesForDays(staffIdx: Int?, days: List<Int>, k: Int) {
        val st = state ?: return
        if (days.isEmpty() || k !in st.shifts.indices) return
        val m = st.wishes.toMutableMap()
        val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
        for (i in staffRange) for (j in days) if (i in st.staff.indices && j in 0 until st.dayCount) m["$i,$j"] = k
        logOp("I", "希望一括: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)} → ${opSy(k)}")
        applyStructure(st.copy(wishes = m))
    }

    /** [一括] スタッフ(null=全員)×日群の希望を一括削除。 */
    fun clearWishesForDays(staffIdx: Int?, days: List<Int>) {
        val st = state ?: return
        if (days.isEmpty()) return
        val m = st.wishes.toMutableMap()
        val staffRange = if (staffIdx != null) listOf(staffIdx) else st.staff.indices.toList()
        for (i in staffRange) for (j in days) m.remove("$i,$j")
        if (m.size == st.wishes.size) return
        logOp("I", "希望クリア: ${if (staffIdx != null) opNm(staffIdx) else "全員"} ${opDays(days)}")
        applyStructure(st.copy(wishes = m))
    }

    /** [一括] すべての希望を削除。 */
    fun clearAllWishes() {
        val st = state ?: return
        if (st.wishes.isEmpty()) return
        logOp("I", "希望全クリア")
        applyStructure(st.copy(wishes = emptyMap()))
    }

    // ---- colors: シフトの表示色 shiftColors[kigou]="#rrggbb"（表示専用）----
    data class ShiftColorView(val kigou: String, val name: String, val hex: String, val custom: Boolean)

    fun shiftColorList(): List<ShiftColorView> {
        val st = state ?: return emptyList()
        return st.shifts.mapIndexed { i, sh ->
            val ov = st.shiftColors[sh.kigou]
            ShiftColorView(sh.kigou, sh.name, V6WebCompat.resolveShiftColor(sh.kigou, sh.name, ov, i), !ov.isNullOrBlank())
        }
    }

    fun setShiftColor(kigou: String, hex: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        val m = st.shiftColors.toMutableMap()
        m[kigou] = hex.trim()
        applyStructure(st.copy(shiftColors = m))
    }

    fun resetShiftColor(kigou: String) {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - kigou))
    }
    /** [違反色] 違反セルの枠/マーカー色。予約キー "__vio__" に保存（状態スキーマ非変更）。 */
    fun setViolationColor(hex: String) {
        val st = state ?: return; if (hex.isBlank()) return
        applyStructure(st.copy(shiftColors = st.shiftColors + ("__vio__" to hex.trim())))
    }
    fun resetViolationColor() {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - "__vio__"))
    }
    /** [違反色] 要調整(ソフト違反)の枠/マーカー色。予約キー "__vioSoft__"（空=既定の橙）。 */
    fun setViolationSoftColor(hex: String) {
        val st = state ?: return; if (hex.isBlank()) return
        applyStructure(st.copy(shiftColors = st.shiftColors + ("__vioSoft__" to hex.trim())))
    }
    fun resetViolationSoftColor() {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - "__vioSoft__"))
    }

    /** [違反色/族別] 違反種別（族）ごとの個別色。予約キー "__vioFam_<fam>__"（例: __vioFam_c3n__）。
     *  未設定の族は重大度色（__vio__/__vioSoft__）へフォールバック。 */
    fun setViolationFamilyColor(fam: String, hex: String) {
        val st = state ?: return; if (hex.isBlank() || fam.isBlank()) return
        applyStructure(st.copy(shiftColors = st.shiftColors + ("__vioFam_${fam}__" to hex.trim())))
    }
    fun resetViolationFamilyColor(fam: String) {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - "__vioFam_${fam}__"))
    }

    // ---- [見直し候補] 月次の修正から「基本ルールの見直し候補」を積む軽量メモ（セッション内のみ・state 非保存） ----
    fun addReviewMemo(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(reviewMemos = it.reviewMemos + text.trim(), message = "見直し候補に追加しました") }
    }
    fun removeReviewMemo(index: Int) {
        _ui.update { val l = it.reviewMemos; if (index !in l.indices) it else it.copy(reviewMemos = l.filterIndexed { j, _ -> j != index }) }
    }
    fun groupKigouList(): List<String> = state?.groups?.map { it.kigou } ?: emptyList()

    /** [冗長除去/データ密度] 1日人数の上下限 [l〜u] を意味で圧縮して短く表す。見出しが「人数(上下限)」の
     *  文脈を担うので、行は記号のみで足りる。l==u=ちょうどN / 下限のみ=N以上 / 上限のみ=N以下 / 両方=l〜u。 */
    private fun boundLabel(l: String, u: String): String {
        val lo = l.ifBlank { null }; val hi = u.ifBlank { null }
        return when {
            lo != null && hi != null && lo == hi -> "ちょうど$lo"
            lo != null && hi != null -> "$lo〜$hi"
            lo != null -> "$lo 以上"
            hi != null -> "$hi 以下"
            else -> "制限なし"
        }
    }

    fun constraintFamilies(): List<ConstraintFamilyView> {
        val st = state ?: return emptyList()
        fun seq(p: List<String>) = p.filter { it.isNotBlank() }.joinToString(" -> ").ifEmpty { "(空)" }
        return listOf(
            // [用語統一/下流→上流] 節タイトルは違反チップ(breakdownLabels)の語彙を正として一致させる
            //   （違反を見て設定を直しに来たとき同じ名前で見つかるように）。単位や補足は括弧で添える。
            ConstraintFamilyView("cons1", "窓の要件（○日間に△回以上）",
                st.cons1.map { "${it.shiftKigou}   ${it.day1}日で${it.day2}回以上" }),
            ConstraintFamilyView("cons2", "個人の合計（回数）",
                st.cons2.map { "${it.shiftKigou}   合計${it.count}回以上" }),
            ConstraintFamilyView("cons3", "必須の並び", st.cons3.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3n", "禁止の並び", st.cons3n.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3m", "推奨の並び", st.cons3m.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3mn", "回避の並び", st.cons3mn.map { seq(it.pattern) }),
            ConstraintFamilyView("cons41", "群のレンジ（1日の人数の下限〜上限）",
                st.cons41.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42", "群ペア禁止（同じ日に不可）",
                st.cons42.map { "${it.g1Kigou}・${it.s1Kigou}  ✕  ${it.g2Kigou}・${it.s2Kigou}" }),
        )
    }

    /** [スキルグループ専用ルール] C41s/C42s。スキルグループ定義の直下に co-locate して表示する。 */
    fun skillConstraintFamilies(): List<ConstraintFamilyView> {
        val st = state ?: return emptyList()
        return listOf(
            ConstraintFamilyView("cons41s", "スキル群のレンジ（1日の人数の下限〜上限）",
                st.cons41s.map { "${it.groupKigou}・${it.shiftKigou}   ${boundLabel(it.l, it.u)}" }),
            ConstraintFamilyView("cons42s", "スキル群ペア禁止（同じ日に不可）",
                st.cons42s.map { "${it.g1Kigou}・${it.s1Kigou}  ✕  ${it.g2Kigou}・${it.s2Kigou}" }),
        )
    }

    fun skillGroupKigouList(): List<String> = state?.skillGroups?.map { it.kigou } ?: emptyList()
    fun addCons41s(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        logOp("I", "制約追加(スキル群回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41s = st.cons41s + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
    }
    fun addCons42s(g1: String, g2: String, s1: String, s2: String) {
        val st = state ?: return
        logOp("I", "制約追加(スキル群組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42s = st.cons42s + C42Row(g1, g2, s1, s2)))
    }

    fun addCons1(day1: String, shiftKigou: String, day2: String) {
        val st = state ?: return
        logOp("I", "制約追加(連勤/休): ${day1.trim()}日に${shiftKigou}${day2.trim()}回以上"); mutateConstraints(st.copy(cons1 = st.cons1 + C1Row(day1.trim(), shiftKigou, day2.trim())))
    }

    fun addCons2(shiftKigou: String, count: String) {
        val st = state ?: return
        logOp("I", "制約追加(cons2): $shiftKigou ${count.trim()}"); mutateConstraints(st.copy(cons2 = st.cons2 + C2Row(shiftKigou, count.trim())))
    }

    fun addCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        logOp("I", "制約追加(群回数): $groupKigou $shiftKigou ${l.trim()}〜${u.trim()}"); mutateConstraints(st.copy(cons41 = st.cons41 + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
    }

    fun addCons42(g1: String, g2: String, s1: String, s2: String) {
        val st = state ?: return
        logOp("I", "制約追加(群組合せ禁止): ${g1}${s1} & ${g2}${s2}"); mutateConstraints(st.copy(cons42 = st.cons42 + C42Row(g1, g2, s1, s2)))
    }

    fun addCons3(family: String, pattern: List<String>) {
        val st = state ?: return
        // Level Zero loads cons3 by reading day columns until the first blank (truncate at
        // first blank, max 5 days), not by removing all blanks. Match that here.
        val pat = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)
        if (pat.isEmpty()) return
        logOp("I", "制約追加($family): ${pat.joinToString("→")}")
        mutateConstraints(
            when (family) {
                "cons3" -> st.copy(cons3 = st.cons3 + C3Row(pat))
                "cons3n" -> st.copy(cons3n = st.cons3n + C3Row(pat))
                "cons3m" -> st.copy(cons3m = st.cons3m + C3Row(pat))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn + C3Row(pat))
                else -> return
            }
        )
    }

    fun removeConstraint(family: String, index: Int) {
        val st = state ?: return
        // [3.271.0, 実機ログ起因] index を先に検証する。旧: 検証なしで先にログ→mutate のため、
        //   リスト縮小後の古い index（連続タップ等）でも「制約削除: cons3mn[7]」の幻ログ＋無駄な
        //   undo/保存/再検査が走り、実機ログで「2回削除されたのか1回なのか」が判別不能だった
        //   （without() 自体は no-op なのでデータは壊れない＝ログと副作用だけが嘘をつく状態）。
        val size = when (family) {
            "cons1" -> st.cons1.size
            "cons2" -> st.cons2.size
            "cons3" -> st.cons3.size
            "cons3n" -> st.cons3n.size
            "cons3m" -> st.cons3m.size
            "cons3mn" -> st.cons3mn.size
            "cons41" -> st.cons41.size
            "cons42" -> st.cons42.size
            "cons41s" -> st.cons41s.size
            "cons42s" -> st.cons42s.size
            else -> return
        }
        if (index !in 0 until size) {
            logOp("W", "制約削除を無視: $family[$index] は存在しません（削除済みの行への連続タップ等）")
            return
        }
        logOp("I", "制約削除: $family[$index]")
        fun <T> List<T>.without(i: Int) = filterIndexed { idx, _ -> idx != i }
        mutateConstraints(
            when (family) {
                "cons1" -> st.copy(cons1 = st.cons1.without(index))
                "cons2" -> st.copy(cons2 = st.cons2.without(index))
                "cons3" -> st.copy(cons3 = st.cons3.without(index))
                "cons3n" -> st.copy(cons3n = st.cons3n.without(index))
                "cons3m" -> st.copy(cons3m = st.cons3m.without(index))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn.without(index))
                "cons41" -> st.copy(cons41 = st.cons41.without(index))
                "cons42" -> st.copy(cons42 = st.cons42.without(index))
                "cons41s" -> st.copy(cons41s = st.cons41s.without(index))
                "cons42s" -> st.copy(cons42s = st.cons42s.without(index))
                else -> return
            }
        )
    }

    /** [制約編集/実機指摘「登録した制約の変更ができない」] 行の生値（編集ダイアログのプリフィル用）。
     *  値の並びは追加ダイアログの入力順と同じ:
     *  cons1=[日数,シフト,回数] / cons2=[シフト,回数] / cons3系=並び(最大5) /
     *  cons41(s)=[群,シフト,下限,上限] / cons42(s)=[群1,シフト1,群2,シフト2]。 */
    fun constraintRowValues(family: String, index: Int): List<String>? {
        val st = state ?: return null
        return when (family) {
            "cons1" -> st.cons1.getOrNull(index)?.let { listOf(it.day1, it.shiftKigou, it.day2) }
            "cons2" -> st.cons2.getOrNull(index)?.let { listOf(it.shiftKigou, it.count) }
            "cons3" -> st.cons3.getOrNull(index)?.pattern
            "cons3n" -> st.cons3n.getOrNull(index)?.pattern
            "cons3m" -> st.cons3m.getOrNull(index)?.pattern
            "cons3mn" -> st.cons3mn.getOrNull(index)?.pattern
            "cons41" -> st.cons41.getOrNull(index)?.let { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) }
            "cons41s" -> st.cons41s.getOrNull(index)?.let { listOf(it.groupKigou, it.shiftKigou, it.l, it.u) }
            "cons42" -> st.cons42.getOrNull(index)?.let { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) }
            "cons42s" -> st.cons42s.getOrNull(index)?.let { listOf(it.g1Kigou, it.s1Kigou, it.g2Kigou, it.s2Kigou) }
            else -> null
        }
    }

    /** [制約編集] 行を同じ位置で置き換える。values の並びは constraintRowValues と同一。
     *  cons3系は追加(addCons3)と同じ正規化（先頭から最初の空白まで・最大5）。 */
    fun updateConstraint(family: String, index: Int, values: List<String>) {
        val st = state ?: return
        fun <T> List<T>.replaced(i: Int, v: T) = mapIndexed { idx, e -> if (idx == i) v else e }
        val v = values.map { it.trim() }
        fun g(i: Int) = v.getOrElse(i) { "" }
        val next = when (family) {
            "cons1" -> { if (index !in st.cons1.indices) return; st.copy(cons1 = st.cons1.replaced(index, C1Row(g(0), g(1), g(2)))) }
            "cons2" -> { if (index !in st.cons2.indices) return; st.copy(cons2 = st.cons2.replaced(index, C2Row(g(0), g(1)))) }
            "cons41" -> { if (index !in st.cons41.indices) return; st.copy(cons41 = st.cons41.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
            "cons41s" -> { if (index !in st.cons41s.indices) return; st.copy(cons41s = st.cons41s.replaced(index, C41Row(g(0), g(1), g(2), g(3)))) }
            "cons42" -> { if (index !in st.cons42.indices) return; st.copy(cons42 = st.cons42.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
            "cons42s" -> { if (index !in st.cons42s.indices) return; st.copy(cons42s = st.cons42s.replaced(index, C42Row(g(0), g(2), g(1), g(3)))) }
            "cons3", "cons3n", "cons3m", "cons3mn" -> {
                val pat = v.takeWhile { it.isNotEmpty() }.take(5)
                if (pat.isEmpty()) return
                when (family) {
                    "cons3" -> { if (index !in st.cons3.indices) return; st.copy(cons3 = st.cons3.replaced(index, C3Row(pat))) }
                    "cons3n" -> { if (index !in st.cons3n.indices) return; st.copy(cons3n = st.cons3n.replaced(index, C3Row(pat))) }
                    "cons3m" -> { if (index !in st.cons3m.indices) return; st.copy(cons3m = st.cons3m.replaced(index, C3Row(pat))) }
                    else -> { if (index !in st.cons3mn.indices) return; st.copy(cons3mn = st.cons3mn.replaced(index, C3Row(pat))) }
                }
            }
            else -> return
        }
        logOp("I", "制約変更: $family[$index] → ${v.filter { it.isNotBlank() }.joinToString(" ")}")
        mutateConstraints(next)
    }

    /** Apply an edited state (constraints changed), then re-run the unified check on the current table. */
    /**
     * [3.328.0/外部レビュー・実行中編集] 意味論を変える編集が最適化の最中に入るのを止める。
     *
     * 個々の編集画面へ `!ui.running` を配るやり方は、これまで何度も取りこぼしてきた
     * （3.161.0＝セル編集・3.127.0＝一括シート）。編集は最終的に必ず
     * [applyStructure] / [applyStructureWithMessage] / [mutateConstraints] のどれかを通るので、
     * **その3つの入口だけ**を塞ぐ。呼び出し元が先にログを出していることがあるので、
     * 取り消したことも記録して読み手が混乱しないようにする。
     */
    private fun structuralEditBlocked(): Boolean {
        if (!optimizeInFlight()) return false
        logOp("W", "計算の実行中のため設定変更を取り消しました（終わってから、または「やめる」の後にどうぞ）")
        _ui.update { it.copy(message = "計算中は設定を変更できません。終わるか「やめる」を押してからにしてください。") }
        return true
    }

    private fun mutateConstraints(newState: MagiState?) {
        val ns = newState ?: return
        if (structuralEditBlocked()) return
        pushUndo()
        state = ns
        // [3.222.0, 実機バグ修正「回避の並びなどが削除できない」] constraintsEdited が既に true だと
        //   copy が同値でStateFlowがemitせず（3.185.0/3.189.0と同型）、ConstraintsCard/SkillConstraintsCard
        //   を包む key(ui.editRev) が再構成されず一覧が更新されなかった。editRev を必ず増やして
        //   distinct な UiState を emit する（3.185.0のapplyStructureと同一パターン）。
        _ui.update { it.copy(constraintsEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    // ---- ws1 initial setup ----------------------------------------------------

    /** Snapshot of the ws1 (初期設定) data for the editor. Recomputed per call (cheap). */
    data class Ws1View(
        val startDate: String, val endDate: String, val days: Int, val use2: Boolean,
        val shifts: List<Shift>, val groups: List<Group>, val staff: List<Staff>,
        val groupShift: List<List<Int>>,
        val groupShiftApt: List<List<String>>,
    )

    fun ws1(): Ws1View? {
        val st = state ?: return null
        val days = currentSchedule?.firstOrNull()?.size ?: st.dayCount
        return Ws1View(st.startDate, st.endDate, days, st.use2Patterns, st.shifts, st.groups, st.staff, st.groupShift, st.groupShiftApt)
    }

    private fun applyStructure(ns: MagiState) {
        if (structuralEditBlocked()) return
        pushUndo()
        state = ns
        // [再構成保証] editRev を必ず増やして distinct な UiState を emit（structureEdited 既true時の非emit＋
        //   currentSchedule=null 時の refreshCheck 早期return で編集画面が再構成されない「+/-で数字が変わらない」修正）。
        _ui.update { it.copy(structureEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    /** 構造変更(ns)を適用し、再チェック後に独自の完了メッセージを表示（コンポーネント別取込で使用）。 */
    private fun applyStructureWithMessage(ns: MagiState, doneMessage: String) {
        if (structuralEditBlocked()) return
        pushUndo()
        state = ns
        autoSave()
        val sched = currentSchedule?.copy2D()
        if (sched == null) { _ui.update { it.copy(structureEdited = true, message = doneMessage) }; return }
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.update { it.copy(running = true, structureEdited = true, message = "$doneMessage（違反チェック中…）") }
        checkJob = viewModelScope.launch {
            try {
                val r = V6FinalPort.handleCheck(ns, sched)
                if (seq != checkSeq) return@launch
                pushReport(ns, r.schedule, r.report) { it.copy(running = optimizeInFlight(), message = "$doneMessage｜必須=${r.report.hard} 合計=${r.report.total}") }
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] stop() によるキャンセル時の running 固着を解消（refreshCheck と同型）。
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "$doneMessage（チェックを停止）") }
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "$doneMessage（チェック失敗: ${e.message}）") }
            }
        }
    }

    /**
     * [ワンタップ修正] 設定の見直しカードの1ボタンで、該当する設定ミスをその場で直す。
     * 画面遷移・スクロール・行探し不要。applyStructure 経由なので Undo 可・自動再診断・自動保存される。
     */
    /**
     * [壁になっている禁止の並びを緩める] ForbiddenDiag が「崩せない」と判定した禁止連続(c3n)ルールを、
     * その場で削除する。制約画面まで行って該当行を探す必要をなくすための導線。
     *
     * データを変えるのは**利用者の明示操作**（HF77 に抵触しない）。`applyStructure` 経由なので
     * Undo 可・自動再診断・自動保存される。同じ並びが重複登録されている場合は全件まとめて消す
     * （1件だけ残ると壁が解消しないため。cons3n の重複は既知＝設定ミス診断でも指摘される）。
     *
     * キーは `Problem.resolveC3` と同じ意味論（**最初の空白まで**を本体とする）で作る。
     * `SettingFixAction.DELETE_DUP_SEQ` の空白除去とは意味が違うので流用しない。
     */
    /**
     * [目標の検算] シフトごとの「適切回数(apt)の合計 vs それを受け止められる上限」。
     *
     * `V6SanityPort.aptBalances` をそのまま返す＝設定ミス診断（検査6-C）と**同じ単一ソース**。
     * 盤面を参照しないので、勤務表を作る前（未計算）でも目標を触るたびに正しい値が出る
     * （`settingIssues` は `refreshCheck` 経由＝盤面が無いと更新されないため、設定中は届かない）。
     */
    fun aptBalances(): List<V6SanityPort.AptBalance> {
        val st = state ?: return emptyList()
        return runCatching { V6SanityPort.aptBalances(st) }.getOrDefault(emptyList())
    }

    fun relaxForbiddenRule(seqLabel: String) {
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は設定を変更できません（完了後にもう一度お試しください）") }; return }
        val s = state ?: return
        fun key(row: C3Row): String {
            val end = row.pattern.indexOfFirst { it.isBlank() }
            val body = if (end >= 0) row.pattern.subList(0, end) else row.pattern
            return body.joinToString("→")
        }
        val remain = s.cons3n.filter { key(it) != seqLabel }
        val removed = s.cons3n.size - remain.size
        if (removed == 0) {
            _ui.update { it.copy(message = "禁止の並び「$seqLabel」は見つかりませんでした") }
            return
        }
        logOp("I", "禁止の並びを削除: $seqLabel（${removed}件）")
        applyStructureWithMessage(s.copy(cons3n = remain), "禁止の並び「$seqLabel」を削除しました（${removed}件・元に戻せます）")
    }

    fun applySettingFix(issue: SettingIssue) {
        val s = state ?: return
        val ns: MagiState? = when (issue.action) {
            SettingFixAction.REMOVE_WISH -> {
                val key = issue.wishKey ?: return
                if (!s.wishes.containsKey(key)) return
                s.copy(wishes = s.wishes - key)
            }
            SettingFixAction.DELETE_DUP_SEQ -> {
                val fam = issue.seqFamily ?: return
                val key = issue.seqKey ?: return
                fun delOne(rows: List<C3Row>): List<C3Row> {
                    var done = false
                    val res = ArrayList<C3Row>(rows.size)
                    for (row in rows) {
                        val joined = row.pattern.filter { it.isNotBlank() }.joinToString("→")
                        if (!done && joined == key) { done = true; continue }
                        res.add(row)
                    }
                    return res
                }
                when (fam) {
                    "c3" -> s.copy(cons3 = delOne(s.cons3))
                    "c3n" -> s.copy(cons3n = delOne(s.cons3n))
                    "c3m" -> s.copy(cons3m = delOne(s.cons3m))
                    "c3mn" -> s.copy(cons3mn = delOne(s.cons3mn))
                    else -> return
                }
            }
            SettingFixAction.ZERO_RANGE_LO, SettingFixAction.CLAMP_RANGE_LO -> {
                val key = issue.rangeKey ?: return
                val cur = s.staffRange[key] ?: Range("", "")
                s.copy(staffRange = s.staffRange + (key to Range(issue.newLo ?: cur.lo, cur.hi)))
            }
            SettingFixAction.CAP_DEMAND -> {
                val k = issue.demandShiftIdx ?: return
                val cap = issue.demandCap ?: return
                val sh = s.shifts.getOrNull(k) ?: return
                val n1 = sh.need1.trim().toIntOrNull()
                val n2 = sh.need2.trim().toIntOrNull()
                val newN1 = if (n1 != null && n1 > cap) cap.toString() else sh.need1
                val newN2 = if (n2 != null && n2 > cap) cap.toString() else sh.need2
                if (newN1 == sh.need1 && newN2 == sh.need2) return
                val list = s.shifts.toMutableList()
                list[k] = sh.copy(need1 = newN1, need2 = newN2)
                s.copy(shifts = list)
            }
            SettingFixAction.NONE -> null
        }
        if (ns != null) applyStructure(ns)
    }

    /**
     * [改善提案] 違反を減らす「1手（変更/交換）」を探索して UI に提示する。
     * focusStaff != null のときはそのスタッフが関わる手だけに絞る（違反タップ起点）。重い処理のため非同期。
     */
    fun findFixSuggestions(focusStaff: Int? = null, focusShift: Int? = null) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val focusName = focusStaff?.let { st.staff.getOrNull(it)?.name } ?: ""
        val snap = sched.copy2D()
        fixJob?.cancel()   // 連続タップ時の前探索を破棄（古い結果で UI を上書きしない）
        _ui.update { it.copy(fixSearching = true, fixFocusName = focusName) }
        fixJob = viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                FixSuggester.suggest(st, snap, focusStaff = focusStaff, focusShift = focusShift, maxResults = 8)
            }
            _ui.update { it.copy(fixSuggestions = list, fixSearching = false, fixFocusName = focusName) }
        }
    }

    /** [改善提案] 改善手を1タップで適用（ops のセル代入を一括反映）。Undo 可・自動再診断・自動保存。 */
    fun applyFixSuggestion(s: FixSuggestion) {
        val st = state ?: return
        if (optimizeInFlight()) { _ui.update { it.copy(message = "計算中は編集できません（完了後にもう一度お試しください）") }; return }
        val sched = currentSchedule ?: return
        if (s.ops.isEmpty()) return
        for (op in s.ops) {
            if (op.staff !in sched.indices || op.day !in sched[op.staff].indices || op.toShift < 0) return
        }
        pushUndo()
        for (op in s.ops) sched[op.staff][op.day] = op.toShift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.update { it.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            fixSuggestions = emptyList(),   // 適用後は候補をクリア（盤面が変わるため再探索を促す）
            message = "改善手を適用: ${s.label}",
        ) }
        refreshCheck()
    }

    private fun applyStructure(r: Ws1Result) {
        if (structuralEditBlocked()) return
        pushUndo()
        state = r.state
        // [review 4b] Ws1Result の schedule を防御コピーして取り込む。Undo は pushUndo() の
        // 事前クローンで保護されるが、currentSchedule を以降 in-place 編集する経路があるため、
        // 全 schedule 取り込み口を copy2D() で統一して別名共有を断つ。
        currentSchedule = r.schedule.copy2D()
        _ui.update { it.copy(structureEdited = true, editRev = it.editRev + 1) }
        refreshCheck()
        autoSave()
    }

    /** Ws1Result(状態+勤務表)を適用し、再チェック後に独自メッセージを表示（スタッフ新規追加など行数変化を伴う取込）。 */
    private fun applyStructureWithMessage(r: Ws1Result, doneMessage: String) {
        pushUndo()
        state = r.state
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        autoSave()
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.update { it.copy(running = true, structureEdited = true, message = "$doneMessage（違反チェック中…）") }
        checkJob = viewModelScope.launch {
            try {
                val rep = V6FinalPort.handleCheck(r.state, sched)
                if (seq != checkSeq) return@launch
                pushReport(r.state, rep.schedule, rep.report) { it.copy(running = optimizeInFlight(), message = "$doneMessage｜必須=${rep.report.hard} 合計=${rep.report.total}") }
            } catch (e: CancellationException) {
                // [3.284.0/外部レビューHigh③] stop() によるキャンセル時の running 固着を解消（refreshCheck と同型）。
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "$doneMessage（チェックを停止）") }
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.update { it.copy(running = optimizeInFlight(), message = "$doneMessage（チェック失敗: ${e.message}）") }
            }
        }
    }

    fun ws1EditShift(k: Int, name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editShift(st, k, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    /** [必要人数カレンダー] シフト既定のneed1/need2だけをその場で編集する（name/kigouは不変）。
     *  ws1EditShiftの狭い版＝NeedCalendarCardの「基本の必要人数」インライン編集用。 */
    fun setShiftNeed(k: Int, need1: String, need2: String) {
        val st = state ?: return
        val sh = st.shifts.getOrNull(k) ?: return
        applyStructure(Ws1Ops.editShift(st, k, sh.name, sh.kigou, need1.trim(), need2.trim()))
    }

    fun ws1EditGroup(g: Int, name: String, kigou: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editGroup(st, g, name.trim(), kigou.trim()))
    }

    fun ws1EditStaff(i: Int, name: String, groupIdx: Int) {
        val st = state ?: return
        applyStructure(Ws1Ops.editStaff(st, i, name.trim(), groupIdx))
    }

    fun ws1SetGroupShift(g: Int, k: Int, allowed: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setGroupShift(st, g, k, allowed))
    }

    /** グループ別シフトの適切回数（1人あたり期間内目標。空欄＝目標なし）を設定。 */
    fun ws1SetGroupApt(g: Int, k: Int, value: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.setGroupApt(st, g, k, value))
    }

    /**
     * [apt強制リセット] 適切回数(apt)を全グループ×全シフトで空欄(目標なし)に戻す。
     * apt由来のソフト違反が消える。担当ON/OFF・回数レンジ・勤務表は不変、表も保持。元に戻すで復帰可。
     */
    fun ws1ResetGroupApt() {
        val st = state ?: return
        val cleared = st.groupShiftApt.sumOf { row -> row.count { it.trim().isNotEmpty() } }
        logOp("I", "apt強制リセット: 適切回数を全空欄に（$cleared 件クリア）")
        applyStructureWithMessage(Ws1Ops.resetGroupApt(st), "適切回数(apt)を全リセットしました（$cleared 件 → 0）")
    }

    fun ws1SetUse2(on: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setUse2(st, on))
    }

    fun ws1AddShift(name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addShift(st, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    fun ws1AddGroup(name: String, kigou: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addGroup(st, name.trim(), kigou.trim()))
    }

    fun ws1AddStaff(name: String, groupIdx: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.addStaff(st, sched, name.trim(), groupIdx))
    }

    fun ws1ResizeDays(newT: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.resizeDays(st, sched, newT))
    }

    /** [対象月の選択] 開始日を指定年月の1日にし、その月の日数へ整える（endDate/希望/必要人数も追従）。 */
    fun setMonth(year: Int, month1to12: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val first = runCatching { java.time.LocalDate.of(year, month1to12, 1) }.getOrNull() ?: return
        logOp("I", "期間変更: ${year}年${month1to12}月"); applyStructure(Ws1Ops.resizeDays(st.copy(startDate = first.toString()), sched, first.lengthOfMonth()))
    }

    /** 現在の開始日から相対的に月を移動（-1=前月 / +1=翌月）。開始日が不明なら端末の今月を起点。 */
    fun shiftMonth(delta: Int) {
        val base = runCatching { java.time.LocalDate.parse(_ui.value.startDate) }.getOrNull()
            ?: java.time.LocalDate.now().withDayOfMonth(1)
        val m = base.withDayOfMonth(1).plusMonths(delta.toLong())
        setMonth(m.year, m.monthValue)
    }

    /** 端末の今月へ設定。 */
    fun setThisMonth() {
        val now = java.time.LocalDate.now()
        setMonth(now.year, now.monthValue)
    }
    /** [実機指摘] 月末に「来月」の勤務表を作る業務のため、ワンタップは来月が適切。 */
    fun setNextMonth() {
        val next = java.time.LocalDate.now().plusMonths(1)
        setMonth(next.year, next.monthValue)
    }

    // ---- スキルグループ（年次マスター・新C41s/C42s 専用） -----------------------
    fun skillGroups(): List<Group> = state?.skillGroups ?: emptyList()
    fun addSkillGroup(name: String, kigou: String) {
        val st = state ?: return; if (kigou.isBlank()) return
        logOp("I", "スキル区分追加: ${name.trim()}(${kigou.trim()})"); applyStructure(st.copy(skillGroups = st.skillGroups + Group(name.trim(), kigou.trim())))
    }
    fun editSkillGroup(g: Int, name: String, kigou: String) {
        val st = state ?: return
        val old = st.skillGroups.getOrNull(g)?.kigou ?: ""
        val renamed = st.copy(skillGroups = st.skillGroups.mapIndexed { i, x -> if (i == g) Group(name.trim(), kigou.trim()) else x })
        logOp("I", "スキル区分編集: [$g] → ${name.trim()}(${kigou.trim()})")
        // [記号変更の伝播] スキル群記号を変えたら cons41s/cons42s の参照も一括置換(幽霊行防止)
        applyStructure(Ws1Ops.renameSkillGroupInConstraints(renamed, old, kigou.trim()))
    }
    fun removeSkillGroup(g: Int) {
        val st = state ?: return
        // [3.330.0] 移行規則は Ws1Ops.removeSkillGroup（担当グループの removeGroup と対）。
        //   ここに手書きしていた間はホストでテストできなかった。
        logOp("I", "スキル区分削除: [$g]"); applyStructure(Ws1Ops.removeSkillGroup(st, g))
    }
    fun setStaffSkill(i: Int, skillIdx: Int) {
        val st = state ?: return
        logOp("I", "スキル割当: ${opNm(i)} → 区分[$skillIdx]"); applyStructure(st.copy(staff = st.staff.mapIndexed { idx, s -> if (idx == i) s.copy(skillIdx = skillIdx) else s }))
    }

    /** グループを削除できるか（2グループ以上あれば可。所属者がいても先頭グループへ移動して削除）。 */
    fun ws1CanRemoveGroup(g: Int): Boolean = state?.let { g in it.groups.indices && it.groups.size > 1 } ?: false

    /** グループgの所属人数（削除確認の警告表示用）。 */
    fun ws1GroupMemberCount(g: Int): Int = state?.staff?.count { it.groupIdx == g } ?: 0

    /** [窓ハイライト③] セル(i,j)の違反が c1/c3/c3m のとき、その違反が指す窓/連の範囲(開始日..終了日)を返す。
     *  c1=最初に不足している窓 / c3・c3m=複数シフト窓なら未完成パターンの窓、単一シフト連なら連の実範囲。
     *  該当なし・他族は null（読み取り専用・表示のみ）。 */
    fun violationRange(i: Int, j: Int): Pair<Int, Int>? {
        val st = state ?: return null
        val sched = currentSchedule ?: return null
        val cls = _ui.value.violationCells["$i,$j"] ?: return null
        val p = com.magi.app.v6.cachedProblem(st)
        if (i !in 0 until p.S || j !in 0 until p.T) return null
        when (cls) {
            "vio-c1" -> for (c in p.cons1) {
                if (!p.canDo(i, c.shiftIdx) || j + c.day1 > p.T) continue
                var z = 0
                for (l in 0 until c.day1) if (sched[i][j + l] == c.shiftIdx) z++
                if (z < c.day2) return j to (j + c.day1 - 1)
            }
            "vio-c3", "vio-c3m" -> {
                val k0 = sched[i][j]
                val lists = if (cls == "vio-c3") p.cons3 else p.cons3m
                for (c in lists) {
                    val seq = c.seq
                    if (seq.size < 2 || seq[0] != k0 || j + seq.size > p.T) continue
                    var ok = true
                    for (l in 1 until seq.size) if (sched[i][j + l] != seq[l]) { ok = false; break }
                    if (!ok) return j to (j + seq.size - 1)   // 未完成パターン=この窓が違反
                }
                var end = j
                while (end + 1 < p.T && sched[i][end + 1] == k0) end++   // 単一シフト連の実範囲
                if (end > j) return j to end
            }
        }
        return null
    }

    fun ws1RemoveShift(k: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        // [P1修正/レビュー指摘] 休シフトの削除は Ws1Ops 側で no-op（全休日が勤務に化けるため禁止）。理由を提示する。
        if (k == com.magi.app.v6.restShiftIndex(st)) {
            _ui.update { it.copy(message = "「休」シフトは削除できません（全ての休日が別のシフトに変わってしまうため）") }
            return
        }
        applyStructure(Ws1Ops.removeShift(st, sched, k))
    }

    fun ws1RemoveStaff(i: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.removeStaff(st, sched, i))
    }

    fun ws1RemoveGroup(g: Int) {
        val st = state ?: return
        if (g !in st.groups.indices || st.groups.size <= 1) return
        applyStructure(Ws1Ops.removeGroup(st, g))
    }

    /** Current JSON to export. ws1 edits -> full serialize; constraint edits -> overwrite cons; else schedule only. */
    fun exportJson(): String? {
        val sched = currentSchedule ?: resultSchedule ?: return null
        val st = state
        if (_ui.value.structureEdited && st != null) return StateParser.serialize(st, sched)
        val orig = originalJson ?: return null
        return if (_ui.value.constraintsEdited && st != null) StateParser.exportWithEdits(orig, st, sched)
        else StateParser.exportWithSchedule(orig, sched)
    }

    fun exportCsv(): String? {
        val st = state ?: return null
        val sched = currentSchedule ?: return null
        return ScheduleCsvBridge.build(st, sched)
    }

    /** コンポーネント別エクスポート（取込種別と対。出力→編集→取込で往復可）。 */
    fun exportStaffCsv(): String? = state?.let { com.magi.app.v6.StaffCsvIO.build(it) }
    fun exportWishesCsv(): String? = state?.let { com.magi.app.v6.WishesCsvIO.build(it) }
    fun exportConstraintsCsv(): String? = state?.let { com.magi.app.v6.ConstraintsCsvIO.build(it) }

    /** Operator log as a plain-text file (mirrors the Web "ログ出力"). */
    fun exportLogs(): String? {
        val ops = _ui.value.opLog
        // 出力は全文（非圧縮）。画面表示は圧縮版だが、監査用にはロスレスの rawDiagLogs を使う。
        val logs = rawDiagLogs.ifEmpty { _ui.value.logs }
        if (ops.isEmpty() && logs.isEmpty()) return null
        val ts = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
        return buildString {
            append("MAGI ログ (Native)  出力: ").append(ts).append('\n')
            append("状態: ${_ui.value.staff}名/${_ui.value.days}日 ・ 必須=${_ui.value.bestHard} 合計=${_ui.value.totalViolations}\n")
            append("\n==== 操作ログ（新しい順 ${ops.size}件）====\n")
            ops.forEach { append(it).append('\n') }
            append("\n==== 診断ログ（全文 ${logs.size}件）====\n")
            logs.forEach { append(it).append('\n') }
        }
    }

    /** 操作ログ・診断ログ・現在の違反サマリを構造化JSONで書き出す（監査用）。 */
    fun exportLogsJson(): String? {
        if (_ui.value.opLog.isEmpty() && _ui.value.logs.isEmpty()) return null
        val o = org.json.JSONObject()
        o.put("exportedAt", System.currentTimeMillis())
        o.put("staff", _ui.value.staff); o.put("days", _ui.value.days); o.put("shifts", _ui.value.shifts)
        o.put("hard", _ui.value.bestHard); o.put("soft", _ui.value.bestSoft); o.put("total", _ui.value.totalViolations)
        o.put("satisfaction", _ui.value.satisfaction)
        // [N6] satisfaction は 0-100 の進捗スコア（違反減少度: hard>0 で×55系）であり希望充足率ではない。
        //   外部AI/人間の誤読が実際に発生したため意味を同梱する。
        o.put("satisfactionMeaning", "0-100の進捗スコア（必須・合計違反の減少度）。希望充足率ではありません")
        o.put("opLog", org.json.JSONArray().apply { _ui.value.opLog.forEach { put(it) } })
        o.put("diagLog", org.json.JSONArray().apply { rawDiagLogs.ifEmpty { _ui.value.logs }.forEach { put(it) } })
        o.put("breakdown", org.json.JSONObject().apply { _ui.value.breakdown.forEach { (k, v) -> put(k, v) } })
        return o.toString(2)
    }

    /**
     * CSV取込の振り分け。病院などの「勤務表テンプレCSV」(ユニット/スタッフ/凡例を含む完全な1ヶ月表) は
     * 新規データセットとして丸ごと取り込む（[RosterCsvImport]）。それ以外は、既存データへ勤務表だけを
     * 重ねる従来の取込([importCsv])に回す（既存データが無ければ案内のみ）。
     */
    fun importCsvSmart(rawText: String) {
        val text = MojibakeRepair.repair(rawText)
        if (com.magi.app.v6.RosterCsvImport.detect(text)) {
            val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text) }.getOrNull()
            if (st != null) {
                // 凡例(記号一覧)が無いとシフトが「休」1種のみになり全セルが公休化する。
                // 取り込まず原因をオペレーターに表示する（Excel保存で凡例が消えるケース）。
                if (st.shiftCount <= 1) {
                    _ui.update { it.copy(message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで開いて保存すると消える場合があります）。元のファイルをそのまま取り込んでください。") }
                    logOp("W", "勤務表CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ→全公休化を防止）")
                    return
                }
                logOp("I", "勤務表CSVを新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット")
                load(StateParser.serialize(st, st.schedule.toIntArray2D()))
                return
            }
            // テンプレらしいが解析不能 → 既存取込にフォールバック（または案内）。
        }
        // ユニット列形式（凡例なし: ユニット,No,役職,氏名,1,2,…）の勤務表CSV → 新規データセットとして取込。
        if (com.magi.app.v6.FlatRosterCsvImport.detect(text)) {
            val st = runCatching { com.magi.app.v6.FlatRosterCsvImport.parse(text) }.getOrNull()
            if (st != null) {
                logOp("I", "勤務表CSV(ユニット列形式)を新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット")
                load(StateParser.serialize(st, st.schedule.toIntArray2D()))
                return
            }
            _ui.update { it.copy(message = "CSV取込失敗: ユニット列形式と判定しましたが解析できませんでした。ヘッダ行（ユニット, No, 役職, 氏名, 1, 2, …）と氏名列をご確認ください。") }
            logOp("W", "勤務表CSV(ユニット列形式)取込 失敗: 解析不能")
            return
        }
        if (state == null) {
            _ui.update { it.copy(message = "このCSVを読み込めませんでした。先に『データを開く』で基本データを読み込むか、勤務表テンプレCSVをご利用ください。") }
            return
        }
        // [3.282.0] 修復済みテキストをそのまま渡す（旧: rawText を渡し importCsv 内で二重に repair＝
        //   結果は同一だが無駄な再修復と非対称があった）。
        importCsv(text)
    }

    /**
     * 勤務表テンプレCSVを、利用者の選択（勤務表 or 希望シフト）で新規データとして取り込む。
     *  - asWishes=false: 本表を初期割り当て(勤務表)として読み込む。
     *  - asWishes=true : 本表をスタッフの希望として読み込み、勤務表は空(全公休)で開始（最適化で尊重）。
     */
    fun importRosterAs(rawText: String, asWishes: Boolean) {
        val text = MojibakeRepair.repair(rawText)
        val st = runCatching { com.magi.app.v6.RosterCsvImport.parse(text, asWishes) }.getOrNull()
        if (st == null) {
            _ui.update { it.copy(message = "このCSVを読み込めませんでした。形式をご確認ください。") }
            return
        }
        if (st.shiftCount <= 1) {
            _ui.update { it.copy(message = "CSV取込失敗: シフト記号（凡例）が見つかりません。テンプレCSV末尾の『記号 / 時刻 …』一覧が削除されていないかご確認ください（Excelで保存すると消える場合があります）。") }
            logOp("W", "${if (asWishes) "希望シフト" else "勤務表"}CSV取込 中止: 凡例なし（シフト${st.shiftCount}種のみ）")
            return
        }
        val kind = if (asWishes) "希望シフト" else "勤務表"
        logOp("I", "${kind}として新規取込: ${st.staffCount}名 / ${st.dayCount}日 / ${st.shiftCount}シフト / ${st.groupCount}ユニット" +
            if (asWishes) "（希望${st.wishes.size}件）" else "")
        load(StateParser.serialize(st, st.schedule.toIntArray2D()))
    }

    fun importCsv(rawText: String) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val text = MojibakeRepair.repair(rawText)
        _ui.update { it.copy(running = true, message = "CSV取込中…") }
        job = viewModelScope.launch {
            try {
                // [3.282.0] JSON 側(loadAsync)と同じ是正: BOM 除去だけの健全な CSV で誤警告しない。
                if (MojibakeRepair.wasDecoded(rawText, text)) logOp("W", "文字化け（二重エンコード）を自動修復してCSVを取り込みました。元のファイル自体は修復されません")
                val res = withContext(Dispatchers.Default) { ScheduleCsvBridge.parse(text, st, sched) }
                // 取込失敗の明示: 氏名が1件も一致しなければ適用せず、オペレーターに原因を表示する。
                if (res.matched == 0) {
                    _ui.update { it.copy(
                        running = false,
                        message = "CSV取込失敗: 一致する職員名がありませんでした（0名）。CSVの1列目の氏名が現在のデータと一致しているか、列レイアウト（氏名, 1日目, 2日目, …）をご確認ください。",
                    ) }
                    logOp("W", "CSV取込 失敗: 職員名が0件一致のため取込を中止しました（氏名/列レイアウトを確認）")
                    return@launch
                }
                pushUndo()
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val total = st.staff.size
                val msg = if (res.matched in 1 until total)
                    "CSV取込完了: ${res.matched}/${total}名を更新（${total - res.matched}名は氏名不一致でスキップ）｜必須=${res.report.hard} 合計=${res.report.total}"
                else
                    "CSV取込完了: ${res.matched}名を更新｜必須=${res.report.hard} 合計=${res.report.total}"
                pushReport(state ?: st, res.schedule, res.report) { it.copy(
                    running = false,
                    hasResult = true,
                    message = msg,
                ) }
                if (res.matched in 1 until total) {
                    logOp("W", "CSV取込 一部のみ反映: ${res.matched}/${total}名一致（${total - res.matched}名は氏名不一致）")
                }
                logOp("I", "CSV取込 完了 ${res.matched}名一致 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, message = "CSV取込失敗: ${e.message}") }
            }
        }
    }

    /** 取込種別を取り違えた可能性の判定: 勤務表(スケジュール)CSVらしいか。 */
    private fun looksLikeScheduleCsv(t: String): Boolean {
        val lines = t.split('\n')
        if (lines.isEmpty()) return false
        // ScheduleCsvBridge.build のヘッダ「スタッフ \ 日付,…」、または集計ブロック「集計,…」。
        val head = lines[0].trim()
        if (head.startsWith("スタッフ") && head.contains("日付")) return true
        return lines.any { it.trimStart().startsWith("集計,") }
    }

    /** 希望/制約の取込が0件のとき、別形式CSVの取り違えを推定して利用者向けヒントを返す（無ければ空）。 */
    private fun componentImportMismatchHint(repairedText: String): String = when {
        com.magi.app.v6.RosterCsvImport.detect(repairedText) ||
            com.magi.app.v6.FlatRosterCsvImport.detect(repairedText) ->
            "これは勤務表全体（テンプレ/ユニット列形式）のCSVのようです。取込種別で『データ全体（新規）』を選んでください。"
        looksLikeScheduleCsv(repairedText) ->
            "これは勤務表（スケジュール）CSVのようで、希望・制約は含まれていません。専用CSVを、出力タブの『希望』『制約』ボタンで出して取り込んでください。"
        else -> ""
    }

    /** [コンポーネント別取込] スタッフ一覧CSV（氏名,グループ,スキル）。既存は所属群/スキルを更新、未知の氏名は新規追加（勤務表に休の行を追加）。 */
    fun importStaffCsv(rawText: String) {
        val st = state ?: run { _ui.update { it.copy(message = "先にデータを開いてください（職員一覧は既存データに追加/更新します）") }; return }
        val sched = currentSchedule ?: run { _ui.update { it.copy(message = "先にデータを開いてください（職員一覧は既存データに追加/更新します）") }; return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.StaffCsvIO.parseUpsert(text, st, sched) }.getOrNull()
        if (res == null) {
            val hint = componentImportMismatchHint(text)
            val tail = if (hint.isEmpty()) "形式『氏名,グループ,スキル』（1行=1名）をご確認ください。" else hint
            _ui.update { it.copy(message = "職員一覧の取込失敗（追加0・更新0）。$tail") }
            logOp("W", "職員一覧CSV取込 失敗: 0件")
            return
        }
        val parts = buildList {
            if (res.added > 0) add("${res.added}名を新規追加")
            if (res.updated > 0) add("${res.updated}名を更新")
        }
        val msg = "職員一覧を取込: " + parts.joinToString("・")
        logOp("I", "職員一覧CSV取込: 追加${res.added} 更新${res.updated}")
        applyStructureWithMessage(com.magi.app.v6.Ws1Result(res.state, res.schedule), msg)
    }

    /** [コンポーネント別取込] 希望シフトCSV（氏名,日,希望シフト）。氏名一致で希望を全置換。 */
    fun importWishesCsv(rawText: String) {
        val st = state ?: run { _ui.update { it.copy(message = "先にデータを開いてください（希望シフトは既存データに重ねます）") }; return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.WishesCsvIO.parse(text, st) }.getOrNull()
        if (res == null) {
            val hint = componentImportMismatchHint(text)
            val tail = if (hint.isEmpty()) "形式は『氏名,日,希望シフト』（例: 古泉 健一,5,休）です。氏名・シフト記号が一致しているかご確認ください。" else hint
            _ui.update { it.copy(message = "希望シフトの取込失敗（取り込める行が0件）。$tail") }
            logOp("W", "希望シフトCSV取込 失敗: 0件${if (hint.isEmpty()) "" else "（別形式CSVの取り違えの可能性）"}")
            return
        }
        // [3.329.0/外部レビュー H-02] この取込は既存の希望を**全置換**する。中身のある行を1つでも
        //   解釈できなかったら置換しない（旧: 誤記の行を黙って捨て、1行でも有効なら残りの希望を消していた）。
        if (res.rejected > 0) {
            _ui.update { it.copy(message = "希望シフトの取込を中止しました（読めない行が${res.rejected}件）。" +
                "この取込は既存の希望を置き換えるため、全部読めたときだけ実行します。例: ${res.sample}") }
            logOp("W", "希望シフトCSV取込 中止: 読めない行${res.rejected}件（取込可${res.accepted}件）例: ${res.sample}")
            return
        }
        logOp("I", "希望シフトCSV取込: ${res.accepted}件を反映（全置換）")
        applyStructureWithMessage(res.state, "希望シフトを取込: ${res.accepted}件を反映（既存の希望は置換）")
    }

    /** [コンポーネント別取込] 各制約CSV（種別タグ付き）。制約一式＋個人レンジを置換。 */
    fun importConstraintsCsv(rawText: String) {
        val st = state ?: run { _ui.update { it.copy(message = "先にデータを開いてください（各制約は既存データに重ねます）") }; return }
        val text = MojibakeRepair.repair(rawText)
        val res = runCatching { com.magi.app.v6.ConstraintsCsvIO.parse(text, st) }.getOrNull()
        if (res == null) {
            val hint = componentImportMismatchHint(text)
            val tail = if (hint.isEmpty()) "1列目の種別（連勤/禁止連続/群組合せ禁止/個人レンジ 等）をご確認ください。例: 連勤,5,休,14 ／ 個人レンジ,古泉 健一,A4,6,8" else hint
            _ui.update { it.copy(message = "各制約の取込失敗（取り込める行が0件）。$tail") }
            logOp("W", "各制約CSV取込 失敗: 0件${if (hint.isEmpty()) "" else "（別形式CSVの取り違えの可能性）"}")
            return
        }
        // [3.329.0/外部レビュー H-02] 制約一式と個人レンジを**全置換**するので、希望と同じ扱いにする。
        if (res.rejected > 0) {
            _ui.update { it.copy(message = "各制約の取込を中止しました（読めない行が${res.rejected}件）。" +
                "この取込は既存の制約・個人レンジを置き換えるため、全部読めたときだけ実行します。例: ${res.sample}") }
            logOp("W", "各制約CSV取込 中止: 読めない行${res.rejected}件（取込可${res.accepted}件）例: ${res.sample}")
            return
        }
        logOp("I", "各制約CSV取込: ${res.accepted}件を反映（制約一式を置換）")
        applyStructureWithMessage(res.state, "各制約を取込: ${res.accepted}件を反映（既存の制約・個人レンジは置換）")
    }

    fun clearMessage() { _ui.update { it.copy(message = null) } }

    /**
     * 診断ログのスパム抑制。RSI/ALNS の各ラウンド・各リスタート・EarlyChain などで同種の行が大量に
     * 出るため、(1) 連続する重複行を「×N」に畳み、(2) それでも上限を超える場合は頭7割＋尾3割に圧縮する。
     * 全文が必要な場合は「ログ出力（テキスト/JSON）」で取得する想定。
     */
    private fun compressDiagLogs(lines: List<String>, cap: Int = 200): List<String> {
        if (lines.size <= 1) return lines
        val collapsed = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            var j = i + 1
            while (j < lines.size && lines[j] == lines[i]) j++
            val n = j - i
            collapsed.add(if (n > 1) "${lines[i]}  ×$n" else lines[i])
            i = j
        }
        if (collapsed.size <= cap) return collapsed
        val head = cap * 7 / 10
        val tail = cap - head
        val out = ArrayList<String>(cap + 1)
        out.addAll(collapsed.subList(0, head))
        out.add("… 中略 ${collapsed.size - head - tail} 行省略（全文は「ログ出力」で取得） …")
        out.addAll(collapsed.subList(collapsed.size - tail, collapsed.size))
        return out
    }

    /**
     * makeUi の重い解析4パスの出力を束ねる不変ホルダ。純関数の出力のみを保持するため、
     * どのスレッドで生成しても安全（背景スレッドで作りメインへ受け渡せる）。
     */
    private data class Analysis(
        val v6: V6PortReport,
        val sanity: V6SanityReport,
        val coverageDiag: CoverageDiagnosis?,
        val forbiddenDiag: ForbiddenRunDiagnosis?,
        val v6Logs: List<String>,
        val rawDiagLogs: List<String>,
    )

    /**
     * makeUi が必要とする4つの重い解析を Dispatchers.Default 上で「並列」に実行する。
     * 4パス（analyze / build / diagnoseCoverage / buildViolationDebug）は同じ不変入力にのみ依存し
     * 相互参照しない純関数なので、別コアで同時実行でき、壁時計時間が sum(パス) → max(パス) に短縮される
     * （最重量は全制約走査の buildViolationDebug）。coroutineScope により、いずれかの失敗・呼び出し元の
     * キャンセルは兄弟 async へ確実に伝播する（構造化並行性）。
     */
    private suspend fun analyzeParallel(
        st: MagiState,
        schedule: Array<IntArray>,
        report: ViolationReport,
    ): Analysis = coroutineScope {
        val v6D       = async(Dispatchers.Default) { V6PortAnalyzer.analyze(st, schedule, report) }
        val sanityD   = async(Dispatchers.Default) { V6SanityPort.build(st, schedule) }
        // 人員不足(covU)または人員過剰(covO)が残る場合のみ原因診断（どの日/シフトが「充足不可」か
        // 「未到達」か／過剰がなぜ動かせないか）を算出しログに残す。
        val coverageD = async(Dispatchers.Default) {
            V6PortAnalyzer.diagnoseCoverage(st, schedule, report).takeIf { it.hasShortage || it.hasSurplus }
        }
        // [3.280.0] 禁止連続(c3n)が残る場合のみ「なぜ崩せないか」診断（CoverageDiag の c3n 版）。
        val forbiddenD = async(Dispatchers.Default) {
            if ((report.breakdown["c3n"] ?: 0) > 0)
                V6PortAnalyzer.diagnoseForbiddenRuns(st, schedule).takeIf { it.hasRuns }
            else null
        }
        // [デバッグ] 制約違反を家族ごとに「場所＋実値(必要/現状, 回数/下限上限, 誰/何日/シフト)」で出力。
        val vioDebugD = async(Dispatchers.Default) { V6SanityPort.buildViolationDebug(st, schedule, report) }

        // v6Logs は sanity/coverageDiag/forbiddenDiag に依存 → 依存先だけ先に await（依存グラフを尊重）
        val sanity = sanityD.await()
        val coverageDiag = coverageD.await()
        val forbiddenDiag = forbiddenD.await()
        val v6Logs = listOf("[I] LoadDataBit: ${sanity.loadDataBitSummary}") + sanity.warns.map { "[W] SanityCheck: $it" } + sanity.notes.map { "[I] V6Port: $it" } + sanity.duplicateSeqConstraints.take(4).map { "[W] DuplicateSeq: $it" } + sanity.guidance.take(12).map { "[W] 設定ミス: ${it.where} — ${it.problem} → ${it.fix}" } + (coverageDiag?.logLines() ?: emptyList()) + (forbiddenDiag?.logLines() ?: emptyList())
        val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
        Analysis(
            v6 = v6D.await(),
            sanity = sanity,
            coverageDiag = coverageDiag,
            forbiddenDiag = forbiddenDiag,
            v6Logs = v6Logs,
            rawDiagLogs = v6Logs + mappedDiag + vioDebugD.await(),   // 出力用の全文（非圧縮）。表示は圧縮版を使う。
        )
    }

    /**
     * 重い解析(analyzeParallel)を Default で並列実行し、その結果を StateFlow へ反映する共通経路。
     * 共有可変(rawDiagLogs)の書き込みと _ui.update はメインスレッドで行い、背景スレッドからは書かない
     * （＝レース不能・単一ライタ）。全 makeUi 呼び出しをこの1経路へ集約する。
     * @param nonCancellable 停止(keep-best)経路から呼ぶ場合 true＝スコープキャンセル後も解析を完了させる。
     */
    private suspend fun pushReport(
        st: MagiState,
        schedule: Array<IntArray>,
        report: ViolationReport,
        nonCancellable: Boolean = false,
        transform: (UiState) -> UiState = { it },
    ) {
        val analysis =
            if (nonCancellable) withContext(NonCancellable) { analyzeParallel(st, schedule, report) }
            else analyzeParallel(st, schedule, report)
        rawDiagLogs = analysis.rawDiagLogs
        _ui.update { base -> makeUi(st, schedule, report, analysis, transform(base)) }
    }

    private fun makeUi(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, analysis: Analysis, base: UiState): UiState {
        val groupSymbols = st.staff.map { staff -> st.groups.getOrNull(staff.groupIdx)?.kigou ?: "" }
        val v6 = analysis.v6
        val sanity = analysis.sanity
        val coverageDiag = analysis.coverageDiag
        val v6Logs = analysis.v6Logs
        // [3.324.0] 研磨診断は観測した盤面のものか（盤面が変わっていれば出さない）。
        // [3.327.0] 盤面**と**制約の両方が観測時と一致するときだけ診断を出す。
        val diagFresh = lastDiagBoardKey != 0L && lastDiagBoardKey == boardKey(schedule) &&
            lastDiagStateKey == stateKey(st)
        val mappedDiag = report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" }
        // rawDiagLogs は pushReport がメインスレッドで設定済み（背景スレッドからは書かない＝レース回避）。
        // 満足度(0-100): 初期からの違反削減率。HARD未解決の間は上限を抑える。
        val initTotal = (base.initHard + base.initSoft).coerceAtLeast(1L)
        val ratio = (1.0 - report.total.toDouble() / initTotal).coerceIn(0.0, 1.0)
        val sat = if (report.hard > 0) (ratio * 55).toInt() else (40 + (ratio * 60).toInt()).coerceIn(0, 100)
        // [backlog#1] この検査対象が結果(ws6)そのものか（＝report が結果専用マップの最新値か）。
        val resultFresh = resultSchedule != null && schedule.contentDeepEquals(resultSchedule)
        return base.copy(
            staff = st.staffCount,
            days = st.dayCount,
            shifts = st.shiftCount,
            groups = st.groupCount,
            use2 = st.use2Patterns,
            bestHard = report.hard.toLong(),
            bestSoft = report.soft.toLong(),
            totalViolations = report.total,
            weightedScore = report.weightedScore,
            breakdown = emptyBreakdown + report.breakdown,
            violationCells = report.violations,
            needViolations = report.needViolations,
            countViolations = report.countViolations,
            violationCellFamilies = report.cellFamilies,
            distLocations = report.distLocations,
            // [backlog#1] 検査対象が結果(ws6)そのものなら、この report が結果専用マップの最新値。
            //   最適化完了/他案適用/結果→編集複製後の refreshCheck 等、resultSchedule 更新サイトは全て
            //   makeUi(schedule==resultSchedule, 対応report) を通るためここで一元的に充填できる。
            //   編集で schedule が結果から離れた場合は既存値を保持（resultSchedule 不変＝マップも有効なまま）。
            resultViolationCells = when { resultSchedule == null -> null; resultFresh -> report.violations; else -> base.resultViolationCells },
            resultNeedViolations = when { resultSchedule == null -> null; resultFresh -> report.needViolations; else -> base.resultNeedViolations },
            resultCountViolations = when { resultSchedule == null -> null; resultFresh -> report.countViolations; else -> base.resultCountViolations },
            resultViolationCellFamilies = when { resultSchedule == null -> null; resultFresh -> report.cellFamilies; else -> base.resultViolationCellFamilies },
            logs = v6Logs + compressDiagLogs(mappedDiag),
            staffNames = st.staff.map { it.name },
            staffGroupSymbols = groupSymbols.map { toHankakuKigou(it) },
            shiftSymbols = st.shifts.map { toHankakuKigou(it.kigou) },
            shiftColorHex = st.shifts.mapIndexed { i, sh -> V6WebCompat.resolveShiftColor(sh.kigou, sh.name, st.shiftColors[sh.kigou], i) },
            shiftTextHex = st.shifts.mapIndexed { i, sh -> V6WebCompat.pickTextColor(V6WebCompat.resolveShiftColor(sh.kigou, sh.name, st.shiftColors[sh.kigou], i)) },
            violationColorHex = st.shiftColors["__vio__"] ?: "",
            violationSoftColorHex = st.shiftColors["__vioSoft__"] ?: "",
            violationFamilyColorHex = st.shiftColors.entries
                .filter { it.key.startsWith("__vioFam_") && it.key.endsWith("__") }
                .associate { it.key.removePrefix("__vioFam_").removeSuffix("__") to it.value },
            schedule = schedule.map { it.toList() },
            wishes = st.wishes,
            resultSchedule = resultSchedule?.map { it.toList() } ?: emptyList(),   // [B1] 確定結果(ws6)
            hasResultSnapshot = resultSchedule != null,                            // [B1]
            v6 = v6,
            satisfaction = sat,
            // 研磨の限界: 必須は解決済みだが微調整が残る → 手修正の検討を促す
            polishExhausted = report.hard == 0 && report.total > 0,
            // 解決したらガチャ助言は消す
            copilotHint = if (report.hard == 0) null else base.copilotHint,
            // 担当外など実現不能な希望（Web版の担当外希望警告に相当）
            impossibleWishCount = sanity.impossibleWishes.size,
            // 人員不足(covU)の原因診断（充足不可/充足可能の切り分け）。不足が無ければ null。
            coverageDiag = coverageDiag,
            // [3.280.0] 禁止連続(c3n)の「なぜ崩せないか」診断。c3n=0 なら null。
            forbiddenDiag = analysis.forbiddenDiag,
            // [3.322.0] c1 頭打ちの構造化診断。再計算できない（研磨中の却下記録が唯一の根拠）ので
            //   ViewModel が保持し、いま c1 が残っているときだけ見せる（解消済みなら黙る）。
            // [3.324.0/外部レビュー] 診断は観測した盤面のものだけ出す。盤面が変わっていれば黙る
            //   （手編集・元に戻す・読込・初期解生成など、あらゆる変更で自動的に外れる）。
            c1Plateau = if (diagFresh) lastC1Plateau?.takeIf { (report.breakdown["c1"] ?: 0) > 0 } else null,
            observedPinBlockedAttempts = if (diagFresh) lastObservedPinAttempts else 0,
            // [3.326.0] 緩和の対象候補。どのピンが何回止めたかを名前つきで渡す（多い順）。
            pinTargets = if (!diagFresh) emptyList() else {
                val pr = cachedProblem(st)
                lastPinBlocks?.byTarget()?.mapNotNull { (i, k, n) ->
                    val lo = pr.rangeLo.getOrNull(i)?.getOrNull(k) ?: return@mapNotNull null
                    val hi = pr.rangeHi.getOrNull(i)?.getOrNull(k) ?: return@mapNotNull null
                    // [3.327.0/外部レビュー High2] **いま固定されているものだけ**出す。緩めたあと
                    //   (lo != hi) も「N回に固定」と表示し続けるのは事実に反する。
                    if (lo == Int.MIN_VALUE || hi == Int.MAX_VALUE || lo != hi) return@mapNotNull null
                    PinTargetView(
                        staff = i, shift = k,
                        staffName = st.staff.getOrNull(i)?.name ?: "#$i",
                        shiftKigou = st.shifts.getOrNull(k)?.kigou ?: "$k",
                        pinnedCount = lo, attempts = n,
                    )
                } ?: emptyList()
            },
            settingIssues = sanity.guidance,
            startDate = st.startDate,
        )
    }

    private data class LoadedProblem(
        val state: MagiState,
        val schedule: Array<IntArray>,
        val nativeHard: Long,
        val nativeSoft: Long,
        val report: ViolationReport,
    )
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m
