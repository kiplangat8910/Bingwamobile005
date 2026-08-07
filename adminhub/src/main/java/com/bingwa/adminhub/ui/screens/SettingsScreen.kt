package com.bingwa.adminhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary
import com.bingwa.adminhub.util.SimSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val simSelector = remember { SimSelector(context) }
    var smsNotifications by remember { mutableStateOf(true) }
    var autoActivate by remember { mutableStateOf(false) }
    var networkTimeSync by remember { mutableStateOf(true) }
    var selectedSimId by remember { mutableStateOf(simSelector.getSelectedSimId()) }
    var recipientPhone by remember { mutableStateOf(simSelector.getRecipientPhone()) }
    var simExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AdminTextPrimary
        )

        SettingsCard("SMS Notifications", "Receive alerts when users buy tokens", Icons.Filled.Notifications) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable SMS alerts", color = AdminTextSecondary, fontSize = 14.sp)
                Switch(
                    checked = smsNotifications,
                    onCheckedChange = { smsNotifications = it }
                )
            }
        }

        SettingsCard("Auto-Activate", "Automatically activate tokens when purchase SMS is detected", Icons.Filled.Phone) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-activate on purchase", color = AdminTextSecondary, fontSize = 14.sp)
                Switch(
                    checked = autoActivate,
                    onCheckedChange = { autoActivate = it }
                )
            }
        }

        SettingsCard("Network Time Sync", "Use network time for accurate command codes", Icons.Filled.Security) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sync with network time", color = AdminTextSecondary, fontSize = 14.sp)
                Switch(
                    checked = networkTimeSync,
                    onCheckedChange = { networkTimeSync = it }
                )
            }
        }

        SettingsCard("SIM Selection", "Choose SIM card for sending SMS commands", Icons.Filled.Send) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = simExpanded, onExpandedChange = { simExpanded = !simExpanded }) {
                    OutlinedTextField(
                        value = simSelector.getSelectedSimName(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Send SMS via") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = simExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = simExpanded, onDismissRequest = { simExpanded = false }) {
                        simSelector.getAvailableSims().forEach { sim ->
                            DropdownMenuItem(
                                text = { Text("${sim.displayName} - ${sim.number}") },
                                onClick = {
                                    simSelector.setSelectedSimId(sim.subscriptionId)
                                    selectedSimId = sim.subscriptionId
                                    simExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = recipientPhone,
                    onValueChange = {
                        recipientPhone = it
                        simSelector.setRecipientPhone(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recipient Phone") },
                    placeholder = { Text("Phone number to send commands to") },
                    singleLine = true
                )
            }
        }
    }
}

@Composable
fun SettingsCard(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AdminAmber,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        color = AdminTextPrimary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = description,
                        color = AdminTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
            content()
        }
    }
}
