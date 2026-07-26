package me.dscloud.judge.hub

import android.content.Context

/** 각 서비스 주소 저장소. 설정 화면에서 수정한다. */
object Prefs {
    private const val PREFS = "service_urls"

    const val KEY_PHOTOS = "photos"
    const val KEY_FILES = "files"
    const val KEY_STATUS = "status"

    fun get(context: Context, key: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "") ?: ""

    fun set(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value.trim())
            .apply()
    }
}
