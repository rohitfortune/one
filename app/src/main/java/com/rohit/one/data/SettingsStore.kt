package com.rohit.one.data

import android.content.Context
import android.content.SharedPreferences

object SettingsStore {
    private const val PREF_NAME = "app_settings"
    private const val KEY_APP_LOCK_ENABLED = "is_app_lock_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isAppLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
    }
}
