package com.magi.app.v6.engine

/**
 * アプリ版情報。BuildConfig が無い単体テスト／ホストでは unknown。
 * ログ・ベンチに必ず載せて「どのバイナリの結果か」を後から判別できるようにする。
 */
object AppVersion {
    data class Info(
        val versionName: String,
        val versionCode: Long,
        val rebuildEngine: Boolean?,
        val applicationId: String?,
    ) {
        /** ログ1行用（空白なし） */
        fun compact(): String = buildString {
            append(versionName)
            append("(").append(versionCode).append(")")
            if (rebuildEngine == true) append("+rebuild")
            else if (rebuildEngine == false) append("+upstream")
        }

        fun logLine(): String = buildString {
            append("version=").append(versionName)
            append(" versionCode=").append(versionCode)
            rebuildEngine?.let { append(" rebuildEngine=").append(it) }
            applicationId?.let { append(" applicationId=").append(it) }
        }
    }

    val info: Info by lazy { resolve() }

    private fun resolve(): Info {
        return runCatching {
            val cl = Class.forName("com.magi.app.BuildConfig")
            val name = runCatching { cl.getField("VERSION_NAME").get(null) as String }.getOrDefault("unknown")
            val code = runCatching {
                when (val v = cl.getField("VERSION_CODE").get(null)) {
                    is Int -> v.toLong()
                    is Long -> v
                    else -> v.toString().toLongOrNull() ?: 0L
                }
            }.getOrDefault(0L)
            val rebuild = runCatching { cl.getField("REBUILD_ENGINE").getBoolean(null) }.getOrNull()
            val appId = runCatching { cl.getField("APPLICATION_ID").get(null) as String }.getOrNull()
            Info(name, code, rebuild, appId)
        }.getOrElse {
            Info(versionName = "unknown", versionCode = 0L, rebuildEngine = null, applicationId = null)
        }
    }
}
