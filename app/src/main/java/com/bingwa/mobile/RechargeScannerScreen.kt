package com.bingwa.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RechargeScannerScreen(
    onBack: () -> Unit,
    viewModel: RechargeScannerViewModel = viewModel(
        factory = RechargeScannerViewModel.factory(LocalContext.current.applicationContext as Application)
    )
) {
    val ctx = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sims = remember(uiState.history.size) { getAvailableSims(ctx) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        viewModel.importFromCamera(bitmap)
    }

    val openGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.importFromGallery(uris)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(ctx, "Camera permission is required to scan recharge cards", Toast.LENGTH_SHORT).show()
        }
    }

    val openCamera = {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(ctx, "This phone does not have a usable camera", Toast.LENGTH_SHORT).show()
        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePictureLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (uiState.showInitialSimDialog) {
        RechargeScannerSimDialog(
            selectedSim = uiState.defaultSimSelection,
            onSelect = viewModel::setDefaultSim
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(
            title = "Recharge Scanner",
            subtitle = "Scan scratch cards, queue them automatically, and recharge with the selected SIM",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .padding(horizontal = UiDimens.ScreenPaddingHorizontal)
                .widthIn(max = 430.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.message?.let { message ->
                FeedbackBanner(
                    msg = message,
                    color = if (message.contains("no ", ignoreCase = true) || message.contains("full", ignoreCase = true)) C.amber else C.green
                )
            }

            SettingsGroup("Scanner Setup") {
                RechargeScannerSelectorRow(
                    title = "Default Recharge SIM",
                    subtitle = "Used when new cards are added to the queue",
                    selectedSim = uiState.defaultSimSelection,
                    sims = sims,
                    onSelect = viewModel::setDefaultSim
                )
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = openCamera,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Camera")
                    }
                    OutlinedButton(
                        onClick = { openGalleryLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, C.border),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Rounded.Collections, null, modifier = Modifier.size(18.dp), tint = C.t1)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery", color = C.t1)
                    }
                }
                if (uiState.isImporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = C.cyan,
                            trackColor = C.cyan.copy(alpha = 0.16f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Reading text and extracting 16-digit recharge codes...",
                            color = C.t2,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            SettingsGroup("Queue & History") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(C.amber.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                .border(1.dp, C.amber.copy(alpha = 0.20f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.History, null, tint = C.amber)
                        }
                        Column {
                            Text("Scanned Cards", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${uiState.history.size} of $SCRATCH_CARD_MAX_HISTORY stored", color = C.t2, fontSize = 12.sp)
                        }
                    }
                    TextButton(
                        onClick = viewModel::clearHistory,
                        enabled = uiState.history.isNotEmpty()
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, null, tint = if (uiState.history.isNotEmpty()) C.red else C.t3)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear History", color = if (uiState.history.isNotEmpty()) C.red else C.t3)
                    }
                }
                Divider(color = C.amber.copy(alpha = 0.16f), thickness = 0.8.dp)
                if (uiState.history.isEmpty()) {
                    RechargeScannerEmptyState()
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        uiState.history.forEachIndexed { index, item ->
                            RechargeScannerHistoryRow(
                                item = item,
                                sims = sims,
                                onSimSelect = { sim -> viewModel.updateCardSim(item.historyId, sim) }
                            )
                            if (index != uiState.history.lastIndex) {
                                GroupDivider()
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun RechargeScannerSimDialog(
    selectedSim: Int,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onSelect(USSD_SIM_SELECTION_SLOT_1) },
        confirmButton = {},
        title = { Text("Choose Recharge SIM", color = C.t1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pick the SIM to use for new recharge cards. Slot 1 is the default if you close this prompt.",
                    color = C.t2,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                RechargeScannerSimChoice(
                    label = "SIM 1",
                    selected = selectedSim != USSD_SIM_SELECTION_SLOT_2,
                    onClick = { onSelect(USSD_SIM_SELECTION_SLOT_1) }
                )
                RechargeScannerSimChoice(
                    label = "SIM 2",
                    selected = selectedSim == USSD_SIM_SELECTION_SLOT_2,
                    onClick = { onSelect(USSD_SIM_SELECTION_SLOT_2) }
                )
            }
        },
        containerColor = C.cardHi,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    )
}

@Composable
private fun RechargeScannerSimChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) C.amber.copy(alpha = 0.10f) else C.card,
        border = BorderStroke(1.dp, if (selected) C.amber.copy(alpha = 0.28f) else C.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.SimCard, null, tint = if (selected) C.amber else C.t2)
            Spacer(Modifier.width(10.dp))
            Text(label, color = C.t1, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RechargeScannerSelectorRow(
    title: String,
    subtitle: String,
    selectedSim: Int,
    sims: List<android.telephony.SubscriptionInfo>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Rounded.SimCard)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(simSelectionLabel(selectedSim, sims), color = C.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text("Change", color = C.cyan, fontSize = 12.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(C.cardHi, RoundedCornerShape(12.dp))
                    .border(1.dp, C.border, RoundedCornerShape(12.dp))
            ) {
                DropdownMenuItem(
                    text = { Text(simSelectionLabel(USSD_SIM_SELECTION_SLOT_1, sims), color = C.t1) },
                    onClick = {
                        onSelect(USSD_SIM_SELECTION_SLOT_1)
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(simSelectionLabel(USSD_SIM_SELECTION_SLOT_2, sims), color = C.t1) },
                    onClick = {
                        onSelect(USSD_SIM_SELECTION_SLOT_2)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RechargeScannerHistoryRow(
    item: ScratchCardHistoryItem,
    sims: List<android.telephony.SubscriptionInfo>,
    onSimSelect: (Int) -> Unit
) {
    var expanded by remember(item.historyId) { mutableStateOf(false) }
    val statusColor = rechargeStatusColor(item.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = maskScratchCardCode(item.code),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                )
                Text(
                    text = formatScannerTimestamp(item.updatedAt),
                    color = C.t3,
                    fontSize = 11.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item.status == SCRATCH_CARD_STATUS_PROCESSING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = statusColor,
                            strokeWidth = 1.6.dp
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                    }
                    Text(item.status, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.SimCard, null, tint = C.t2, modifier = Modifier.size(16.dp))
                Text(simSelectionLabel(item.simSelection, sims), color = C.t2, fontSize = 12.sp)
                if (item.retryCount > 0) {
                    MiniTag("Retry ${item.retryCount}", C.amber)
                }
            }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text("Change SIM", color = if (item.status == SCRATCH_CARD_STATUS_PROCESSING) C.t3 else C.cyan)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(C.cardHi, RoundedCornerShape(12.dp))
                        .border(1.dp, C.border, RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text(simSelectionLabel(USSD_SIM_SELECTION_SLOT_1, sims), color = C.t1) },
                        onClick = {
                            onSimSelect(USSD_SIM_SELECTION_SLOT_1)
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(simSelectionLabel(USSD_SIM_SELECTION_SLOT_2, sims), color = C.t1) },
                        onClick = {
                            onSimSelect(USSD_SIM_SELECTION_SLOT_2)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (item.lastResponse.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = C.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.14f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Rounded.Sync, null, tint = statusColor, modifier = Modifier.size(16.dp))
                    Text(
                        text = item.lastResponse.replace(Regex("\\s+"), " ").trim(),
                        color = C.t2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RechargeScannerEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    Brush.radialGradient(listOf(C.amber.copy(alpha = 0.16f), Color.Transparent)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Collections, null, tint = C.amber, modifier = Modifier.size(24.dp))
        }
        Text("No scanned recharge cards yet", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Use Camera or Gallery to extract 16-digit scratch-card codes and queue them automatically.",
            color = C.t2,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

private fun rechargeStatusColor(status: String): Color {
    return when (status) {
        SCRATCH_CARD_STATUS_SUCCESS -> C.green
        SCRATCH_CARD_STATUS_FAILED -> C.red
        SCRATCH_CARD_STATUS_PROCESSING -> C.cyan
        else -> C.amber
    }
}

private fun simSelectionLabel(selection: Int, sims: List<android.telephony.SubscriptionInfo>): String {
    return when (selection) {
        USSD_SIM_SELECTION_SLOT_2 -> describeUssdSimSelection(USSD_SIM_SELECTION_SLOT_2, sims)
        else -> describeUssdSimSelection(USSD_SIM_SELECTION_SLOT_1, sims)
    }
}

private fun maskScratchCardCode(code: String): String {
    val trimmed = code.filter(Char::isDigit)
    return when {
        trimmed.length < 4 -> "****"
        else -> "**** **** **** ${trimmed.takeLast(4)}"
    }
}

private fun formatScannerTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
}
