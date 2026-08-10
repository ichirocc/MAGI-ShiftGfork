package com.magi.app.ui

import android.content.Context
import com.magi.app.v6.PolishGate

/**
 * 調整トグルの永続化。
 *
 * 問題: ViewModel 再生成（プロセス再作成・設定回転など）で [UiState] が既定値に戻り、
 * 画面のスイッチ表示が実際の探索設定（[PolishGate] / 前回ユーザー選択）と食い違う。
 *
 * 対応: SharedPreferences に保存し、VM init で UiState と PolishGate を同時復元する。
 */
object OptimizeToggleStore {
    private const val PREFS = "magi_optimize_toggles"

    const val KEY_WIDE_C3N = "wideC3nBreak"
    const val KEY_ADAPTIVE = "adaptiveEscape"
    const val KEY_SOFT_POLISH = "softPolish"
    const val KEY_BLOCK_SWAP_C3N = "blockSwapC3nFilter"
    const val KEY_NATIVE_ACCEL = "nativeAccel"
    const val KEY_NATIVE_PARITY = "nativeParity"
    const val KEY_SESSION_LOG = "sessionLogEnabled"

    data class Snapshot(
        val wideC3nBreak: Boolean = false,
        val adaptiveEscape: Boolean = false,
        val softPolish: Boolean = true,
        val blockSwapC3nFilter: Boolean = false,
        val nativeAccel: Boolean = true,
        val nativeParity: Boolean = true,
        val sessionLogEnabled: Boolean = true,
    )

    fun load(ctx: Context): Snapshot {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            wideC3nBreak = p.getBoolean(KEY_WIDE_C3N, false),
            adaptiveEscape = p.getBoolean(KEY_ADAPTIVE, false),
            softPolish = p.getBoolean(KEY_SOFT_POLISH, true),
            blockSwapC3nFilter = p.getBoolean(KEY_BLOCK_SWAP_C3N, false),
            nativeAccel = p.getBoolean(KEY_NATIVE_ACCEL, true),
            nativeParity = p.getBoolean(KEY_NATIVE_PARITY, true),
            sessionLogEnabled = p.getBoolean(KEY_SESSION_LOG, true),
        )
    }

    fun save(ctx: Context, snap: Snapshot) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIDE_C3N, snap.wideC3nBreak)
            .putBoolean(KEY_ADAPTIVE, snap.adaptiveEscape)
            .putBoolean(KEY_SOFT_POLISH, snap.softPolish)
            .putBoolean(KEY_BLOCK_SWAP_C3N, snap.blockSwapC3nFilter)
            .putBoolean(KEY_NATIVE_ACCEL, snap.nativeAccel)
            .putBoolean(KEY_NATIVE_PARITY, snap.nativeParity)
            .putBoolean(KEY_SESSION_LOG, snap.sessionLogEnabled)
            .apply()
    }

    fun put(ctx: Context, key: String, value: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }

    /** UiState 既定 + PolishGate 静的に同時適用 */
    fun applyToRuntime(ctx: Context, snap: Snapshot = load(ctx)) {
        runCatching {
            PolishGate.wideC3nBreakDays = snap.wideC3nBreak
            PolishGate.adaptiveEscapeControl = snap.adaptiveEscape
        }
    }
}
