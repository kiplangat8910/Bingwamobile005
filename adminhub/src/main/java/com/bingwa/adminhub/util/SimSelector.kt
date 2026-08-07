package com.bingwa.adminhub.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class SimSelector(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("adminhub_settings", Context.MODE_PRIVATE)
    private var availableSims: List<SubscriptionInfo> = emptyList()

    init {
        loadAvailableSims()
    }

    fun loadAvailableSims() {
        availableSims = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                sm.activeSubscriptionInfoList ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SIMs", e)
            emptyList()
        }
    }

    fun getAvailableSims(): List<SubscriptionInfo> = availableSims

    fun getSelectedSimId(): Int {
        return prefs.getInt("admin_sms_sim_id", -1).takeIf { it != -1 } ?: getDefaultSimId()
    }

    fun getSelectedSimName(): String {
        val simId = getSelectedSimId()
        return availableSims.find { it.subscriptionId == simId }?.let {
            it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}"
        } ?: "Default"
    }

    fun setSelectedSimId(simId: Int) {
        prefs.edit().putInt("admin_sms_sim_id", simId).apply()
    }

    fun getRecipientPhone(): String {
        val saved = prefs.getString("admin_recipient_phone", "") ?: ""
        return saved.ifBlank { getDevicePhoneNumber() }
    }

    fun setRecipientPhone(phone: String) {
        prefs.edit().putString("admin_recipient_phone", phone).apply()
    }

    fun getDefaultSimId(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val active = sm.activeSubscriptionInfoList ?: return -1
                if (active.size == 1) {
                    active[0].subscriptionId
                } else {
                    -1
                }
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun getDevicePhoneNumber(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val active = sm.activeSubscriptionInfoList ?: return ""
                active.firstOrNull()?.number?.takeIf { it.isNotBlank() } ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "SimSelector"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSelector.RenderSimSelector() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var simExpanded by remember { mutableStateOf(false) }
    val simSelector = remember(this) { this }

    ExposedDropdownMenuBox(expanded = simExpanded, onExpandedChange = { simExpanded = !simExpanded }) {
        androidx.compose.material3.OutlinedTextField(
            value = simSelector.getSelectedSimName(),
            onValueChange = {},
            readOnly = true,
            label = { androidx.compose.material3.Text("Send SMS via") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = simExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = simExpanded, onDismissRequest = { simExpanded = false }) {
            simSelector.getAvailableSims().forEach { sim ->
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text("${sim.displayName} - ${sim.number}") },
                    onClick = {
                        simSelector.setSelectedSimId(sim.subscriptionId)
                        simExpanded = false
                    }
                )
            }
        }
    }
}
