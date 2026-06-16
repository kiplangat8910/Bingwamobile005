package com.bingwa.mobile

import android.content.Context
import android.provider.Settings
import android.util.Log

object AccessibilityStatusChecker {
    private const val TAG = "AccStatus"

    fun isAccessibilityEnabled(context: Context): Boolean = try {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        enabled?.contains(context.packageName) == true
    } catch (e: Exception) { Log.e(TAG, "Error checking accessibility", e); false }

    fun getStatusMessage(context: Context): String =
        if (isAccessibilityEnabled(context)) "Accessibility Service Active" else "Accessibility Service Required"
}