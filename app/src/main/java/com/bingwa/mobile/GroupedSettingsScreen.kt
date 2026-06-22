package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

private sealed class SettingsDest {
    data object Home : SettingsDest()
    data object Sim : SettingsDest()
    data object Relay : SettingsDest()
    data object Remote : SettingsDest()
    data object Notifications : SettingsDest()
    data object Automation : SettingsDest()
    data object Contacts : SettingsDest()
    data object Appearance : SettingsDest()
    data object Offers : SettingsDest()
    data object Alerts : SettingsDest()
    data object Transactions : SettingsDest()
    data object Diagnostics : SettingsDest()
}

@Composable
fun GroupedSettingsScreen() {
    var dest by remember { mutableStateOf<SettingsDest>(SettingsDest.Home) }
    BackHandler(enabled = dest != SettingsDest.Home) { dest = SettingsDest.Home }

    when (dest) {
        SettingsDest.Home -> SettingsHome(
            onOpenSim = { dest = SettingsDest.Sim },
            onOpenRelay = { dest = SettingsDest.Relay },
            onOpenRemote = { dest = SettingsDest.Remote },
            onOpenNotifications = { dest = SettingsDest.Notifications },
            onOpenAutomation = { dest = SettingsDest.Automation },
            onOpenContacts = { dest = SettingsDest.Contacts },
            onOpenAppearance = { dest = SettingsDest.Appearance },
            onOpenOffers = { dest = SettingsDest.Offers },
            onOpenAlerts = { dest = SettingsDest.Alerts },
            onOpenTransactions = { dest = SettingsDest.Transactions },
            onOpenDiagnostics = { dest = SettingsDest.Diagnostics }
        )
        SettingsDest.Sim -> SimSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Relay -> RelaySettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Remote -> RemoteControlSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Notifications -> CustomerNotificationSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Automation -> AutomationSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Contacts -> ContactsScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Appearance -> AppearanceSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Offers -> OffersScreen(onBack = { dest = SettingsDest.Home })
        SettingsDest.Alerts -> AdminAlertsSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Transactions -> TransactionSettings(onBack = { dest = SettingsDest.Home })
        SettingsDest.Diagnostics -> DeviceDiagnosticsScreen(onBack = { dest = SettingsDest.Home })
    }
}

