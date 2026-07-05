package com.bingwa.mobile

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rechargeSettingsDataStore by preferencesDataStore(name = "recharge_settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        private val RECHARGE_SCANNER_DEFAULT_SIM = intPreferencesKey("recharge_scanner_default_sim")
        private val RECHARGE_SCANNER_SIM_PROMPT_SHOWN = booleanPreferencesKey("recharge_scanner_sim_prompt_shown")
        private val RECHARGE_SCANNER_HISTORY_JSON = stringPreferencesKey("recharge_scanner_history_json")
    }

    val rechargeScannerDefaultSim: Flow<Int> =
        context.rechargeSettingsDataStore.data.map { prefs ->
            prefs[RECHARGE_SCANNER_DEFAULT_SIM] ?: 1
        }

    val rechargeScannerSimPromptShown: Flow<Boolean> =
        context.rechargeSettingsDataStore.data.map { prefs ->
            prefs[RECHARGE_SCANNER_SIM_PROMPT_SHOWN] ?: false
        }

    suspend fun setRechargeScannerDefaultSim(slot: Int) {
        context.rechargeSettingsDataStore.edit { prefs ->
            prefs[RECHARGE_SCANNER_DEFAULT_SIM] = slot.coerceIn(1, 2)
        }
    }

    suspend fun markRechargeScannerSimPromptShown() {
        context.rechargeSettingsDataStore.edit { prefs ->
            prefs[RECHARGE_SCANNER_SIM_PROMPT_SHOWN] = true
        }
    }

    suspend fun readRechargeScannerHistoryJson(): String {
        val prefs = context.rechargeSettingsDataStore.data.first()
        return prefs[RECHARGE_SCANNER_HISTORY_JSON] ?: "[]"
    }

    suspend fun writeRechargeScannerHistoryJson(json: String) {
        context.rechargeSettingsDataStore.edit { prefs ->
            prefs[RECHARGE_SCANNER_HISTORY_JSON] = json
        }
    }

    suspend fun readAllPreferences(): Preferences = context.rechargeSettingsDataStore.data.first()
}
