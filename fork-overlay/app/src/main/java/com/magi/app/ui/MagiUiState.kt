package com.magi.app.ui

import com.magi.app.v6.CoverageDiagnosis
import com.magi.app.v6.ForbiddenRunDiagnosis
import com.magi.app.v6.C1PlateauDiagnosis
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6PortReport

// [リファクタ Phase3] UI 状態モデルを MagiViewModel.kt から分離（同一パッケージ・挙動不変）。
internal val emptyBreakdown: Map<String, Int> = MirrorKeys.all.associateWith { 0 }

data class UiState(
    val loaded: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,        // [Web反映] 手動修正ループ用の「やり直し」
    val staff: Int = 0,
    val days: Int = 0,
    val shifts: Int = 0,
    val groups: Int = 0,
    val use2: Boolean = false,
    val initHard: Long = 0,
    val initSoft: Long = 0,
    /** 実行中の**表示**。可否の判定には使わない（[MagiViewModel.optimizeInFlight] が唯一の根拠）。 */
    val running: Boolean = false,
    val hasResult: Boolean = false,
    val bestHard: Long = 0,
    val bestSoft: Long = 0,
    val totalViolations: Int = 0,
    val weightedScore: Double = 0.0,
    val breakdown: Map<String, Int> = emptyBreakdown,
    val violationCells: Map<String, String> = emptyMap(),
    val needViolations: Map<String, String> = emptyMap(),
    val countViolations: Map<String, String> = emptyMap(),
    // [Set化] セル("i,j")の全違反クラス（重み降順。violationCells は最重1クラス）。タップ全列挙とE7整合に使う。
    val violationCellFamilies: Map<String, List<String>> = emptyMap(),
    // [/code-review] 回数キー("i,k")/被覆キー("k,j")の全違反クラス（重み降順）。cellFamiliesの兄弟。
    //   breakdownLocations（内訳→場所タップ）が重い族に隠れた軽い族の場所を取りこぼさないために使う。
    val countFamilies: Map<String, List<String>> = emptyMap(),
    val needFamilies: Map<String, List<String>> = emptyMap(),
    // [場所表示] fair/weekly の職員単位の偏り箇所。"weekly"->[[i,dev],..] / "fair"->[[i,k,dev],..]（dev降順）。
    //   内訳パネルの場所表示専用（グリッドには出さない）。表示のみ・スコア不変。
    val distLocations: Map<String, List<List<Int>>> = emptyMap(),
    val fixSuggestions: List<com.magi.app.v6.FixSuggestion> = emptyList(),  // [改善提案] 違反を減らす1手（変更/交換）
    val fixSearching: Boolean = false,                                       // 改善手を探索中
    val fixFocusName: String = "",                                           // 絞り込み対象スタッフ名（空=全体）
    val logs: List<String> = emptyList(),
    val iters: Long = 0,
    val itersPerSec: Long = 0,
    val elapsedMs: Long = 0,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val budgetSec: Int = 300,
    val nativeAccel: Boolean = true,           // [Stage4] C++ネイティブ加速（SAチャンク）のユーザートグル
    val nativeParity: Boolean = true,          // [照合トグル] Kotlinパリティ照合。OFF=純ネイティブ(検証/ベンチ用・誤結果の可能性)
    val blockSwapC3nFilter: Boolean = false,    // [3.298.0] ブロック巡回交換で c3n が増える候補を候補生成段階で捨てるか。採用結果は不変・評価枠の節約のみ
    val wideC3nBreak: Boolean = false,          // [3.304.0] 禁止連続を崩す日を j±1 から違反パターン全域へ広げるか。既定OFF（実データで利得が一貫しない）
    val adaptiveEscape: Boolean = false,        // [3.306.0] 並列仮説の次の役割を残差から選び停滞の深さを保持するか。既定OFF（A/Bで有意差を検出できず）
    val softPolish: Boolean = true,   // [既定ON] 仕上げ最適化（品質研磨）。keep-best で悪化しない
    val v6Algorithm: V6Algorithm = V6Algorithm.AUTO,
    val staffNames: List<String> = emptyList(),
    val staffGroupSymbols: List<String> = emptyList(),
    val shiftSymbols: List<String> = emptyList(),
    val shiftColorHex: List<String> = emptyList(),
    val shiftTextHex: List<String> = emptyList(),
    val violationColorHex: String = "",   // 違反の表示色（空＝テーマのエラー色）。shiftColors["__vio__"] に保存。
    // [見直し候補] セル修正時に「基本ルールの見直し候補にする」で積むメモ（セッション内のみ・state 非保存）。
    val reviewMemos: List<String> = emptyList(),
    val violationSoftColorHex: String = "",   // 要調整(ソフト違反)の表示色（空＝既定の橙）。shiftColors["__vioSoft__"] に保存。
    // [違反色/族別] 族(c1/c3n/…)ごとの個別色。shiftColors["__vioFam_<fam>__"] 由来。未設定族は重大度色へフォールバック。
    val violationFamilyColorHex: Map<String, String> = emptyMap(),
    val schedule: List<List<Int>> = emptyList(),
    val wishes: Map<String, Int> = emptyMap(),   // ws3 希望 "i,j"->shiftIdx（表示融合用）
    val resultSchedule: List<List<Int>> = emptyList(),   // [B1→D7] 確定結果(ws6)スナップショット。読取モード撤去に伴い UI 参照ゼロ（モデルのみ温存）。
    val hasResultSnapshot: Boolean = false,               // [B1→D7] 結果スナップショットの有無（UI 参照ゼロ・温存）。
    // [backlog#1→D7] 結果(ws6)専用の違反マップ。読取モード撤去に伴い UI 参照ゼロ（makeUi の充填のみ継続・温存）。
    val resultViolationCells: Map<String, String>? = null,
    val resultNeedViolations: Map<String, String>? = null,
    val resultCountViolations: Map<String, String>? = null,
    val resultViolationCellFamilies: Map<String, List<String>>? = null,
    val resultCountFamilies: Map<String, List<String>>? = null,
    val resultNeedFamilies: Map<String, List<String>>? = null,
    val liveSchedule: List<List<Int>> = emptyList(),      // [DefragLiveView] 計算中の最良盤面（実行中のみ）
    val v6: V6PortReport? = null,
    val constraintsEdited: Boolean = false,
    val structureEdited: Boolean = false,
    // [再構成保証] 構造編集(apt/群/シフト等)ごとに単調増加。structureEdited は Boolean で既 true 時に copy が
    //   同値となり StateFlow が emit せず、かつ currentSchedule=null 時は refreshCheck も早期returnするため、
    //   editScope の編集画面(AptCard 等が vm.ws1()=state 直読み)が再構成されず「+/-で数字が変わらない」実機バグを
    //   生んでいた。applyStructure が毎回これを増やして必ず distinct な UiState を emit＝確実に再構成させる。
    val editRev: Int = 0,
    val message: String? = null,
    // [判断設計監査 #3] 「データを開く」直前の1世代退避が存在するか（設定タブの復元導線の表示条件）。
    val prevBackupAvailable: Boolean = false,
    // 操作コパイロット: 満足度(0-100) / 研磨の限界 / ガチャ操作の助言
    val satisfaction: Int = 0,
    val polishExhausted: Boolean = false,
    val copilotHint: String? = null,
    val impossibleWishCount: Int = 0,
    val opLog: List<String> = emptyList(),
    /** 操作ログ・セッションファイルへの出力。OFF で探索中のログ書き込みを抑止（性能・静音）。 */
    val sessionLogEnabled: Boolean = true,
    val alternatives: List<String> = emptyList(), // 他の案（採用案以外の候補サマリ）
    val coverageDiag: CoverageDiagnosis? = null,  // 人員不足(covU)/人員過剰(covO)の原因診断（充足不可/充足可能の切り分け・過剰がなぜ動かせないか）
    val forbiddenDiag: ForbiddenRunDiagnosis? = null,  // [3.280.0] 禁止連続(c3n)の「なぜ崩せないか」診断（c3n=0 なら null）
    val c1Plateau: C1PlateauDiagnosis? = null,  // [3.322.0] 窓の要件(c1)がなぜ直せなかったかの構造化診断（直近の最適化の観測。残存なし/未実行なら null）
    // [3.325.0] 直近の最適化で「回数固定(lo==hi)だけが却下の理由だった」**計測済みの候補試行数**。
    //   全手数でも改善予測でもない。**[3.327.0 で範囲を訂正]** 計測しているのは後処理研磨のうち
    //   `V6HotfixPasses` の18パス＋最終LNS 2本（`C1JointLns`/`PersonalBalanceJointLns`）＝**20パス**
    //   （3.350.0 で追加。それまで LNS 2本は却下するだけで数えておらず、real_state で 1,898件が
    //   UI から抜けていた＝配線後は総数 181→1,617）。`EliteIntegration`(4)/`C1TemporalFlow`(1)/
    //   `CombinatorialRepair`(2)/`C1RepairAnalysis`(1) の計8箇所と探索本体(SA/ALNS/LAHC)は計測外。
    //   最大4巡を重複排除せず加算する。0 は「緩めても変わらない」の証明にならない。
    val observedPinBlockedAttempts: Int = 0,
    // [3.326.0] 緩和の対象候補（どのピンが何回止めたか・多い順）。空＝観測なし。
    val pinTargets: List<PinTargetView> = emptyList(),
    val settingIssues: List<com.magi.app.v6.SettingIssue> = emptyList(), // 制約/希望の設定ミスと直し方の誘導
    val startDate: String = "",                   // 期間開始日（カレンダー表示の曜日整列に使用）
    val interruptedRun: Boolean = false,          // 前回の計算がプロセスkill等で中断された
    val interruptedInfo: String? = null,
)

/**
 * [3.326.0] 回数固定(lo==hi)の緩和対象1件。
 * `attempts` は「目的関数が採用を認めた手を、このピンのガードだけが止めた**計測できた回数**」。
 * 手の数ではなく試行の回数で、研磨の巡（最大4）を重複排除せず数えている。
 * **0 件でも緩和が無意味とは限らない** — 緩和は下限割れ(low, 重み90)の罰も外すため、
 * 「ピン以外の理由で」却下されていた候補が通るようになる経路が別にある（実測で確認済み）。
 */
data class PinTargetView(
    val staff: Int,
    val shift: Int,
    val staffName: String,
    val shiftKigou: String,
    /** 固定されている回数（lo==hi の値）。 */
    val pinnedCount: Int,
    val attempts: Int,
)
