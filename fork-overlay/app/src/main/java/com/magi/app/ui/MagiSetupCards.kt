package com.magi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import java.time.LocalDate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.CoverageVerdict
import com.magi.app.v6.MirrorKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * CSVのバイト列を文字列へ復号する。妥当な UTF-8 ならそれを採用し、そうでなければ日本の Excel CSV で
 * 一般的な CP932(Shift-JIS) とみなす。先頭の BOM は除去する。これにより Shift-JIS の勤務表CSVが
 * 文字化けせず取り込める（UTF-8 として bytes を読むと壊れていた）。
 */

@Composable
internal fun MonthPickerCard(ui: UiState, vm: MagiViewModel) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val label = remember(ui.startDate) {
        runCatching { val d = LocalDate.parse(ui.startDate); "${d.year}年 ${d.monthValue}月" }.getOrNull()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("対象の月", style = MaterialTheme.typography.titleMedium)
            Text("作成対象の月。変えると日数に合わせて表を作り直します。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.shiftMonth(-1) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("前の月") }
                Text(label ?: "未設定", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.shiftMonth(1) }, enabled = !ui.running,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("次の月") }
            }
            // [実機指摘] 月末に「来月」の表を作る業務フローに合わせ、ワンタップは来月へ。
            OutlinedButton(onClick = { vm.setNextMonth() }, enabled = !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("来月にする") }
        }
    }
}


