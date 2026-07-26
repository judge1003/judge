package me.dscloud.judge.hub

import android.content.Context

/** 앱 설정 저장소. 설정 화면에서 수정한다. */
object Prefs {
    private const val PREFS = "service_urls"

    /** 자체 API 서버 주소 (예: http://192.168.0.50:8585) */
    const val KEY_SERVER = "server"

    /** 자체 API 서버 토큰 */
    const val KEY_TOKEN = "token"

    /** 모니터링 대시보드 주소 (Netdata 등) */
    const val KEY_STATUS = "status"

    const val KEY_BACKUP_ENABLED = "backup_enabled"
    const val KEY_LAST_BACKUP_MS = "last_backup_ms"

    fun get(context: Context, key: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "") ?: ""

    fun set(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value.trim()).apply()
    }

    fun getBool(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

    fun setBool(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }

    fun getLong(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)

    fun setLong(context: Context, key: String, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, value).apply()
    }
}
