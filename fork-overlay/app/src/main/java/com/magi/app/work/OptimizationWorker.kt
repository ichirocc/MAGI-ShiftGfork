package com.magi.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.engine.AppVersion
import com.magi.app.v6.engine.OptimizeBenchLog
import com.magi.app.v6.engine.integration.RebuildOptimizeEntry
import android.util.Log
import com.magi.app.v6.copy2D
import com.magi.app.v6.toIntArray2D
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Background optimization (改善仕様書 §6.2). Runs the V6 engine off the UI process's main
 * thread, publishes live progress to [OptimizationRepository], persists the result there, and
 * posts a completion notification. Enqueued as expedited work (with non-expedited fallback).
 */
class OptimizationWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    /**
     * [3.327.0/外部レビュー High3] この実行が共有ファイル（入力・結果・途中最良）の所有者か。
     * `runId` は inputData に載るので WorkManager が永続化する＝kill 後の再実行でも同一。
     * - `mine == 0L`：runId を持たない旧経路 → 従来どおり所有者として扱う（非破壊）。
     * - 置き換え（REPLACE）で新しい実行が `beginRun` を書くと、旧実行はここで false になり
     *   **書き込みも削除も一切しなくなる**。停止（`clearFiles` で runId 消去）も同様。
     */
    private fun ownsFiles(): Boolean {
        val mine = inputData.getLong(KEY_RUN_ID, 0L)
        if (mine == 0L) return true
        return activeRunId(ctx) == mine
    }

    override suspend fun doWork(): Result {
        // 置き換え済み／停止済みの実行はここで降りる（共有ファイルへ触らない）。
        if (!ownsFiles()) return Result.success()
        // [C1] kill後にWorkManagerが再起動した場合、同一プロセス参照(request)は失われている。
        // [P2修正/レビュー指摘] 復元は「途中最良スナップショット」を優先（8秒毎に退避済み＝実質的な途中再開。
        //   無ければ元入力）。旧: 常に元入力から再スタートし、途中の改善を捨てていた。
        val req = OptimizationRepository.request ?: loadInputFromFile(ctx) ?: return Result.failure()
        ensureChannel()
        // [C1] 入力をファイルへ退避（現在は参照渡し）。kill後の再起動でここから復元できる。
        runCatching { inputFile(ctx).writeText(StateParser.serialize(req.first, req.second)) }
        OptimizationRepository.setRunning(true)
        runCatching {
            val v = AppVersion.info
            Log.i(OptimizeBenchLog.TAG, "MAGI_VERSION ${v.logLine()}")
            Log.i("MAGI", "アプリ版 ${v.compact()} / ${v.logLine()}")
        }
        // [P2修正/レビュー指摘] 予算秒数・並列数は WorkManager の inputData から復元する。
        //   旧: インメモリの OptimizationRepository のみで、プロセス再起動後は既定の 60秒/4並列 に
        //   化けていた（300秒/8並列で開始したジョブが別条件で再実行される）。inputData は WorkManager が
        //   永続化するため kill/再起動を跨いで開始時の条件が保たれる（0=未設定なら従来どおり Repository）。
        val budgetSec = inputData.getInt(KEY_SECONDS, 0).takeIf { it > 0 } ?: OptimizationRepository.seconds
        val bgWorkers = inputData.getInt(KEY_WORKERS, 0).takeIf { it > 0 } ?: OptimizationRepository.workers
        // [#4] 前景サービス化: 5分のCPUジョブをOSに止めさせない（FGS不可な環境では通常実行へフォールバック）。
        runCatching { setForeground(getForegroundInfo()) }
        // [Android 17 バブル] 会話バブルの前提（会話チャンネル＋長寿命ショートカット）を用意し、開始バブルを提示。
        runCatching {
            BubbleSupport.ensureChannel(ctx)
            BubbleSupport.pushShortcut(ctx)
            BubbleSupport.postProgress(ctx, "最適化を開始しました")
        }
        // [3.333.0/外部レビュー] 成功パスは所有権マーカー(runIdFile)を**自分で消してから** finally へ入る。
        //   finally が `ownsFiles()` をファイルから読み直すと「所有者でない」と判定され、
        //   `setRunning(false)` が飛ばされて **OptimizationRepository.running が永久に true** になっていた
        //   （＝完了後も optimizeInFlight() が真のままで、編集・Undo/Redo が恒久的にブロックされる）。
        //   自分で手放したことを覚えておき、finally はそれも所有者扱いにする。
        var releasedByMe = false
        var lastSnapMs = 0L
        var lastBubbleMs = 0L
        val wallStart = System.currentTimeMillis()   // [実機報告「残り時間表示が5分から何度も巡回する」修正]
        return try {
            // 再構築エンジン経路（RebuildOptimizeEntry.enabled=true）
            if (RebuildOptimizeEntry.enabled) {
                val rebuilt = RebuildOptimizeEntry.optimize(
                    state = req.first,
                    schedule = req.second.copy2D(),
                    budgetSec = budgetSec,
                    seed = System.currentTimeMillis(),
                    workers = bgWorkers,
                    shouldStop = { isStopped },
                    onProgress = { sp ->
                        if (ownsFiles()) {
                            val wallElapsed = System.currentTimeMillis() - wallStart
                            OptimizationRepository.publishProgress(
                                OptimizationRepository.BgProgress(
                                    sp.phase, sp.report.hard, sp.report.soft, sp.report.total,
                                    sp.iters, wallElapsed,
                                ),
                            )
                            if (wallElapsed - lastBubbleMs > 1_500L) {
                                lastBubbleMs = wallElapsed
                                val s = wallElapsed / 1000
                                val clock = "%d:%02d".format(s / 60, s % 60)
                                runCatching {
                                    BubbleSupport.postProgress(
                                        ctx,
                                        "再構築 ・ 経過 $clock ・ ${sp.phase} ・ 違反 ${sp.report.total}（必須 ${sp.report.hard}）",
                                    )
                                }
                            }
                            val snap = sp.schedule
                            if (snap != null && wallElapsed - lastSnapMs > 8_000L) {
                                lastSnapMs = wallElapsed
                                runCatching {
                                    snapshotFile(ctx).writeText(StateParser.serialize(req.first, snap))
                                }
                            }
                        }
                    },
                )
                if (ownsFiles()) {
                    runCatching {
                        val json = StateParser.serialize(req.first, rebuilt.schedule)
                        val tmp = File(resultFile(ctx).parentFile, "magi_bg_result.json.tmp")
                        tmp.writeText(json)
                        if (!tmp.renameTo(resultFile(ctx))) {
                            resultFile(ctx).writeText(json); tmp.delete()
                        }
                    }
                    OptimizationRepository.publishResult(
                        OptimizationRepository.BgResult(rebuilt.schedule, rebuilt.report, "rebuild"),
                    )
                    notifyDone(rebuilt.report.hard, rebuilt.report.total)
                    runCatching { inputFile(ctx).delete() }
                    runCatching { snapshotFile(ctx).delete() }
                    runCatching { runIdFile(ctx).delete() }
                    releasedByMe = true
                }
                return Result.success()
            }
            val res = V6FinalPort.handleOptimize(
                state = req.first,
                schedule = req.second.copy2D(),
                secondsRaw = budgetSec,
                workers = bgWorkers,
                allowImpossible = true,
            ) { phase, report, iters, elapsed ->
                if (report != null) {
                    // [実機報告「残り時間表示が5分から何度も巡回する」修正] onProgressのelapsedはフェーズ
                    //   境界（V5→ALNS→RSIラウンド等）で巻き戻るローカル時計。UI(progressSummaryの「残り」)と
                    //   会話バブルの「経過」表示、および下のスロットル判定(elapsed差分)はいずれも単調増加を
                    //   前提とするため、単調な壁時計(wallStart基準、MagiViewModel.runV6FullOptimizeの
                    //   startMsと同じ考え方)に統一する。
                    val wallElapsed = System.currentTimeMillis() - wallStart
                    // [3.329.0/外部レビュー H-03] 置き換えられた旧実行の進捗を新実行のものとして
                    //   見せない。所有権の確認はファイル1本の読取なので、8秒間引きの外でも十分安い。
                    if (!ownsFiles()) return@handleOptimize
                    OptimizationRepository.publishProgress(
                        OptimizationRepository.BgProgress(phase, report.hard, report.soft, report.total, iters, wallElapsed),
                    )
                    // [Android 17 バブル] 進捗を会話バブルへ反映（連続更新は onlyAlertOnce で静音・~1.5秒間引き）。
                    if (wallElapsed - lastBubbleMs > 1_500L) {
                        lastBubbleMs = wallElapsed
                        val s = wallElapsed / 1000
                        val clock = "%d:%02d".format(s / 60, s % 60)
                        runCatching {
                            BubbleSupport.postProgress(ctx, "計算中 ・ 経過 $clock ・ 違反 ${report.total}（必須 ${report.hard}）")
                        }
                    }
                    // [#4/C1] 途中最良解を定期スナップショット → kill されても「途中結果から再開」できる。
                    if (wallElapsed - lastSnapMs > 8_000L) {
                        lastSnapMs = wallElapsed
                        com.magi.app.v6.V6NativeOptimizer.liveBest?.let { live ->
                            // [3.327.0] 所有権を失っていたら書かない（8秒間引きの中なので追加I/Oは無視できる）。
                            if (ownsFiles()) {
                                runCatching { snapshotFile(ctx).writeText(StateParser.serialize(req.first, live.toIntArray2D())) }
                            }
                        }
                    }
                }
            }
            // [3.327.0/外部レビュー High3] 置き換えられた実行の結果は**公開も保存もしない**。
            //   旧実装はここに所有権の検査が無く、完了間際に REPLACE された実行が別データの結果を
            //   resultFile へ書き、次回起動でそれが現在のデータとして復元されうる状態だった。
            if (ownsFiles()) {
                // [3.336.0/外部レビュー S3] 順序を「耐久保存 → 公開」へ。旧は公開が先で、その間に
                //   プロセスが落ちるとメモリにしか無い結果が消えた。さらに `writeText` は非原子で、
                //   書き込み途中で落ちると**壊れた JSON が resultFile に残る**。起動時の復元は
                //   `resultTxt` が空でなければマーカーも入力も掃除してから読むので、壊れたファイルは
                //   「結果も再開手段も両方失う」経路になっていた。一時ファイル経由で置き換える。
                // [C1] 完了結果を耐久保存。UI不在(プロセス再起動でWorkerだけ走った)でも次回起動で反映できる。
                runCatching {
                    val json = StateParser.serialize(req.first, res.schedule)
                    val tmp = File(resultFile(ctx).parentFile, "magi_bg_result.json.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(resultFile(ctx))) { resultFile(ctx).writeText(json); tmp.delete() }
                }
                OptimizationRepository.publishResult(
                    OptimizationRepository.BgResult(res.schedule, res.report, res.phase),
                )
                notifyDone(res.report.hard, res.report.total)
                runCatching { inputFile(ctx).delete() }
                runCatching { snapshotFile(ctx).delete() }   // [#4] 完了でスナップショット破棄
                runCatching { runIdFile(ctx).delete() }
                releasedByMe = true
            }
            Result.success()
        } catch (e: CancellationException) {
            // [敵対的レビュー修正・#9] UI の stop() は cancelUniqueWork() の完了を待たず即座に
            //   clearFiles() するため、その直後に本Workerの進捗コールバックがまだキャンセルに
            //   気づかずスナップショットを再生成しうる。自身のキャンセルを検知した時点で必ず
            //   もう一度片付けてから伝播する（次回起動時に明示停止済みの古い盤面を復旧候補として
            //   読んでしまう事故を防ぐ）。
            // [3.327.0/外部レビュー High3] **所有者のときだけ**片付ける。置き換えで打ち切られた旧実行が
            //   ここを通ると、新実行が既に書いた入力ファイルまで消していた（復元不能の窓を作る）。
            if (ownsFiles()) runCatching { clearFiles(ctx) }
            throw e
        } catch (e: Exception) {
            notify("最適化に失敗しました", e.message ?: "原因不明")
            runCatching { BubbleSupport.postDone(ctx, "最適化に失敗しました") }
            // [3.336.0/外部レビュー P0残] 失敗だけが所有権を閉じない出口だった。マーカーと入力が残るので、
            //   次回起動が「中断されました・再開できます」と案内する（実際は失敗）。`Result.failure()` は
            //   WorkManager が再実行しない＝入力を残す意味も無い。所有者なら片付けてから返す。
            if (ownsFiles()) { runCatching { clearFiles(ctx) }; releasedByMe = true }
            Result.failure()
        } finally {
            // [3.329.0/外部レビュー H-03] **所有者のときだけ**実行中を降ろす。置き換えで打ち切られた
            //   旧実行がここを通ると、まだ動いている新実行の「実行中」を消してしまう。
            // [3.333.0] `releasedByMe` は「自分が正常完了してマーカーを消した」＝実行中を降ろすのが
            //   正しい経路。ここを見ないと完了後に実行中が残り続けた（上のコメント参照）。
            if (releasedByMe || ownsFiles()) OptimizationRepository.setRunning(false)
        }
    }

    /** Required for expedited work running as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("勤務表を最適化中")
            .setContentText("バックグラウンドで計算しています…")
            .setOngoing(true)
            .build()
        // minSdk 36 (Android 16+): foregroundServiceType is always required.
        return ForegroundInfo(NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(hard: Int, total: Int) {
        val msg = if (hard == 0) "配布できます（必須違反0・合計$total）" else "未解決$hard 件（合計$total）"
        notify("最適化が完了しました", msg)
        // [Android 17 バブル] 完了サマリを会話バブルへ反映（ongoing 解除）。
        runCatching { BubbleSupport.postDone(ctx, msg) }
    }

    private fun notify(title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NID_DONE, n) }
    }

    private fun ensureChannel() {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "勤務表の最適化", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val UNIQUE = "magi_bg_optimize"
        private const val CHANNEL = "magi_optimize"
        private const val NID_PROGRESS = 4101
        private const val NID_DONE = 4102

        // [C1] kill耐性: 入力・完了結果・途中最良解のファイル退避先（filesDir、UIと共有）
        fun inputFile(ctx: Context): File = ctx.filesDir.resolve("magi_bg_input.json")
        fun resultFile(ctx: Context): File = ctx.filesDir.resolve("magi_bg_result.json")
        fun snapshotFile(ctx: Context): File = ctx.filesDir.resolve("magi_bg_best.json")   // [#4] 途中最良解
        // [3.327.0/外部レビュー High3] いま所有権を持つ実行の ID。ファイル名は固定・
        //   `ExistingWorkPolicy.REPLACE` で入れ替わるため、**どの実行が書いたファイルか**を区別する術が
        //   無かった。区別できないと ①置き換えで打ち切られた旧実行が、新実行の入力ファイルを
        //   `clearFiles` で消す ②旧実行が完了間際なら別データの結果を `resultFile` へ書き、次回起動で
        //   それが現在のデータとして復元される、が起こりうる。
        fun runIdFile(ctx: Context): File = ctx.filesDir.resolve("magi_bg_run.txt")

        /** [3.327.0] enqueue の直前に呼び、この実行を所有者として記録する。 */
        fun beginRun(ctx: Context, runId: Long) {
            runCatching { runIdFile(ctx).writeText(runId.toString()) }
        }

        fun activeRunId(ctx: Context): Long =
            runCatching { runIdFile(ctx).readText().trim().toLong() }.getOrDefault(0L)

        fun clearFiles(ctx: Context) {
            runCatching { inputFile(ctx).takeIf { it.exists() }?.delete() }
            runCatching { resultFile(ctx).takeIf { it.exists() }?.delete() }
            runCatching { snapshotFile(ctx).takeIf { it.exists() }?.delete() }
            runCatching { runIdFile(ctx).takeIf { it.exists() }?.delete() }
        }

        const val KEY_SECONDS = "seconds"   // [P2] enqueue 時の予算秒数（WorkManager が永続化）
        const val KEY_WORKERS = "workers"   // [P2] enqueue 時の並列数
        const val KEY_RUN_ID = "runId"      // [3.327.0] 実行の識別子（WorkManager が永続化＝kill後も同一）

        private fun loadPair(f: File): Pair<MagiState, Array<IntArray>>? {
            if (!f.exists()) return null
            return runCatching {
                val st = StateParser.parse(f.readText())
                st to st.schedule.toIntArray2D()
            }.getOrNull()
        }

        /** [P2] kill後の復元: 途中最良スナップショット優先（8秒毎退避＝実質の途中再開）、無ければ元入力。 */
        private fun loadInputFromFile(ctx: Context): Pair<MagiState, Array<IntArray>>? =
            loadPair(snapshotFile(ctx)) ?: loadPair(inputFile(ctx))
    }
}