@Composable
internal fun SetupGuideCard(ui: UiState, vm: MagiViewModel, editScope: Int = -1, onOpenWish: (() -> Unit)? = null) {
    if (!ui.loaded) return
    val c = vm.setupCounts()
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("初期設定の手順", style = MaterialTheme.typography.titleMedium)
            // [E1] 番号順(①〜⑤)とeditScopeの不一致を解消：毎月触る項目と年次マスター項目を分けて表示。
            //   ①基本情報・④制約・⑤回数範囲は「年次マスター」scopeにあり、月次では編集しない。
            // [監査A1] 旧①〜⑤番号と旧スコープ名（年次マスター等）が新3ドア/新節番号と食い違っていた→ドア名で案内。
            // [冗長性見直し] editScope==0(月次条件)では MonthlyChecklistCard がこの直下により詳しい形
            //   （件数比・標準/例外の区別・作成ボタン）で同じ2行を表示するため、そこでは重複を隠す。
            //   他スコープ(職員管理/年間マスター)では引き続き表示し、希望シフトへのショートカットを維持する。
            if (editScope != 0) {
                Text("── 月次条件（毎月）──", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                // [見つけやすさ改善] 「希望シフト」行をタップで登録画面へ直行（既存機能の入口が編集タブの奥に
                //   埋もれ見つけにくいという指摘。新規画面は作らず、常時表示のこの案内カードから最短で開く）。
                GuideRow("希望シフト", "${c.wishes}件", c.wishes > 0, onClick = onOpenWish)
                GuideRow("必要人数の例外", if (c.needDay > 0) "${c.needDay}件（個別指定）" else "シフト既定のみ", true)
            }
            Text("── 年間マスター（制度が変わったときだけ）──", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            GuideRow("基本情報", "${c.days}日 / ${c.staff}名 / ${c.shifts}シフト / ${c.groups}グループ", c.days > 0 && c.staff > 0 && c.shifts > 0)
            GuideRow("ルール（並び・人数など）", "${c.constraints}件", true)
            GuideRow("⑤ 個人の回数範囲", "${c.ranges}件", true)
            val next = when {
                c.staff == 0 || c.shifts == 0 -> "基本情報（職員／シフト）を整えましょう。"
                c.wishes == 0 -> "次に『希望シフト』を登録すると できあがり度 が上がります。"
                else -> "準備OK。ホームの『勤務表をつくる』で勤務表を作成しましょう。"
            }
            Surface(color = cs.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text("次の一手: $next", color = cs.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}


@Composable
internal fun GuideRow(label: String, value: String, done: Boolean, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier,
    ) {
        Text(if (done) "✓" else "・", color = if (done) cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value + (if (onClick != null) " ›" else ""), color = if (onClick != null) cs.primary else cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * [3.188.0 オプション集約] 主要（毎回の判断材料になる項目）だけを常時表示: 計算の制限時間・計算方式・
 * バックグラウンドでつくる。技術系（並列ワーカー・ネイティブ加速・Kotlin照合・仕上げ最適化＝一般の運用では
 * 触らない内部チューニング）は既存の「詳細設定（上級者向け）」（`AdvancedSettingsSection`）へ移動。
 */
@Composable
internal fun SettingsCard(ui: UiState, vm: MagiViewModel, onBgOptimize: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("最適化設定", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("計算の制限時間（最長5分・停滞時は早く終わることも）: ${ui.budgetSec} 秒")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.setBudget((ui.budgetSec - 60).coerceAtLeast(10)) },
                    enabled = !ui.running && ui.budgetSec > 10, modifier = Modifier.height(48.dp)) { Text("− 60秒") }
                Text("${ui.budgetSec} 秒", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
                Button(onClick = { vm.setBudget((ui.budgetSec + 60).coerceAtMost(MAX_BUDGET_SEC)) },
                    enabled = !ui.running && ui.budgetSec < MAX_BUDGET_SEC, modifier = Modifier.height(48.dp)) { Text("＋ 60秒") }
            }
            Spacer(Modifier.height(10.dp))
            Text("計算方式: ${v6AlgorithmLabel(ui.v6Algorithm)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // [3.192.0 情報不足の解消] 「おまかせ」選択中は実際に動く方式が計算の制限時間から自動決定される
            // （V6FinalPort.optimizationPlan/getAlgorithmLabelと同一ロジック）が、画面には「おまかせ」としか
            // 出ず、今の時間設定で何が動くか見えなかった。現在の budgetSec での解決結果を併記する（表示のみ）。
            if (ui.v6Algorithm == V6Algorithm.AUTO) {
                val resolved = V6FinalPort.getAlgorithmLabel(ui.budgetSec)
                Text("→ 今の設定(${ui.budgetSec}秒)では ${resolved.icon} ${resolved.name}（${resolved.desc}）が動きます",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V6Algorithm.values().forEach { alg ->
                    val selected = ui.v6Algorithm == alg
                    if (selected) {
                        Button(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                    } else {
                        OutlinedButton(onClick = { vm.setV6Algorithm(alg) }, enabled = !ui.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(v6AlgorithmLabel(alg)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // [移設] ホームの「ほかの作り方」カード撤去（ユーザー赤囲い指示）に伴い、固有機能の
            //   バックグラウンド実行だけをここ（実行条件＝予算の設定と同じ場所）へ移設。
            OutlinedButton(onClick = onBgOptimize, enabled = ui.loaded && !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("バックグラウンドでつくる（閉じても続行）") }
            Spacer(Modifier.height(14.dp))
            // [バージョン表示] インストール済みAPKの versionName/versionCode を実行時に取得して表示。
            //   これでユーザーが「今どの版か」を確認できる（例: CSVのBOM対応は 2.90.0 以降）。
            val ctx = LocalContext.current
            val versionLabel = remember(ctx) {
                runCatching {
                    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    "${pi.versionName} (${pi.longVersionCode})"
                }.getOrDefault("不明")
            }
            Text("バージョン: $versionLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [3.188.0 オプション集約] 技術系チューニング（並列ワーカー・ネイティブ加速・Kotlin照合・仕上げ最適化）。
 *  `SettingsCard` から「詳細設定（上級者向け）」へ移動した項目。一般の運用では既定値のままで問題ない。 */
@Composable
private fun OptimizationTuningSection(ui: UiState, vm: MagiViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("並列ワーカー（同時に計算する数）: ${ui.workers}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setWorkers((ui.workers - 1).coerceAtLeast(1)) },
                enabled = !ui.running && ui.workers > 1, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を減らす" }) { Text("−", fontSize = 20.sp) }
            Text("${ui.workers}", style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            Button(onClick = { vm.setWorkers((ui.workers + 1).coerceAtMost(com.magi.app.v6.engine.parallel.ParallelSaCoordinator.MAX_PARALLEL)) },
                enabled = !ui.running && ui.workers < 16, modifier = Modifier.height(48.dp).semantics { contentDescription = "同時計算数を増やす" }) { Text("＋", fontSize = 20.sp) }
        }
        // [仮説数上限撤廃・ユーザー指示] 旧: 仮説数は5固定・超過ワーカーは仮説内並列度へ配分。
        //   現在は設定値がそのまま並列に探索する仮説（案）の数になる（下限2、上限=設定値自体）。
        //   表示はエンジン本体と同じ V6NativeOptimizer.hypothesisCount から導出し独立再計算による乖離を防ぐ。
        run {
            val cores = Runtime.getRuntime().availableProcessors()
            val hyp = com.magi.app.v6.V6NativeOptimizer.hypothesisCount(ui.workers)
            val overNote = if (ui.workers > cores)
                "この端末のコア数(${cores})を超えるためコアを奪い合います。極端に大きい値は電池・発熱に注意してください。"
            else ""
            Text(
                "※「高速」（おまかせで制限時間30秒以下の場合を含む）は設定値をそのままSAチェーン数に使います。" +
                    "それ以外の方式では設定値がそのまま並列に探索する仮説（案）の数（${hyp}）になります。$overNote",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("ネイティブ加速（C++）")
                Text("計算の内側ループを高速版で実行。結果は常にKotlin実装と照合され、不一致なら自動で従来方式に戻ります。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.nativeAccel, onCheckedChange = { vm.setNativeAccel(it) }, enabled = !ui.running)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Kotlin照合", color = if (ui.nativeParity) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text(
                    if (ui.nativeParity)
                        "高速版の結果を毎回Kotlin実装で検証。不一致なら自動で従来方式に戻ります（推奨・既定）。"
                    else
                        "⚠ 検証せず高速版の結果をそのまま採用します（速度重視の検証/ベンチ用）。誤った勤務表が表示される可能性があります。",
                    fontSize = 12.sp,
                    color = if (ui.nativeParity) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = ui.nativeParity, onCheckedChange = { vm.setNativeParity(it) }, enabled = !ui.running && ui.nativeAccel)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("禁止連続の事前フィルタ")
                Text("期間まるごとの入れ替えを試すとき、禁止の並びを新しく作る案を最初から候補にしません。" +
                    "できあがる勤務表は同じで（そういう案は最後に必ず却下されるため）、無駄な検査を省くぶんだけ速くなります。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.blockSwapC3nFilter, onCheckedChange = { vm.setBlockSwapC3nFilter(it) }, enabled = !ui.running)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("禁止連続の崩し範囲")
                Text(
                    if (ui.wideC3nBreak)
                        "⚠ 禁止の並びを崩すとき、並び全体（前後2日以上）まで動かします。3つ以上つながる並びの先頭にも手が届きますが、" +
                            "できあがる勤務表が良くなるとは限りません（データによっては悪くなります）。"
                    else
                        "禁止の並びを崩すとき、前後1日だけを動かします。",
                    fontSize = 12.sp,
                    color = if (ui.wideC3nBreak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.wideC3nBreak, onCheckedChange = { vm.setWideC3nBreak(it) }, enabled = !ui.running)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("行き詰まりからの立て直し方")
                Text(
                    if (ui.adaptiveEscape)
                        "⚠ 並列で走る複数案が行き詰まったとき、残っている違反の種類に合わせて次の手を選びます。" +
                            "できあがる勤務表が良くなるとは限りません（試した範囲では差が出ませんでした）。"
                    else
                        "並列で走る複数案が行き詰まったとき、決まった順番で次の手を試します。",
                    fontSize = 12.sp,
                    color = if (ui.adaptiveEscape) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = ui.adaptiveEscape, onCheckedChange = { vm.setAdaptiveEscape(it) }, enabled = !ui.running)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = ui.softPolish,
                onCheckedChange = { vm.setSoftPolish(it) },
                enabled = !ui.running,
            )
            Spacer(Modifier.width(8.dp))
            Text("仕上げ最適化", fontSize = 14.sp)
        }
    }
}

/** [見やすさ] 計算方式(V6Algorithm)の一般向け日本語ラベル。技術名(AUTO/RSI 等)は操作者に不明なため。 */
internal fun v6AlgorithmLabel(alg: V6Algorithm): String = when (alg) {
    V6Algorithm.AUTO -> "おまかせ"
    V6Algorithm.V5 -> "高速"
    V6Algorithm.ALNS -> "破壊再構築"
    V6Algorithm.RSI -> "違反集中"
    V6Algorithm.RSI_PLUS -> "違反集中＋"
    // [3.191.0 用語重複解消] 「並列ワーカー」（同時実行数=workers）と「並列(複数案)」が同じ「並列」を
    //   冠していて意味が重複して見えた。本方式はworkers本のうち各hypothesisへ異なるアルゴリズムを
    //   割り当てる（portfolioAlgoFor）＝並列度そのものではなく「方式を組み合わせる」戦略のため改称。
    V6Algorithm.PORTFOLIO -> "方式ミックス"
}


// [3.112.0 撤去] ActionCard（ほかの作り方: 速くつくる/かんたんに/閉じても大丈夫）はユーザー赤囲い指示で撤去。
//   速く/かんたんは主導線（思考誘導カード onMake/onDraft）と重複、バックグラウンド実行は SettingsCard へ移設。

/**
 * [入口4分割/月次条件] 今月の作成条件チェックリスト。作成前の入力状況（職員/希望/必要人数/入力診断）を
 * 1枚で確認し、そのまま作成へ進む（月末モードの Step0）。read-only＋作成ボタン＝スコアリング不変。
 * 例外件数は D6 に従い、明示的な例外リストを持つ「日別必要人数の例外」のみを数える。
 */
@Composable
internal fun MonthlyChecklistCard(ui: UiState, vm: MagiViewModel, onMake: () -> Unit, onOpenWish: (() -> Unit)? = null) {
    if (!ui.loaded) return
    val staffN = ui.staffNames.size
    val wishStaff = remember(ui.wishes, staffN) {
        ui.wishes.keys.mapNotNull { it.substringBefore(",").toIntOrNull() }.toSet().size
    }
    val needExceptions = vm.needDayOverrides().size
    val needStdOk = vm.ws1()?.shifts?.any { it.need1.isNotBlank() } == true
    val issues = ui.settingIssues.size
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今月の作成条件", style = MaterialTheme.typography.titleMedium)
            ChecklistRow("職員", "${staffN}名", ok = staffN > 0)
            // [見つけやすさ改善] タップで希望シフト登録へ直行（SetupGuideCardと同じ入口を共有）。
            ChecklistRow("希望・休暇", "${wishStaff}/${staffN}名 入力済み", ok = wishStaff > 0, onClick = onOpenWish)
            ChecklistRow("必要人数", (if (needStdOk) "標準あり" else "標準が未設定") + "・例外${needExceptions}件", ok = needStdOk)
            ChecklistRow("入力診断", if (issues == 0) "問題なし" else "見直し ${issues}件（ホームに詳細）", ok = issues == 0)
            Button(onClick = onMake, enabled = ui.loaded && !ui.running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("▶ 勤務表をつくる", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, value: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier,
    ) {
        Text(if (ok) "✓" else "！", color = if (ok) cs.tertiary else cs.error, fontWeight = FontWeight.Bold)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value + (if (onClick != null) " ›" else ""), color = if (onClick != null) cs.primary else cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * [年度始めモード/実働チェック] 「15人いるから大丈夫」ではなく、シフトごとの実働体制で見る（D5 残スコープ）。
 * Q=担当できる人数(canDo)・D=月間需要人日(Σ日次必要数)・日最大=1日に同時に必要な最大人数。
 * 欠勤余裕 = Q − 日最大（1人欠けてもその日の必要人数を揃えられるか）。read-only・スコアリング不変。
 * データは Problem 由来（allowedShiftsFor / needCellLimits=need1+日別例外込み）＝チェッカーと同じ実効値。
 */
@Composable
internal fun StaffingRealityCard(ui: UiState, vm: MagiViewModel) {
    if (!ui.loaded) return
    val cs = MaterialTheme.colorScheme
    val days = ui.days
    val staffN = ui.staffNames.size
    val canDoCount = IntArray(ui.shiftSymbols.size)
    for (i in 0 until staffN) vm.allowedShiftsFor(i).forEach { k -> if (k in canDoCount.indices) canDoCount[k]++ }
    data class RowV(val sym: String, val q: Int, val d: Int, val maxNeed: Int)
    val rows = ui.shiftSymbols.indices.mapNotNull { k ->
        var d = 0; var mx = 0
        for (j in 0 until days) { val lo = vm.needCellLimits(k, j)?.first ?: 0; d += lo; if (lo > mx) mx = lo }
        if (d == 0) null else RowV(ui.shiftSymbols[k], canDoCount[k], d, mx)
    }
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("この体制で回るか", style = MaterialTheme.typography.titleMedium)
            Text("シフト別に「担当できる人数」で確認。余裕=1人欠けても回るか。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            rows.forEach { r ->
                val slack = r.q - r.maxNeed
                val tenths = if (r.q > 0) (r.d * 10 + r.q / 2) / r.q else 0
                val (mark, col) = when {
                    slack < 0 -> "⚠" to cs.error
                    // [UD監査] 橙(2.7:1)は白地の文字色として不足→警告トークンの濃色(fg)へ。
                    slack == 0 -> "！" to magiWarnColors().second
                    else -> "✓" to cs.tertiary
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mark, color = col, fontWeight = FontWeight.Bold)
                    Text(r.sym, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), maxLines = 1)
                    Text(
                        "担当${r.q}人・月${r.d}人日（1人あたり${tenths / 10}.${tenths % 10}回）・" +
                            when {
                                slack < 0 -> "日最大${r.maxNeed}人 → 担当不足"
                                slack == 0 -> "日最大${r.maxNeed}人 → 欠勤余裕なし"
                                else -> "欠勤余裕${slack}人"
                            },
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * [見直し候補] 月次の修正（セル編集シート）から送られた「基本ルールの見直し候補」メモの一覧。
 * 年間マスターを開いたとき最初に目に入る位置に置き、「毎月同じ手修正→制度の直し」への橋にする。
 * セッション内メモ（state 非保存）＝アプリ終了で消える旨を明示。read-only・スコアリング不変。
 */
@Composable
internal fun ReviewMemoCard(ui: UiState, vm: MagiViewModel) {
    if (ui.reviewMemos.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("見直し候補（${ui.reviewMemos.size}件）", style = MaterialTheme.typography.titleMedium)
            Text("勤務表の修正中に印を付けたルール見直しのメモです（アプリ終了で消えます）。",
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            ui.reviewMemos.forEachIndexed { idx, memo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(memo, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    DeleteRowButton(onClick = { vm.removeReviewMemo(idx) }, text = "済")
                }
            }
        }
    }
}


@Composable
internal fun AdvancedSettingsSection(
    ui: UiState,
    vm: MagiViewModel,
    onExportLog: () -> Unit,
    onExportJson: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }   // 既定は閉
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("詳細設定（上級者向け）", style = MaterialTheme.typography.titleMedium)
                    // [3.188.0 オプション集約] 並列/ネイティブ/仕上げの技術系チューニングを SettingsCard から
                    //   ここへ移設したため、説明文を「ログのみ」から実態に同期。
                    Text("並列数・ネイティブ加速・仕上げ最適化の調整とログの確認。一般の運用では触りません。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // [Planner テイスト] グリフ「▾/▸」をやめ、やわらかい丸の中に整ったアイコンシェブロン。
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "閉じる" else "開く",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    // [冗長性] 旧 FlagsView（計算方式＋仕上げ最適化）は「最適化設定」カードと完全重複の
                    //   ため撤去。設定の操作は「最適化設定」に一本化。
                    // [冗長性J1] V6DashboardCard（1ヶ月俯瞰・生指標）は分析タブ「プロ」表示と重複のため
                    //   ここから撤去し、分析タブ(プロ)に一本化。
                    // [IA重複解消 3.132系] ColorSettingsView（違反種別の色）はシフトの表示色の直後
                    //   （設定タブ上部）へ移動＝色設定を1か所に。ここはログのみ。
                    // [3.188.0 オプション集約] 並列ワーカー/ネイティブ加速/Kotlin照合/仕上げ最適化は
                    //   一般の運用では触らない内部チューニングのため SettingsCard からここへ移動。
                    OptimizationTuningSection(ui, vm)
                    LogsCard(ui = ui, vm = vm, onExportLog = onExportLog, onExportJson = onExportJson)
                }
            }
        }
    }
}



@Composable
internal fun LogsCard(ui: UiState, vm: MagiViewModel, onExportLog: () -> Unit, onExportJson: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var savedCount by remember { mutableStateOf(0) }
    LaunchedEffect(ui.running, ui.opLog.size) {
        savedCount = runCatching { vm.listSavedSessionLogs().size }.getOrDefault(0)
    }
    val hasAny = ui.opLog.isNotEmpty() || ui.logs.isNotEmpty() || savedCount > 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ログ", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ログ出力", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (ui.sessionLogEnabled) "操作ログとファイル保存 ON" else "探索中のログを抑止（性能優先）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ui.sessionLogEnabled,
                    onCheckedChange = { vm.setSessionLogEnabled(it) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportLog, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("テキスト出力") }
                OutlinedButton(onClick = onExportJson, enabled = hasAny, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("JSON出力") }
            }
            OutlinedButton(
                onClick = {
                    vm.loadLatestSavedLog()
                    savedCount = runCatching { vm.listSavedSessionLogs().size }.getOrDefault(0)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (savedCount > 0) "保存ログを表示（${savedCount}件）" else "保存ログを表示")
            }
            // 操作ログ（監査・新しい順）
            Text("操作ログ（新しい順 ${ui.opLog.size}件）", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            if (ui.opLog.isEmpty()) {
                Text("操作履歴なし", color = cs.onSurfaceVariant, fontSize = 12.sp)
            } else {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp)
                        .background(cs.surfaceVariant, MaterialTheme.shapes.small).padding(10.dp),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ui.opLog.take(60).forEach { line ->
                            val warn = line.contains("[W]") || line.contains("[E]")
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = if (warn) cs.error else cs.onSurface)
                        }
                    }
                }
            }
            // 診断ログ（エンジン）
            if (ui.logs.isNotEmpty()) {
                Text("診断ログ", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                ui.logs.take(6).forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


@Composable
internal fun DataActionsCard(
    ui: UiState,
    onOpenJson: () -> Unit, onSample: () -> Unit, onSaveJson: () -> Unit,
    onOpenCsv: () -> Unit, onSaveCsv: () -> Unit, onCheck: () -> Unit,
    onSaveStaffCsv: () -> Unit = {}, onSaveWishesCsv: () -> Unit = {}, onSaveConstraintsCsv: () -> Unit = {},
    onRestorePrev: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("データ", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenJson, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("データを開く") }
                OutlinedButton(onClick = onSample, enabled = !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("サンプル") }
            }
            // [判断設計監査 #3] 開く=現データの置換。置換直前の1世代退避へ戻す導線（押すたび入れ替わる）。
            if (ui.prevBackupAvailable) {
                TextButton(onClick = onRestorePrev, enabled = !ui.running, modifier = Modifier.fillMaxWidth()) {
                    Text("開く前のデータに戻す（もう一度押すと入れ替え）")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveJson, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("データを保存") }
                OutlinedButton(onClick = onCheck, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("問題がないか調べる") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV取込") }
                OutlinedButton(onClick = onSaveCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("CSV出力") }
            }
            Text("コンポーネント別 出力（取込種別と対・往復用）",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveStaffCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("職員") }
                OutlinedButton(onClick = onSaveWishesCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("希望") }
                OutlinedButton(onClick = onSaveConstraintsCsv, enabled = ui.loaded && !ui.running, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("制約") }
            }
        }
    }
}


@Composable
internal fun AppearanceCard(
    oneHand: Boolean = false, onOneHand: (Boolean) -> Unit = {},
    proMode: Boolean = false, onProMode: (Boolean) -> Unit = {},
) {
    // [D8/UD固定] 配色セレクタ（自動/明/暗/UD）はユーザー判断で撤去。テーマは UD（高コントラスト）固定。
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("外観", style = MaterialTheme.typography.titleMedium)
            Text("配色はユニバーサルデザイン（高コントラスト）固定です。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = oneHand, onCheckedChange = onOneHand)
                Spacer(Modifier.width(8.dp))
                Text("片手モード", fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
            // [プロ編集] 表示モード。プロ＝数値診断（生指標）を前面に。今後さらに高密度編集を拡張予定。
            Text("表示モード", style = MaterialTheme.typography.titleSmall)
            MagiSegmentedControl(options = listOf("かんたん", "プロ"), selected = if (proMode) 1 else 0, onSelect = { onProMode(it == 1) })
        }
    }
}

/**
 * [E6案A/⛏?] 折りたたみ可能なセクション。年次マスターの長大スクロールを、不要なカードを
 * 畳んで削減する。展開状態は rememberSaveable(stateKey) で保持し、回転/再構成でも維持(P4対策)。
 * アンカージャンプ(案B)は LazyColumn 変換が要るため別途・実機目視前提。
 */
@Composable
internal fun SectionNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
internal fun CollapsibleSection(
    title: String,
    stateKey: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (expanded) "▼ 閉じる" else "▶ 開く", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
