package com.bingwa.mobile

import android.content.Context

object SettingsDataStore {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_RECHARGE_SCANNER_SIM = "recharge_scanner_sim_selection"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isRechargeScannerSimConfigured(context: Context): Boolean =
        prefs(context).contains(KEY_RECHARGE_SCANNER_SIM)

    fun getRechargeScannerSimSelection(context: Context): Int =
        normalizeUssdSimSelection(
            rawSelection = prefs(context).safeGetInt(
                KEY_RECHARGE_SCANNER_SIM,
                USSD_SIM_SELECTION_SLOT_1
            ),
            sims = getAvailableSims(context)
        )

    fun setRechargeScannerSimSelection(context: Context, selection: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_RECHARGE_SCANNER_SIM, selection)
            .apply()
    }
}
