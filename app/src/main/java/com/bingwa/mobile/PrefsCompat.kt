package com.bingwa.mobile

import android.content.SharedPreferences

internal fun SharedPreferences.safeGetString(key: String, defaultValue: String? = null): String? =
    try {
        getString(key, defaultValue)
    } catch (_: ClassCastException) {
        edit().remove(key).apply()
        defaultValue
    }

internal fun SharedPreferences.safeGetBoolean(key: String, defaultValue: Boolean = false): Boolean =
    try {
        getBoolean(key, defaultValue)
    } catch (_: ClassCastException) {
        edit().remove(key).apply()
        defaultValue
    }

internal fun SharedPreferences.safeGetInt(key: String, defaultValue: Int = 0): Int =
    try {
        getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        edit().remove(key).apply()
        defaultValue
    }

internal fun SharedPreferences.safeGetLong(key: String, defaultValue: Long = 0L): Long =
    try {
        getLong(key, defaultValue)
    } catch (_: ClassCastException) {
        edit().remove(key).apply()
        defaultValue
    }
