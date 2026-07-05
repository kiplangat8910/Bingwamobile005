package com.bingwa.mobile

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rechargeScannerDataStore by preferencesDataStore(name = "recharge_scanner_settings")

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val rechargeScannerSim = intPreferencesKey("recharge_scanner_sim")
        val rechargeScannerSimConfigured = booleanPreferencesKey("recharge_scanner_sim_configured")
    }

    val rechargeScannerSimFlow: Flow<Int> = context.rechargeScannerDataStore.data.map { prefs ->
        prefs[Keys.rechargeScannerSim] ?: USSD_SIM_SELECTION_SLOT_1
    }

    val rechargeScannerSimConfiguredFlow: Flow<Boolean> = context.rechargeScannerDataStore.data.map { prefs ->
        prefs[Keys.rechargeScannerSimConfigured] ?: false
    }

    suspend fun getRechargeScannerSim(): Int = rechargeScannerSimFlow.first()

    suspend fun isRechargeScannerSimConfigured(): Boolean = rechargeScannerSimConfiguredFlow.first()

    suspend fun setRechargeScannerSim(simSelection: Int) {
        context.rechargeScannerDataStore.edit { prefs: MutablePreferences ->
            prefs[Keys.rechargeScannerSim] = normalizeScannerSimSelection(simSelection)
            prefs[Keys.rechargeScannerSimConfigured] = true
        }
    }

    private fun normalizeScannerSimSelection(selection: Int): Int {
        return when (selection) {
            USSD_SIM_SELECTION_SLOT_2 -> USSD_SIM_SELECTION_SLOT_2
            else -> USSD_SIM_SELECTION_SLOT_1
        }
    }
}