@Composable
private fun SettingsHome(
    onOpenSim: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenOffers: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        PageHeader("Settings", "Organized access to history, execution, automation, and support")
        Column(
            Modifier.padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            SettingsGroup("History & Offers") {
                LinkRow(
                    Icons.Rounded.Schedule,
                    "Transaction History",
                    "Review payments, statuses, summaries, and cleanup controls",
                    C.green,
                    onOpenTransactions
                )
                GroupDivider()
                LinkRow(Icons.Rounded.Tag, "Offers & USSD Codes", "Manage available bundles and execution codes", C.cyan, onOpenOffers)
                GroupDivider()
                LinkRow(Icons.Rounded.Contacts, "Contacts", "Keep saved customer names clean and easy to search", C.blue, onOpenContacts)
            }

            SettingsGroup("Execution Setup") {
                LinkRow(Icons.Rounded.SimCard, "SIM Settings", "Choose SIMs for USSD, customer notifications, and admin replies", C.cyan, onOpenSim)
                GroupDivider()
                LinkRow(Icons.Rounded.SyncAlt, "Relay (Two‑Phone Mode)", "Configure SMS or hotspot relay between two phones", C.blue, onOpenRelay)
                GroupDivider()
                LinkRow(Icons.Rounded.PhoneAndroid, "Remote Control", "Manage admin phone, prefix, PIN, and remote commands", C.purple, onOpenRemote)
            }

            SettingsGroup("Automation & Alerts") {
                LinkRow(Icons.Rounded.SmartToy, "Automation Settings", "Control automation, retries, and auto-save behavior", C.cyan, onOpenAutomation)
                GroupDivider()
                LinkRow(Icons.Rounded.NotificationsActive, "Customer Notifications", "Edit success, pending, and failure templates", C.green, onOpenNotifications)
                GroupDivider()
                LinkRow(Icons.Rounded.Warning, "Admin Alerts", "Low airtime, low tokens, and low battery alerts", C.red, onOpenAlerts)
            }

            SettingsGroup("Appearance & Support") {
                LinkRow(Icons.Rounded.DarkMode, "Appearance", "Adjust theme and system colors", C.orange, onOpenAppearance)
                GroupDivider()
                LinkRow(Icons.Rounded.Info, "Setup Doctor", "Check permissions, compatibility, battery rules, and alarms", C.green, onOpenDiagnostics)
                GroupDivider()
                LinkRow(Icons.Rounded.AccessibilityNew, "Accessibility", "Open your phone Accessibility settings", C.cyan) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        Spacer(Modifier.height(UiDimens.Spacing2xl))
    }
}

@Composable
fun SettingsTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = UiDimens.ScreenPaddingHorizontal, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.cardHi.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.ArrowBack, null, tint = C.t1)
                }
            }
            Column(Modifier.padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SimSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(ctx)
    var ussdSimId by remember { mutableIntStateOf(prefs.safeGetInt("selected_sim_id", -1)) }
    var notifySimId by remember { mutableIntStateOf(prefs.safeGetInt("notify_sim_id", -1)) }
    var adminSmsSimId by remember { mutableIntStateOf(prefs.safeGetInt("admin_sms_sim_id", -1)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("SIM Settings", "Choose SIMs for USSD, customer notifications, and admin replies", onBack)
        Column(
            Modifier.padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            SettingsGroup("SIM Settings") {
                SimPickerRow("USSD Execution SIM", "SIM used for USSD calls", sims, ussdSimId) { v ->
                    ussdSimId = v
                    prefs.edit().putInt("selected_sim_id", v).apply()
                }
                GroupDivider()
                SimPickerRow("Customer Notification SIM", "SIM used to send customer notifications", sims, notifySimId) { v ->
                    notifySimId = v
                    prefs.edit().putInt("notify_sim_id", v).apply()
                }
                GroupDivider()
                SimPickerRow("Admin Reply SIM", "SIM used to reply to admin SMS commands", sims, adminSmsSimId) { v ->
                    adminSmsSimId = v
                    prefs.edit().putInt("admin_sms_sim_id", v).apply()
                }
            }
        }
        Spacer(Modifier.height(UiDimens.Spacing2xl))
    }
}

@Composable
private fun RelaySettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var twoPhoneEnabled by remember { mutableStateOf(prefs.safeGetBoolean("two_phone_enabled", false)) }
    var twoPhoneRole by remember { mutableStateOf(prefs.safeGetString("two_phone_role", "PRIMARY") ?: "PRIMARY") }
    var relayMethod by remember { mutableStateOf(prefs.safeGetString("relay_method", "SMS") ?: "SMS") }
    var pairedPhone by remember { mutableStateOf(prefs.safeGetString("paired_phone", "") ?: "") }
    var relayIp by remember { mutableStateOf(prefs.safeGetString("relay_ip", "") ?: "") }
    var relayIpAuto by remember { mutableStateOf(prefs.safeGetBoolean("relay_ip_auto", false)) }
    var relayPrefix by remember { mutableStateOf(prefs.safeGetString("relay_prefix", prefs.safeGetString("sms_prefix", "BINGWA")) ?: (prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA")) }
    var relayPin by remember { mutableStateOf(prefs.safeGetString("relay_pin", prefs.safeGetString("sms_pin", "")) ?: (prefs.safeGetString("sms_pin", "") ?: "")) }
    var relaySendResults by remember { mutableStateOf(prefs.safeGetBoolean("relay_send_results", true)) }
    var roleExp by remember { mutableStateOf(false) }
    var methodExp by remember { mutableStateOf(false) }
    var showConnectQr by remember { mutableStateOf(false) }
    var connectQrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var connectQrSummary by remember { mutableStateOf("") }

    fun persistRelaySettings() {
        prefs.edit()
            .putBoolean("two_phone_enabled", twoPhoneEnabled)
            .putString("two_phone_role", twoPhoneRole.trim().uppercase())
            .putString("relay_method", relayMethod.trim().uppercase())
            .putString("paired_phone", pairedPhone.trim())
            .putString("relay_ip", relayIp.trim())
            .putBoolean("relay_ip_auto", relayIpAuto)
            .putString("relay_prefix", relayPrefix.trim().uppercase())
            .putString("relay_pin", relayPin.trim())
            .putBoolean("relay_send_results", relaySendResults)
            .apply()
        RelayManager.stopRelayHotspotService(ctx)
        val cfg = RelayManager.load(ctx)
        if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") {
            RelayManager.startRelayHotspotService(ctx)
        } else {
            RelayManager.startHotspotMonitor(ctx)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isBlank()) return@rememberLauncherForActivityResult
        val payload = RelayQrCodec.decode(raw)
        if (payload == null) {
            Toast.makeText(ctx, "That QR code is not a relay hotspot code", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        twoPhoneEnabled = true
        twoPhoneRole = "PRIMARY"
        relayMethod = "HOTSPOT"
        pairedPhone = payload.relayPhone
        relayIp = payload.relayIp
        relayIpAuto = payload.relayIp.isBlank()
        relayPrefix = payload.relayPrefix
        relayPin = payload.relayPin
        relaySendResults = payload.sendResultsSms
        persistRelaySettings()
        vib(ctx)
        Toast.makeText(ctx, "Relay hotspot settings imported from QR", Toast.LENGTH_SHORT).show()
    }

    val openScanner = launch@{
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(ctx, "This phone does not have a usable camera for QR scanning", Toast.LENGTH_SHORT).show()
            return@launch
        }
        val scanOptions = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the relay connect QR")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        runCatching { scanLauncher.launch(scanOptions) }
            .onFailure { error ->
                Log.e("GroupedSettings", "Unable to launch QR scanner", error)
                Toast.makeText(ctx, "Unable to open the QR scanner on this phone", Toast.LENGTH_SHORT).show()
            }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openScanner()
        else Toast.makeText(ctx, "Camera permission is needed to scan the relay QR", Toast.LENGTH_SHORT).show()
    }

    val qrBitmap = connectQrBitmap
    if (showConnectQr && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showConnectQr = false },
            confirmButton = {
                TextButton(onClick = { showConnectQr = false }) { Text("Close", color = C.cyan) }
            },
            title = { Text("Scan To Connect", color = C.t1) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("On the PRIMARY phone, open Relay settings and tap Scan Connect QR.", color = C.t2, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color.White) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Relay connect QR",
                            modifier = Modifier.padding(14.dp).size(240.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(connectQrSummary, color = C.t2, fontSize = 11.sp)
                }
            },
            containerColor = C.cardHi,
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )
    }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Relay (Two‑Phone Mode)", "SMS or hotspot relay between phones", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Two‑Phone Mode") {
                ToggleRow(Icons.Rounded.SyncAlt, "Enable Two‑Phone Mode", "Forward selected offers to the Relay phone", twoPhoneEnabled) {
                    twoPhoneEnabled = it
                    prefs.edit().putBoolean("two_phone_enabled", it).apply()
                    if (!it) RelayManager.stopRelayHotspotService(ctx)
                    val cfg = RelayManager.load(ctx)
                    if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") RelayManager.startRelayHotspotService(ctx)
                }
                AnimatedVisibility(visible = twoPhoneEnabled) {
                    Column {
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Devices)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("This Phone Role", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("PRIMARY receives M‑PESA; RELAY executes selected offers", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { roleExp = true }) { Text(twoPhoneRole.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = roleExp, onDismissRequest = { roleExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("PRIMARY", "RELAY").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == twoPhoneRole.uppercase()) C.cyan else C.t1) },
                                            onClick = { twoPhoneRole = opt; prefs.edit().putString("two_phone_role", opt).apply(); roleExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Router)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Relay Method", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("SMS works anywhere; Hotspot works offline on local network", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { methodExp = true }) { Text(relayMethod.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = methodExp, onDismissRequest = { methodExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("SMS", "HOTSPOT").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == relayMethod.uppercase()) C.cyan else C.t1) },
                                            onClick = { relayMethod = opt; prefs.edit().putString("relay_method", opt).apply(); methodExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Paired Phone Number", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("PRIMARY → RELAY number, and RELAY → PRIMARY number", color = C.t2, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pairedPhone,
                                onValueChange = { pairedPhone = it.trim() },
                                placeholder = { Text("e.g. 0712345678", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                        }
                        AnimatedVisibility(visible = relayMethod.uppercase() == "HOTSPOT") {
                            Column {
                                GroupDivider()
                                ToggleRow(Icons.Rounded.AutoFixHigh, "Auto‑Detect Relay IP", "Connect using current Wi‑Fi gateway (hotspot)", relayIpAuto) {
                                    relayIpAuto = it
                                    prefs.edit().putBoolean("relay_ip_auto", it).apply()
                                }
                                GroupDivider()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SettingsRowIcon(Icons.Rounded.WifiTethering); Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Relay Hotspot IP", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text("IP address of the RELAY phone on the hotspot network", color = C.t2, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = relayIp,
                                        onValueChange = { relayIp = it.trim() },
                                        placeholder = { Text(if (relayIpAuto) "Auto-detected" else "e.g. 192.168.43.1", color = C.t3) },
                                        leadingIcon = { Icon(Icons.Rounded.Router, null, tint = C.t2) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = fieldColors(),
                                        enabled = !relayIpAuto,
                                        singleLine = true
                                    )
                                }
                                GroupDivider()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SettingsRowIcon(Icons.Rounded.Router); Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Quick Connect QR", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text(
                                                if (twoPhoneRole.uppercase() == "RELAY") "Show a QR that the PRIMARY phone can scan to connect quickly"
                                                else "Scan the RELAY phone QR to fill the hotspot settings automatically",
                                                color = C.t2,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            if (twoPhoneRole.uppercase() == "RELAY") {
                                                val payload = RelayQrCodec.createPayload(
                                                    pairedPhone = pairedPhone,
                                                    relayPrefix = relayPrefix,
                                                    relayPin = relayPin,
                                                    relayIp = relayIp,
                                                    sendResultsSms = relaySendResults
                                                )
                                                if (payload.relayIp.isBlank()) {
                                                    Toast.makeText(ctx, "Turn on the relay hotspot or set the relay IP first", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    connectQrBitmap = RelayQrCodec.createBitmap(RelayQrCodec.encode(payload))
                                                    connectQrSummary = "Relay IP ${payload.relayIp}${if (payload.relayPhone.isNotBlank()) " • Phone ${payload.relayPhone}" else ""}"
                                                    showConnectQr = true
                                                }
                                            } else {
                                                val hasCameraPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                                if (hasCameraPermission) openScanner() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = C.blue)
                                    ) {
                                        Icon(
                                            if (twoPhoneRole.uppercase() == "RELAY") Icons.Rounded.Router else Icons.Rounded.Devices,
                                            null,
                                            tint = C.bg,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (twoPhoneRole.uppercase() == "RELAY") "Show Connect QR" else "Scan Connect QR",
                                            color = C.bg,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Prefix used when PRIMARY forwards via SMS", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = relayPrefix, onValueChange = { relayPrefix = it.trim().uppercase() }, placeholder = { Text("BINGWA", color = C.t3) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true)
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay PIN (optional)", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Must match on both phones (recommended)", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = relayPin,
                                onValueChange = { relayPin = it.filter { ch -> ch.isDigit() }.take(6) },
                                placeholder = { Text("4–6 digits", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                            )
                        }
                        GroupDivider()
                        ToggleRow(Icons.Rounded.Sms, "Send Relay Results by SMS", "RELAY sends execution result back to PRIMARY", relaySendResults) {
                            relaySendResults = it
                            prefs.edit().putBoolean("relay_send_results", it).apply()
                        }
                        GroupDivider()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Button(
                                onClick = {
                                    persistRelaySettings()
                                    vib(ctx)
                                    Toast.makeText(ctx, "Two‑Phone settings saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Save Two‑Phone Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun RemoteControlSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var remoteEnabled by remember { mutableStateOf(prefs.safeGetBoolean("remote_enabled", false)) }
    var adminPhone by remember { mutableStateOf(prefs.safeGetString("admin_phone", "") ?: "") }
    var adminPrefix by remember { mutableStateOf(prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA") }
    var adminPin by remember { mutableStateOf(prefs.safeGetString("sms_pin", "") ?: "") }
    var pinVisible by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Remote Control", "Admin SMS commands and security", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Remote Admin") {
                ToggleRow(Icons.Rounded.PhoneAndroid, "Enable Remote Control", "Allow SMS commands from admin phone", remoteEnabled) {
                    remoteEnabled = it
                    prefs.edit().putBoolean("remote_enabled", it).apply()
                }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val pinHint = if (adminPin.trim().isNotEmpty()) "*".repeat((adminPin.trim().length - 2).coerceAtLeast(0)) + adminPin.trim().takeLast(2) else ""
                    Surface(shape = RoundedCornerShape(14.dp), color = C.w04, border = BorderStroke(1.dp, C.border)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(C.cyanDim)
                                    .border(1.dp, C.cyan.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Info, null, tint = C.cyan, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Command Format", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text("${adminPrefix.trim().uppercase()} ${if (pinHint.isNotEmpty()) "$pinHint " else ""}STATUS", color = C.t2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                        Column { Text("Admin Phone", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Number that can send remote commands", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPhone,
                        onValueChange = { adminPhone = it.trim() },
                        placeholder = { Text("e.g. 0712345678", color = C.t3) },
                        leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                        Column { Text("Command Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Word that must start every SMS command", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPrefix,
                        onValueChange = { adminPrefix = it.trim().uppercase() },
                        placeholder = { Text("BINGWA", color = C.t3) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true
                    )
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                        Column { Text("Security PIN", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("4–6 digit PIN to authorise commands (optional)", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPin,
                        onValueChange = { adminPin = it.filter { ch -> ch.isDigit() }.take(6) },
                        placeholder = { Text("4–6 digits", color = C.t3) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                        trailingIcon = {
                            IconButton(onClick = { pinVisible = !pinVisible }) {
                                Icon(if (pinVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = C.t2)
                            }
                        },
                        visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Button(
                        onClick = {
                            prefs.edit()
                                .putString("admin_phone", adminPhone.trim())
                                .putString("sms_prefix", adminPrefix.trim().uppercase())
                                .putString("sms_pin", adminPin.trim())
                                .apply()
                            vib(ctx)
                            Toast.makeText(ctx, "Admin settings saved", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                    ) {
                        Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save Admin Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun CustomerNotificationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var notifySuccess by remember { mutableStateOf(prefs.safeGetBoolean("notify_success", true)) }
    var notifyFailed by remember { mutableStateOf(prefs.safeGetBoolean("notify_failed", true)) }
    var tplSuccess by remember { mutableStateOf(prefs.safeGetString("sms_tpl_success", DEFAULT_TPL_SUCCESS) ?: DEFAULT_TPL_SUCCESS) }
    var tplFailed by remember { mutableStateOf(prefs.safeGetString("sms_tpl_failed", DEFAULT_TPL_FAILED) ?: DEFAULT_TPL_FAILED) }
    var tplPending by remember { mutableStateOf(prefs.safeGetString("sms_tpl_pending", DEFAULT_TPL_PENDING) ?: DEFAULT_TPL_PENDING) }
    var tplLimitNotice by remember { mutableStateOf(prefs.safeGetString("sms_tpl_limit_notice", DEFAULT_TPL_LIMIT_NOTICE) ?: DEFAULT_TPL_LIMIT_NOTICE) }
    var notifyPending by remember { mutableStateOf(prefs.safeGetBoolean("notify_pending", true)) }
    var notifyLimitNotice by remember { mutableStateOf(prefs.safeGetBoolean("notify_limit_notice", true)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Customer Notifications", "SMS to customers for each outcome", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Success") {
                ToggleRow(Icons.Rounded.CheckCircle, "Send on Success", "SMS customer when bundle is delivered", notifySuccess) {
                    notifySuccess = it
                    prefs.edit().putBoolean("notify_success", it).apply()
                }
                AnimatedVisibility(visible = notifySuccess) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Success Message Template", tplSuccess, C.green, SMS_TAGS) { v ->
                            tplSuccess = v
                            prefs.edit().putString("sms_tpl_success", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplSuccess, C.green)
                    }
                }
            }

            SettingsGroup("Daily Limit / Pending") {
                ToggleRow(Icons.Rounded.Schedule, "Send on Daily Limit", "SMS customer when offer already used today", notifyPending) {
                    notifyPending = it
                    prefs.edit().putBoolean("notify_pending", it).apply()
                }
                AnimatedVisibility(visible = notifyPending) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Pending / Daily Limit Template", tplPending, C.amber, SMS_TAGS) { v ->
                            tplPending = v
                            prefs.edit().putString("sms_tpl_pending", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplPending, C.amber)
                    }
                }
            }

            SettingsGroup("Daily Limit / Notice") {
                ToggleRow(Icons.Rounded.Info, "Send Alternative Notice", "SMS customer when Notice mode is used instead of auto-queue", notifyLimitNotice) {
                    notifyLimitNotice = it
                    prefs.edit().putBoolean("notify_limit_notice", it).apply()
                }
                AnimatedVisibility(visible = notifyLimitNotice) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Alternative Number / Try Tomorrow Template", tplLimitNotice, C.blue, SMS_TAGS) { v ->
                            tplLimitNotice = v
                            prefs.edit().putString("sms_tpl_limit_notice", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplLimitNotice, C.blue)
                    }
                }
            }

            SettingsGroup("Failure") {
                ToggleRow(Icons.Rounded.Warning, "Send on Failure", "SMS customer when bundle fails", notifyFailed) {
                    notifyFailed = it
                    prefs.edit().putBoolean("notify_failed", it).apply()
                }
                AnimatedVisibility(visible = notifyFailed) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Failure Message Template", tplFailed, C.red, SMS_TAGS) { v ->
                            tplFailed = v
                            prefs.edit().putString("sms_tpl_failed", v).apply()
                        }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplFailed, C.red)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AutomationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var autoEnabled by remember { mutableStateOf(prefs.safeGetBoolean("automation_enabled", true)) }
    var autoRetry by remember { mutableStateOf(prefs.safeGetBoolean("auto_retry", false)) }
    var autoContacts by remember { mutableStateOf(prefs.safeGetBoolean("auto_save_contacts", true)) }
    val enabledOffers = remember { OfferRepository.load(ctx).filter { it.enabled } }
    var dailyLimitMode by remember { mutableStateOf(prefs.safeGetString("daily_limit_mode", DailyLimitPolicy.MODE_QUEUE_TOMORROW) ?: DailyLimitPolicy.MODE_QUEUE_TOMORROW) }
    var dailyLimitModeExp by remember { mutableStateOf(false) }
    var repeatNoticeEnabled by remember { mutableStateOf(prefs.safeGetBoolean("daily_limit_repeat_notice_enabled", false)) }
    var fallbackEnabled by remember { mutableStateOf(prefs.safeGetBoolean("daily_limit_fallback_enabled", false)) }
    var fallbackOfferId by remember { mutableIntStateOf(prefs.safeGetInt("daily_limit_fallback_offer_id", -1)) }
    var fallbackOfferExp by remember { mutableStateOf(false) }
    var fallbackMinPrice by remember { mutableStateOf(prefs.safeGetInt("daily_limit_fallback_min_price", 0).toString()) }
    val selectedFallbackOffer = enabledOffers.firstOrNull { it.id == fallbackOfferId }

    fun saveDailyLimitMode(mode: String) {
        dailyLimitMode = mode
        val edit = prefs.edit().putString("daily_limit_mode", mode)
        if (mode == DailyLimitPolicy.MODE_QUEUE_TOMORROW && repeatNoticeEnabled) {
            repeatNoticeEnabled = false
            edit.putBoolean("daily_limit_repeat_notice_enabled", false)
        }
        edit.apply()
    }

    fun saveRepeatNotice(enabled: Boolean) {
        repeatNoticeEnabled = enabled
        val edit = prefs.edit().putBoolean("daily_limit_repeat_notice_enabled", enabled)
        if (enabled) {
            dailyLimitMode = DailyLimitPolicy.MODE_NOTICE_ONLY
            edit.putString("daily_limit_mode", DailyLimitPolicy.MODE_NOTICE_ONLY)
        }
        edit.apply()
    }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Automation", "Background processing behavior", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Automation") {
                ToggleRow(Icons.Rounded.SmartToy, "Enable Automation", "Auto-run bundles on payment", autoEnabled) {
                    autoEnabled = it
                    prefs.edit().putBoolean("automation_enabled", it).apply()
                    if (!it) ctx.stopService(android.content.Intent(ctx, BalanceChecker::class.java))
                    else ServiceLauncher.startBalanceChecker(ctx)
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Autorenew, "Auto-Retry on Failure", "Retry failed USSD up to 3 times", autoRetry) {
                    autoRetry = it
                    prefs.edit().putBoolean("auto_retry", it).apply()
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.PersonAdd, "Auto-Save Contacts", "Save payer numbers from M-PESA SMS", autoContacts) {
                    autoContacts = it
                    prefs.edit().putBoolean("auto_save_contacts", it).apply()
                }
            }

            SettingsGroup("Daily Limit Handling") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.Schedule)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("If number already got today's offer", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Choose one mode. Turning on repeat notice disables auto-queue tomorrow.", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        val label = if (dailyLimitMode == DailyLimitPolicy.MODE_NOTICE_ONLY) "NOTICE" else "QUEUE"
                        TextButton(onClick = { dailyLimitModeExp = true }) { Text(label, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(
                            expanded = dailyLimitModeExp,
                            onDismissRequest = { dailyLimitModeExp = false },
                            modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("QUEUE TOMORROW", color = if (dailyLimitMode == DailyLimitPolicy.MODE_QUEUE_TOMORROW) C.cyan else C.t1) },
                                onClick = {
                                    saveDailyLimitMode(DailyLimitPolicy.MODE_QUEUE_TOMORROW)
                                    dailyLimitModeExp = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("NOTICE ONLY", color = if (dailyLimitMode == DailyLimitPolicy.MODE_NOTICE_ONLY) C.cyan else C.t1) },
                                onClick = {
                                    saveDailyLimitMode(DailyLimitPolicy.MODE_NOTICE_ONLY)
                                    dailyLimitModeExp = false
                                }
                            )
                        }
                    }
                }
                GroupDivider()
                ToggleRow(
                    Icons.Rounded.Info,
                    "Repeat Same-Number Notice",
                    "If the same blocked number pays again today, send a notice instead of starting another dispatch",
                    repeatNoticeEnabled
                ) {
                    saveRepeatNotice(it)
                }
                AnimatedVisibility(visible = repeatNoticeEnabled) {
                    Column {
                        GroupDivider()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = C.w04, border = BorderStroke(1.dp, C.border)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text("Repeat Notice Active", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Same-number repeat payments are held and the customer is told to provide another number or confirm tomorrow morning dispatch. Auto-queue tomorrow is switched off while this stays enabled.",
                                        color = C.t2,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Autorenew, "Use Fallback Offer", "If today's bundle is blocked, try another configured offer first", fallbackEnabled) {
                    fallbackEnabled = it
                    prefs.edit().putBoolean("daily_limit_fallback_enabled", it).apply()
                }
                AnimatedVisibility(visible = fallbackEnabled) {
                    Column {
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Tag)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Fallback Offer", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Select the configured offer to try when the original one is limited, including unlimited offers", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { fallbackOfferExp = true }) {
                                    Text(selectedFallbackOffer?.name ?: "SELECT", color = C.cyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(
                                    expanded = fallbackOfferExp,
                                    onDismissRequest = { fallbackOfferExp = false },
                                    modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))
                                ) {
                                    enabledOffers.forEach { offer ->
                                        DropdownMenuItem(
                                            text = { Text("${offer.name} • KES ${offer.price}", color = if (offer.id == fallbackOfferId) C.cyan else C.t1) },
                                            onClick = {
                                                fallbackOfferId = offer.id
                                                prefs.edit().putInt("daily_limit_fallback_offer_id", offer.id).apply()
                                                fallbackOfferExp = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Fallback Condition: Minimum Price", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Only use the fallback when the original purchase amount is this value or higher", color = C.t2, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = fallbackMinPrice,
                                onValueChange = {
                                    val filtered = it.filter(Char::isDigit)
                                    fallbackMinPrice = filtered
                                    prefs.edit().putInt("daily_limit_fallback_min_price", filtered.toIntOrNull() ?: 0).apply()
                                },
                                placeholder = { Text("0 = any price", color = C.t3) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AppearanceSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var themeMode by remember { mutableStateOf((prefs.safeGetString("theme_mode", AppTheme.mode.name) ?: AppTheme.mode.name).uppercase()) }
    var themeExp by remember { mutableStateOf(false) }
    var themeAccent by remember { mutableStateOf(themeAccentFromName(prefs.safeGetString("theme_accent", AppTheme.accent.name))) }
    var accentExp by remember { mutableStateOf(false) }
    var useDynamicColors by remember { mutableStateOf(prefs.safeGetBoolean("use_dynamic_colors", AppTheme.useDynamicColors)) }
    val dynSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Appearance", "Theme and system colors", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Appearance") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.DarkMode)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("System, Dark, or Light", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(onClick = { themeExp = true }) { Text(themeMode, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(
                            expanded = themeExp,
                            onDismissRequest = { themeExp = false },
                            modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))
                        ) {
                            listOf("SYSTEM", "DARK", "LIGHT").forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt, color = if (opt == themeMode) C.cyan else C.t1) },
                                    onClick = {
                                        themeMode = opt
                                        prefs.edit().putString("theme_mode", opt).apply()
                                        AppTheme.mode = runCatching { ThemeMode.valueOf(opt) }.getOrDefault(ThemeMode.SYSTEM)
                                        themeExp = false
                                    }
                                )
                            }
                        }
                    }
                }
                GroupDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.Palette)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Main UI Color", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Choose the dominant app color when system colors are off", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(onClick = { accentExp = true }) { Text(themeAccentLabel(themeAccent), color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(
                            expanded = accentExp,
                            onDismissRequest = { accentExp = false },
                            modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))
                        ) {
                            themeAccentOptions().forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(themeAccentLabel(opt), color = if (opt == themeAccent) C.cyan else C.t1) },
                                    onClick = {
                                        themeAccent = opt
                                        prefs.edit().putString("theme_accent", opt.name).apply()
                                        AppTheme.accent = opt
                                        accentExp = false
                                    }
                                )
                            }
                        }
                    }
                }
                GroupDivider()
                ToggleRow(
                    Icons.Rounded.Palette,
                    "Use System Colors",
                    if (dynSupported) "Uses your phone colors (Android 12+)" else "Requires Android 12+",
                    checked = useDynamicColors && dynSupported
                ) {
                    val v = it && dynSupported
                    useDynamicColors = v
                    prefs.edit().putBoolean("use_dynamic_colors", v).apply()
                    AppTheme.useDynamicColors = v
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun AdminAlertsSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var alertLowBalance by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_balance", false)) }
    var alertLowTokens by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_tokens", false)) }
    var alertFailedTx by remember { mutableStateOf(prefs.safeGetBoolean("alert_failed_tx", false)) }
    var lowBalanceLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_balance_limit", 50)) }
    var lowTokenLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_token_limit", 5)) }
    var alertLowBattery by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_battery", false)) }
    var lowBatteryLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_battery_limit", 20)) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        SettingsTopBar("Admin Alerts", "Notifications sent to admin", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup("Admin Alerts") {
                ToggleRow(Icons.Rounded.Warning, "Low Airtime Alert", "SMS when balance drops below limit", alertLowBalance) {
                    alertLowBalance = it
                    prefs.edit().putBoolean("alert_low_balance", it).apply()
                }
                AnimatedVisibility(visible = alertLowBalance) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (KES)", lowBalanceLimit) { v -> lowBalanceLimit = v; prefs.edit().putInt("low_balance_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Circle, "Low Tokens Alert", "SMS when tokens fall below limit", alertLowTokens) {
                    alertLowTokens = it
                    prefs.edit().putBoolean("alert_low_tokens", it).apply()
                }
                AnimatedVisibility(visible = alertLowTokens) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit", lowTokenLimit) { v -> lowTokenLimit = v; prefs.edit().putInt("low_token_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Error, "Failed Transaction Alert", "SMS when a transaction fails", alertFailedTx) {
                    alertFailedTx = it
                    prefs.edit().putBoolean("alert_failed_tx", it).apply()
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.BatteryAlert, "Low Battery Alert", "SMS when battery drops below limit", alertLowBattery) {
                    alertLowBattery = it
                    prefs.edit().putBoolean("alert_low_battery", it).apply()
                }
                AnimatedVisibility(visible = alertLowBattery) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (%)", lowBatteryLimit) { v ->
                            val vv = v.coerceIn(1, 100)
                            lowBatteryLimit = vv
                            prefs.edit().putInt("low_battery_limit", vv).apply()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun TransactionSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var autoClear by remember { mutableStateOf(prefs.safeGetString("auto_clear", "Never") ?: "Never") }
    var confirmClear by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(TransactionHistoryFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var summaryPeriod by remember {
        mutableStateOf(
            TransactionHistorySummaryPeriod.fromValue(
                prefs.safeGetString("transaction_summary_period", TransactionHistorySummaryPeriod.DAILY.value)
                    ?: TransactionHistorySummaryPeriod.DAILY.value
            )
        )
    }
    var history by remember {
        mutableStateOf(TransactionStore.load(ctx).sortedByDescending { transactionHistoryTimestamp(it) })
    }
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    val offers = remember { OfferRepository.load(ctx).filter { it.enabled } }
    val clearOptions = listOf("Daily", "Weekly", "Monthly", "Yearly", "Never")
    val tabs = TransactionHistoryFilter.entries.map { filter ->
        TransactionHistoryTab(
            filter = filter,
            label = filter.label,
            count = if (filter == TransactionHistoryFilter.ALL) {
                history.size
            } else {
                history.count { transactionHistoryFilterFor(it, offers) == filter }
            }
        )
    }
    val normalizedQuery = searchQuery.trim()
    val filteredHistory = history
        .sortedByDescending { transactionHistoryTimestamp(it) }
        .filter { tx ->
            val matchesTab = activeTab == TransactionHistoryFilter.ALL ||
                transactionHistoryFilterFor(tx, offers) == activeTab
            val matchesQuery = normalizedQuery.isBlank() ||
                tx.clientName.contains(normalizedQuery, ignoreCase = true) ||
                tx.description.contains(normalizedQuery, ignoreCase = true) ||
                tx.phoneNumber.contains(normalizedQuery, ignoreCase = true)
            matchesTab && matchesQuery
        }
    val groupedHistory = filteredHistory.groupBy { transactionHistoryDateLabel(it) }
    val summaryCompleted = history.filter {
        transactionHistoryFilterFor(it, offers) == TransactionHistoryFilter.COMPLETED &&
            transactionMatchesSummaryPeriod(it, summaryPeriod)
    }
    val totalCollected = summaryCompleted.sumOf { transactionHistoryAmountValue(it) }
    val unmatchedCount = history.count { transactionHistoryFilterFor(it, offers) == TransactionHistoryFilter.UNMATCHED }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = {
                TextButton(onClick = {
                    ctx.getSharedPreferences("transactions", Context.MODE_PRIVATE).edit().remove("list").apply()
                    history = emptyList()
                    Toast.makeText(ctx, "Transactions cleared", Toast.LENGTH_SHORT).show()
                    confirmClear = false
                }) { Text("Clear", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = C.t2) } },
            title = { Text("Clear all transactions?", color = C.t1) },
            text = { Text("This will wipe the entire transaction history.", color = C.t2) },
            containerColor = C.cardHi,
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0C0D),
                        Color(0xFF111517),
                        C.bg
                    )
                )
            )
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(44.dp)
                        .background(C.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t1)
                }
                Text(
                    "Transactions",
                    color = C.t1,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(44.dp)
                            .background(C.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = C.t1)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .width(272.dp)
                            .background(Color(0xFF262C2F), RoundedCornerShape(16.dp))
                            .border(1.dp, C.border.copy(alpha = 0.74f), RoundedCornerShape(16.dp))
                    ) {
                        TransactionMenuSectionTitle("Total Received Window")
                        TransactionHistorySummaryPeriod.entries.forEach { period ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        period.menuLabel,
                                        color = if (period == summaryPeriod) C.amber else C.t1,
                                        fontSize = 13.sp,
                                        fontWeight = if (period == summaryPeriod) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                trailingIcon = {
                                    if (period == summaryPeriod) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = C.amber)
                                    }
                                },
                                onClick = {
                                    summaryPeriod = period
                                    prefs.edit().putString("transaction_summary_period", period.value).apply()
                                    menuExpanded = false
                                }
                            )
                        }
                        TransactionMenuDivider()
                        TransactionMenuSectionTitle("Retention & Cleanup")
                        clearOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Auto-Clear: $option",
                                        color = if (option == autoClear) C.cyan else C.t1,
                                        fontSize = 13.sp,
                                        fontWeight = if (option == autoClear) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                trailingIcon = {
                                    if (option == autoClear) {
                                        Icon(Icons.Rounded.Autorenew, contentDescription = null, tint = C.cyan)
                                    }
                                },
                                onClick = {
                                    autoClear = option
                                    prefs.edit().putString("auto_clear", option).apply()
                                    menuExpanded = false
                                }
                            )
                        }
                        TransactionMenuDivider()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Clear All Transactions", color = C.red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Wipe entire transaction history", color = C.t3, fontSize = 11.sp)
                                }
                            },
                            trailingIcon = {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = C.red)
                            },
                            onClick = {
                                confirmClear = true
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            TransactionHistoryHeroCard(
                summaryPeriod = summaryPeriod,
                totalCollected = totalCollected,
                completedCount = summaryCompleted.size,
                unmatchedCount = unmatchedCount
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search by name or number...", color = C.t3) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = C.t3)
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF15191B),
                    unfocusedContainerColor = Color(0xFF15191B),
                    focusedBorderColor = Color(0xFF333B3E),
                    unfocusedBorderColor = Color(0xFF333B3E),
                    focusedTextColor = C.t1,
                    unfocusedTextColor = C.t1,
                    cursorColor = C.cyan,
                    focusedLeadingIconColor = C.t3,
                    unfocusedLeadingIconColor = C.t3,
                    focusedPlaceholderColor = C.t3,
                    unfocusedPlaceholderColor = C.t3
                )
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    TransactionHistoryTabChip(
                        tab = tab,
                        isActive = activeTab == tab.filter,
                        onClick = { activeTab = tab.filter }
                    )
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            if (filteredHistory.isEmpty()) {
                TransactionHistoryEmptyState(
                    hasTransactions = history.isNotEmpty(),
                    filterLabel = activeTab.label,
                    hasSearch = normalizedQuery.isNotBlank()
                )
            } else {
                groupedHistory.forEach { (dateLabel, items) ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            dateLabel.uppercase(Locale.getDefault()),
                            color = C.t3,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        items.forEach { tx ->
                            TransactionHistoryRow(tx = tx, offers = offers, onClick = { selectedTx = tx })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
    }

    selectedTx?.let { tx ->
        TransactionDetailDialog(
            tx = tx,
            onDismiss = { selectedTx = null },
            onDelete = {
                val updated = history.filterNot { it.id == tx.id }
                TransactionStore.save(ctx, updated)
                history = updated.sortedByDescending { transactionHistoryTimestamp(it) }
                Toast.makeText(ctx, "Transaction deleted", Toast.LENGTH_SHORT).show()
                selectedTx = null
            }
        )
    }
}

private enum class TransactionHistorySummaryPeriod(
    val value: String,
    val menuLabel: String,
    val heroLabel: String
) {
    DAILY("daily", "Daily", "TODAY'S RECEIVED"),
    WEEKLY("weekly", "Weekly", "THIS WEEK'S RECEIVED"),
    MONTHLY("monthly", "Monthly", "THIS MONTH'S RECEIVED");

    companion object {
        fun fromValue(value: String): TransactionHistorySummaryPeriod =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: DAILY
    }
}

private enum class TransactionHistoryFilter(val label: String) {
    ALL("All"),
    COMPLETED("Completed"),
    PENDING("Pending"),
    FAILED("Failed"),
    UNMATCHED("Unmatched")
}

private data class TransactionHistoryTab(
    val filter: TransactionHistoryFilter,
    val label: String,
    val count: Int
)

@Composable
private fun TransactionHistoryHeroCard(
    summaryPeriod: TransactionHistorySummaryPeriod,
    totalCollected: Double,
    completedCount: Int,
    unmatchedCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0x22FFB454),
                        Color(0x1674E6D8),
                        Color(0xFF1C2123)
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .border(1.dp, Color(0xFF262D2F), RoundedCornerShape(22.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            listOf(10.dp, 18.dp, 8.dp, 24.dp, 14.dp).forEach { barHeight ->
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(999.dp))
                        .background(C.cyan.copy(alpha = 0.35f))
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                summaryPeriod.heroLabel,
                color = C.t2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                transactionHistoryCurrency(totalCollected),
                color = C.amber,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = C.cyan, modifier = Modifier.size(14.dp))
                Text(
                    if (unmatchedCount > 0) {
                        "$completedCount successful transactions • $unmatchedCount unmatched"
                    } else {
                        "$completedCount successful transactions"
                    },
                    color = C.t2,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun TransactionHistoryTabChip(
    tab: TransactionHistoryTab,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(
                if (isActive) {
                    Brush.horizontalGradient(
                        listOf(
                            C.amber.copy(alpha = 0.96f),
                            C.cyan.copy(alpha = 0.92f)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1C2123),
                            Color(0xFF1C2123)
                        )
                    )
                },
                shape
            )
            .border(
                width = if (isActive) 0.dp else 1.dp,
                color = if (isActive) Color.Transparent else Color(0xFF262D2F),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tab.label,
            color = if (isActive) Color(0xFF15191B) else C.t2,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (isActive) Color(0x3315191B) else Color(0xFF2E3437),
                    RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                tab.count.toString(),
                color = if (isActive) Color(0xFF15191B) else C.t3,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TransactionHistoryEmptyState(
    hasTransactions: Boolean,
    filterLabel: String,
    hasSearch: Boolean
) {
    Surface(
        color = Color(0xFF1C2123),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF262D2F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF2E3437)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = C.t3, modifier = Modifier.size(20.dp))
            }
            Text(
                if (hasTransactions) "No matching transactions" else "No transactions recorded yet",
                color = C.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when {
                    !hasTransactions ->
                        "New collections and dispatch results will appear here automatically."
                    hasSearch ->
                        "Try a different name, phone number, or switch back to the ${filterLabel.lowercase(Locale.getDefault())} tab."
                    else ->
                        "There are no ${filterLabel.lowercase(Locale.getDefault())} transactions right now."
                },
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TransactionHistoryRow(
    tx: Transaction,
    offers: List<OfferItem>,
    onClick: () -> Unit
) {
    val classification = transactionHistoryFilterFor(tx, offers)
    val accent = transactionHistoryAccent(classification)
    val avatarColor = transactionAvatarColor(tx)
    val expectedOffer = resolveOfferForHistory(tx, offers)
    val title = tx.clientName.ifBlank {
        tx.description.ifBlank { "Transaction #${tx.id}" }
    }
    val primaryDetail = tx.phoneNumber
        .takeIf { it.isNotBlank() }
        ?.let(::maskTransactionPhone)
        ?: transactionSourceLabel(tx.source)
    val note = when (classification) {
        TransactionHistoryFilter.UNMATCHED -> {
            val received = transactionHistoryCurrency(transactionHistoryAmountValue(tx))
            expectedOffer?.let {
                "Received $received but ${it.name} is set to KES ${it.price}"
            } ?: "Received $received but it does not match any configured offer"
        }
        else -> tx.description
            .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            ?: transactionSourceLabel(tx.source)
    }
    val amountPrefix = if (classification == TransactionHistoryFilter.FAILED || tx.source == TX_SOURCE_AIRTIME) "-" else "+"

    Surface(
        color = Color(0xFF1C2123),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF262D2F)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(avatarColor.copy(alpha = 0.18f))
                    .border(1.dp, avatarColor.copy(alpha = 0.34f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    transactionAvatarText(tx),
                    color = avatarColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    color = C.t1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        primaryDetail,
                        color = C.t3,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TransactionStatusBadge(classification = classification, accent = accent)
                }
                Text(
                    note,
                    color = if (classification == TransactionHistoryFilter.UNMATCHED) accent else C.t2,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    amountPrefix + transactionHistoryCurrency(transactionHistoryAmountValue(tx)),
                    color = accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    transactionHistoryTimeLabel(tx),
                    color = C.t3,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun TransactionStatusBadge(
    classification: TransactionHistoryFilter,
    accent: Color
) {
    Surface(
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                classification.label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TransactionMenuSectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = C.t3,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun TransactionMenuDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF333B3E))
    )
}

private fun transactionHistoryFilterFor(
    tx: Transaction,
    offers: List<OfferItem>
): TransactionHistoryFilter {
    if (isTransactionUnmatched(tx, offers)) return TransactionHistoryFilter.UNMATCHED
    return when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> TransactionHistoryFilter.COMPLETED
        TransactionStatus.FAILED, TransactionStatus.CANCELLED -> TransactionHistoryFilter.FAILED
        else -> TransactionHistoryFilter.PENDING
    }
}

private fun isTransactionUnmatched(
    tx: Transaction,
    offers: List<OfferItem>
): Boolean {
    if (tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED) return false
    val amount = transactionHistoryAmountValue(tx)
    if (amount <= 0.0 || tx.source == TX_SOURCE_AIRTIME) return false
    val expectedOffer = resolveOfferForHistory(tx, offers)
    return if (expectedOffer != null) {
        expectedOffer.price.toDouble() != amount
    } else {
        offers.none { it.enabled && it.price.toDouble() == amount }
    }
}

private fun resolveOfferForHistory(
    tx: Transaction,
    offers: List<OfferItem>
): OfferItem? {
    tx.offerId.takeIf { it >= 0 }?.let { offerId ->
        offers.firstOrNull { it.id == offerId }?.let { return it }
    }
    offers.firstOrNull { it.name.trim().equals(tx.description.trim(), ignoreCase = true) }?.let { return it }
    if (tx.amountValue > 0.0) {
        offers.firstOrNull { it.price == tx.amountValue.toInt() }?.let { return it }
    }
    val normalizedCode = UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
    return offers.firstOrNull {
        UssdHelper.normalizeUssdCode(it.ussdCode, tx.phoneNumber) == normalizedCode
    }
}

private fun transactionHistoryAccent(classification: TransactionHistoryFilter): Color =
    when (classification) {
        TransactionHistoryFilter.COMPLETED -> C.green
        TransactionHistoryFilter.FAILED -> C.red
        TransactionHistoryFilter.UNMATCHED -> C.orange
        TransactionHistoryFilter.PENDING -> Color(0xFFF7B731)
        TransactionHistoryFilter.ALL -> C.cyan
    }

private fun transactionMatchesSummaryPeriod(
    tx: Transaction,
    period: TransactionHistorySummaryPeriod
): Boolean {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return false
    val now = Calendar.getInstance()
    val item = Calendar.getInstance().apply { timeInMillis = timestamp }
    return when (period) {
        TransactionHistorySummaryPeriod.DAILY -> sameDay(item, now)
        TransactionHistorySummaryPeriod.WEEKLY ->
            item.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                item.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR)
        TransactionHistorySummaryPeriod.MONTHLY ->
            item.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                item.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }
}

private fun transactionAvatarColor(tx: Transaction): Color {
    val palette = listOf(
        Color(0xFF6C63FF),
        Color(0xFF00C9A7),
        Color(0xFF4ECDC4),
        Color(0xFFFD79A8),
        Color(0xFFF7B731)
    )
    return palette[tx.id.absoluteValue % palette.size]
}

private fun transactionAvatarText(tx: Transaction): String {
    val source = tx.clientName
        .ifBlank { tx.description }
        .ifBlank { tx.phoneNumber }
        .ifBlank { "TX" }
    val parts = source
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        parts.isNotEmpty() -> parts.first().take(2).uppercase(Locale.getDefault())
        else -> "TX"
    }
}

private fun transactionSourceLabel(source: String): String =
    when (source) {
        TX_SOURCE_AUTOMATED -> "Automated"
        TX_SOURCE_MANUAL -> "Manual"
        TX_SOURCE_SMS_COMMAND -> "SMS Command"
        TX_SOURCE_AIRTIME -> "Airtime"
        else -> "System"
    }

private fun transactionHistoryAmountValue(tx: Transaction): Double =
    tx.amountValue.takeIf { it > 0.0 }
        ?: Regex("""\d+(?:\.\d+)?""").find(tx.amount)?.value?.toDoubleOrNull()
        ?: 0.0

private fun transactionHistoryCurrency(amount: Double): String {
    val rounded = amount.toLong().toDouble()
    val body = if (amount == rounded) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.2f", amount)
    }
    return "KES $body"
}

private fun transactionHistoryTimestamp(tx: Transaction): Long {
    if (tx.timestamp > 0L) return tx.timestamp
    return runCatching {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).parse(tx.date)?.time ?: 0L
    }.getOrDefault(0L)
}

private fun transactionHistoryDateLabel(tx: Transaction): String {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return tx.date.substringBefore(" ").ifBlank { "Earlier" }
    val now = Calendar.getInstance()
    val item = Calendar.getInstance().apply { timeInMillis = timestamp }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(item, now) -> "Today"
        sameDay(item, yesterday) -> "Yesterday · ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))}"
        else -> SimpleDateFormat("EEE · MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun transactionHistoryTimeLabel(tx: Transaction): String {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return tx.date.ifBlank { "Just now" }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
}

private fun isTransactionToday(tx: Transaction): Boolean {
    val timestamp = transactionHistoryTimestamp(tx)
    if (timestamp <= 0L) return false
    return sameDay(
        Calendar.getInstance().apply { timeInMillis = timestamp },
        Calendar.getInstance()
    )
}

private fun sameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

private fun maskTransactionPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 7) return phone
    return digits.take(4) + "..." + digits.takeLast(2)
}
