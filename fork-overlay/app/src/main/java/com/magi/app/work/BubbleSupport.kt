package com.magi.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.LocusIdCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.magi.app.R

/**
 * Android 会話バブル対応（API 30+ / 本アプリ minSdk 36）。
 *
 * バックグラウンド最適化（[OptimizationWorker]）の進捗・完了を、他アプリの上に浮かぶ
 * 会話バブルとして提示する。
 *
 * 成立条件:
 * 1. 長寿命の会話ショートカット（[pushShortcut]）
 * 2. MessagingStyle + setShortcutId + Person
 * 3. BubbleMetadata（展開先 [BubbleActivity]）
 * 4. 埋め込み可能な Activity（allowEmbedded / resizeable / documentLaunchMode=always）
 * 5. チャンネルの setAllowBubbles(true) + IMPORTANCE_HIGH
 *
 * FGS 用の進捗通知（NID_PROGRESS）とは別通知（[NID_BUBBLE]）。
 * 表示専用・最適化本体の成否には影響しない。
 */
object BubbleSupport {
    const val CHANNEL_BUBBLE = "magi_optimize_bubble"
    const val SHORTCUT_ID = "magi_optimize_conversation"
    const val NID_BUBBLE = 4103
    private const val CATEGORY_OPTIMIZE = "com.magi.app.category.OPTIMIZE"

    private fun person(ctx: Context): Person =
        Person.Builder()
            .setName("勤務表の最適化")
            .setKey(SHORTCUT_ID)
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setBot(true)
            .build()

    fun canPostNotifications(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** システムがこのアプリのバブルを許可しているか（ユーザー設定）。 */
    fun areBubblesAllowed(ctx: Context): Boolean {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            mgr.areBubblesAllowed()
        } catch (_: Throwable) {
            true
        }
    }

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val existing = mgr.getNotificationChannel(CHANNEL_BUBBLE)
        if (existing == null) {
            val ch = NotificationChannel(
                CHANNEL_BUBBLE,
                "最適化バブル",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "最適化の進捗・完了を浮遊バブルで表示"
                setAllowBubbles(true)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        } else if (!existing.canBubble()) {
            // 作成済みでもバブル不可なら再作成できないため、ユーザー設定依存。ログのみ。
        }
    }

    /**
     * 長寿命会話ショートカット。Android 11+ でバブル必須。
     * setPerson / setLongLived / 同一 SHORTCUT_ID を維持する。
     */
    fun pushShortcut(ctx: Context) {
        val open = Intent(ctx, BubbleActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(ctx, SHORTCUT_ID)
            .setLongLived(true)
            .setShortLabel("最適化")
            .setLongLabel("勤務表の最適化")
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setPerson(person(ctx))
            .setCategories(setOf(CATEGORY_OPTIMIZE))
            .setIntent(open)
            .setLocusId(LocusIdCompat(SHORTCUT_ID))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, info) }
    }

    private fun bubbleIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun bubbleMetadata(
        ctx: Context,
        autoExpand: Boolean,
        suppressNotification: Boolean,
    ): NotificationCompat.BubbleMetadata =
        NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent(ctx),
            IconCompat.createWithResource(ctx, R.mipmap.ic_launcher),
        )
            .setDesiredHeight(600)
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(suppressNotification)
            .build()

    private fun post(
        ctx: Context,
        message: String,
        ongoing: Boolean,
        autoExpand: Boolean,
        suppressNotification: Boolean,
        smallIcon: Int,
    ) {
        if (!canPostNotifications(ctx)) return
        ensureChannel(ctx)
        pushShortcut(ctx)
        val me = Person.Builder().setName("あなた").setKey("magi_user").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("勤務表の最適化")
            .addMessage(message, System.currentTimeMillis(), person(ctx))
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUBBLE)
            .setSmallIcon(smallIcon)
            .setContentTitle("勤務表の最適化")
            .setContentText(message)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(SHORTCUT_ID)
            .setLocusId(LocusIdCompat(SHORTCUT_ID))
            .addPerson(person(ctx))
            .setContentIntent(bubbleIntent(ctx))
            .setBubbleMetadata(bubbleMetadata(ctx, autoExpand, suppressNotification))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NID_BUBBLE, n) }
    }

    fun postProgress(ctx: Context, message: String) =
        post(
            ctx,
            message,
            ongoing = true,
            autoExpand = false,
            suppressNotification = false,
            smallIcon = android.R.drawable.stat_notify_sync,
        )

    /** 完了時。autoExpand=true で展開し結果を見せやすくする。 */
    fun postDone(ctx: Context, message: String, autoExpand: Boolean = true) =
        post(
            ctx,
            message,
            ongoing = false,
            autoExpand = autoExpand,
            suppressNotification = false,
            smallIcon = android.R.drawable.stat_sys_download_done,
        )

    fun clear(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(NID_BUBBLE) }
    }
}
