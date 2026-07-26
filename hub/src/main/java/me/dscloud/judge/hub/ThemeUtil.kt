package me.dscloud.judge.hub

import android.app.Activity

/** 설정에서 고른 색상 테마를 적용한다. setContentView 전에 호출해야 한다. */
object ThemeUtil {
    const val KEY_THEME = "theme"

    const val THEME_BLACK = "black"
    const val THEME_BLUE = "blue"
    const val THEME_LIGHT = "light"

    fun apply(activity: Activity) {
        val theme = Prefs.get(activity, KEY_THEME).ifBlank { THEME_BLACK }
        activity.setTheme(
            when (theme) {
                THEME_BLUE -> R.style.Theme_Hub_Blue
                THEME_LIGHT -> R.style.Theme_Hub_Light
                else -> R.style.Theme_Hub_Black
            }
        )
    }
}
