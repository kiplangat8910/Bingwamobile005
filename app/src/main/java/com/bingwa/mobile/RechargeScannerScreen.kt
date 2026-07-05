package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RechargeScannerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ScratchCardRechargeManager.state.collectAsState()
    val sims = getAvailableSims(ctx)
    val firstLaunchChoices = remember(sims) {
        buildList {
            add(USSD_SIM_SELECTION_SLOT_1)
            if (sims.any { it.simSlotIndex == 1 } || sims.size > 1) add(USSD_SIM_SELECTION_SLOT_2)
        }
    }
    var defaultSimSelection by remember {
        mutableIntStateOf(SettingsDataStore.getRechargeScannerSimSelection(ctx))
    }
    var showFirstLaunchDialog by remember {
        mutableStateOf(!SettingsDataStore.isRechargeScannerSimConfigured(ctx))
    }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var processingImages by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        ScratchCardRechargeManager.initialize(ctx)
    }

    fun saveDefaultSim(selection: Int) {
        defaultSimSelection = normalizeUssdSimSelection(selection, sims)
        SettingsDataStore.setRechargeScannerSimSelection(ctx, defaultSimSelection)
    }

    suspend fun enqueueDetectedCodes(codes: Set<String>) {
        if (codes.isEmpty()) {
            Toast.makeText(ctx, "No valid 16-digit code found.", Toast.LENGTH_SHORT).show()
            return
        }
        val result = ScratchCardRechargeManager.enqueueCodes(
            context = ctx,
            codes = codes.toList(),
            defaultSimSelection = defaultSimSelection
        )
        when {
            result.addedCount > 0 && result.duplicateCount > 0 ->
                Toast.makeText(
                    ctx,
                    "Queued ${result.addedCount} card(s). Skipped ${result.duplicateCount} duplicate code(s).",
                    Toast.LENGTH_LONG
                ).show()
            result.addedCount > 0 ->
                Toast.makeText(ctx, "Queued ${result.addedCount} card(s).", Toast.LENGTH_SHORT).show()
            else ->
                Toast.makeText(ctx, "All detected codes were already in history.", Toast.LENGTH_SHORT).show()
        }
        if (result.queueFull) {
            Toast.makeText(ctx, "Scanner history is full. Older completed items must be cleared first.", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun processGalleryUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(ctx, "Empty gallery selection.", Toast.LENGTH_SHORT).show()
            return
        }
        processingImages = true
        val detectedCodes = LinkedHashSet<String>()
        uris.forEach { uri ->
            runCatching { ScratchCardTextRecognizer.recognizeFromUri(ctx, uri) }
                .onSuccess { detectedCodes.addAll(it) }
                .onFailure { Log.e("RechargeScanner", "Gallery OCR failed for $uri", it) }
        }
        processingImages = false
        enqueueDetectedCodes(detectedCodes)
    }

    suspend fun processCameraCapture(uri: Uri) {
        processingImages = true
        val detectedCodes = runCatching {
            ScratchCardTextRecognizer.recognizeFromUri(ctx, uri)
        }.onFailure {
            Log.e("RechargeScanner", "Camera OCR failed", it)
        }.getOrDefault(emptyList())
        processingImages = false
        enqueueDetectedCodes(detectedCodes.toSet())
    }

    fun launchCameraCapture(
        launcher: androidx.activity.result.ActivityResultLauncher<Uri>
    ) {
        val photoUri = createRechargeScannerImageUri(ctx)
        pendingCameraUri = photoUri
        launcher.launch(photoUri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val selected = uris.distinct()
        if (selected.isEmpty()) {
            Toast.makeText(ctx, "Empty gallery selection.", Toast.LENGTH_SHORT).show()
        } else {
            scope.launch { processGalleryUris(selected) }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        pendingCameraUri = null
        if (!success || capturedUri == null) {
            Toast.makeText(ctx, "Camera capture cancelled.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch { processCameraCapture(capturedUri) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture(cameraLauncher)
        } else {
            Toast.makeText(ctx, "Camera permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = "Recharge Scanner",
                subtitle = "Scan recharge cards from camera or gallery, then auto-run them in order",
                onBack = onBack
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    start = UiDimens.ScreenPaddingHorizontal,
                    end = UiDimens.ScreenPaddingHorizontal,
                    bottom = UiDimens.Spacing2xl
                ),
                verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
            ) {
                item {
                    SettingsGroup("Scan Cards") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                if (processingImages) "Reading selected image(s)..." else "Pick a camera photo or one or more gallery images.",
                                color = C.t2,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                                            PackageManager.PERMISSION_GRANTED
                                        ) {
                                            launchCameraCapture(cameraLauncher)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    enabled = !processingImages,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = C.amber,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Camera", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { galleryLauncher.launch("image/*") },
                                    enabled = !processingImages,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.5f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.cyan)
                                ) {
                                    Icon(Icons.Rounded.Image, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("Gallery", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    SettingsGroup("Queue Summary") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MiniTag("Queued ${state.queuedCount}", C.amber)
                            MiniTag("Processing ${state.processingCount}", C.cyan)
                            MiniTag(
                                "Done ${state.history.count { it.status == ScratchCardStatus.Success }}",
                                C.green
                            )
                            MiniTag(
                                "Failed ${state.history.count { it.status == ScratchCardStatus.Failed }}",
                                C.red
                            )
                        }
                    }
                }

                item {
                    SettingsGroup("SIM") {
                        RechargeScannerSimSelectionRow(
                            title = "Default Scanner SIM",
                            subtitle = "Used for newly scanned cards until you change a card manually.",
                            currentSelection = defaultSimSelection,
                            sims = sims,
                            enabled = !processingImages
                        ) { selection ->
                            saveDefaultSim(selection)
                        }
                    }
                }

                item {
                    SettingsGroup("History") {
                        if (state.history.isEmpty()) {
                            Text(
                                "No scanned cards yet.",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                                color = C.t2,
                                fontSize = 13.sp
                            )
                        } else {
                            state.history.forEachIndexed { index, item ->
                                ScratchCardHistoryRow(
                                    item = item,
                                    sims = sims,
                                    isProcessing = state.activeId == item.id,
                                    onSimChange = { selection ->
                                        ScratchCardRechargeManager.updateItemSimSelection(
                                            context = ctx,
                                            itemId = item.id,
                                            simSelection = selection
                                        )
                                    }
                                )
                                if (index != state.history.lastIndex) GroupDivider()
                            }
                            GroupDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { showClearHistoryDialog = true },
                                    enabled = state.activeId == null
                                ) {
                                    Icon(Icons.Rounded.DeleteSweep, null, tint = C.red, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Clear History", color = C.red, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFirstLaunchDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Choose Scanner SIM") },
            text = {
                Text("Which SIM should the Recharge Scanner use by default? You can change it later from this screen.")
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    firstLaunchChoices.forEach { selection ->
                        TextButton(onClick = {
                            saveDefaultSim(selection)
                            showFirstLaunchDialog = false
                        }) {
                            Text(if (selection == USSD_SIM_SELECTION_SLOT_2) "SIM 2" else "SIM 1")
                        }
                    }
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear scanner history?") },
            text = {
                Text("This removes queued and completed recharge cards from the scanner history.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        val cleared = ScratchCardRechargeManager.clearHistory(ctx)
                        if (!cleared) {
                            Toast.makeText(ctx, "Wait for the current card to finish first.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Clear", color = C.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ScratchCardHistoryRow(
    item: ScratchCardHistoryItem,
    sims: List<android.telephony.SubscriptionInfo>,
    isProcessing: Boolean,
    onSimChange: (Int) -> Unit
) {
    val accent = scratchCardStatusColor(item.status)
    val statusLabel = when (item.status) {
        ScratchCardStatus.Pending -> "Pending"
        ScratchCardStatus.Processing -> "Processing"
        ScratchCardStatus.Success -> "Success"
        ScratchCardStatus.Failed -> "Failed"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
            ) {
                Icon(
                    imageVector = scratchCardStatusIcon(item.status),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    maskScratchCardCode(item.code),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    item.lastResponse.ifBlank {
                        if (item.retryCount > 0 && item.status == ScratchCardStatus.Pending) {
                            "Retry queued once more."
                        } else {
                            "Ready for recharge."
                        }
                    },
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MiniTag(statusLabel, accent)
        }
        RechargeScannerSimSelectionRow(
            title = "Use SIM",
            subtitle = "Current card setting",
            currentSelection = item.simSelection,
            sims = sims,
            enabled = !isProcessing,
            onSelect = onSimChange
        )
    }
}

@Composable
private fun RechargeScannerSimSelectionRow(
    title: String,
    subtitle: String,
    currentSelection: Int,
    sims: List<android.telephony.SubscriptionInfo>,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val slotOptions = remember(sims) {
        buildList {
            add(USSD_SIM_SELECTION_SLOT_1)
            if (sims.any { it.simSlotIndex == 1 } || sims.size > 1) add(USSD_SIM_SELECTION_SLOT_2)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = C.cardHi,
            border = BorderStroke(1.dp, C.border)
        ) {
            Icon(
                Icons.Rounded.SimCard,
                null,
                tint = C.cyan,
                modifier = Modifier.padding(10.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Box {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled) { expanded = true },
                shape = RoundedCornerShape(14.dp),
                color = C.cardHi,
                border = BorderStroke(1.dp, C.border)
            ) {
                Text(
                    text = describeRechargeScannerSim(currentSelection, sims),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = if (enabled) C.cyan else C.t2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(C.cardHi, RoundedCornerShape(12.dp))
                    .border(1.dp, C.border, RoundedCornerShape(12.dp))
            ) {
                slotOptions.forEach { selection ->
                    DropdownMenuItem(
                        text = { Text(describeRechargeScannerSim(selection, sims), color = C.t1) },
                        onClick = {
                            onSelect(selection)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun scratchCardStatusColor(status: ScratchCardStatus): Color = when (status) {
    ScratchCardStatus.Pending -> C.amber
    ScratchCardStatus.Processing -> C.cyan
    ScratchCardStatus.Success -> C.green
    ScratchCardStatus.Failed -> C.red
}

private fun scratchCardStatusIcon(status: ScratchCardStatus) = when (status) {
    ScratchCardStatus.Pending -> Icons.Rounded.Schedule
    ScratchCardStatus.Processing -> Icons.Rounded.Autorenew
    ScratchCardStatus.Success -> Icons.Rounded.CheckCircle
    ScratchCardStatus.Failed -> Icons.Rounded.Error
}

private fun maskScratchCardCode(code: String): String =
    if (code.length < 8) code else "${code.take(4)} **** **** ${code.takeLast(4)}"

private fun describeRechargeScannerSim(
    selection: Int,
    sims: List<android.telephony.SubscriptionInfo>
): String = when (normalizeUssdSimSelection(selection, sims)) {
    USSD_SIM_SELECTION_SLOT_2 -> {
        val slot2 = sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)
        val name = slot2?.displayName?.toString()?.trim().orEmpty()
        if (name.isBlank()) "SIM 2" else "SIM 2 · $name"
    }
    else -> {
        val slot1 = sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.firstOrNull()
        val name = slot1?.displayName?.toString()?.trim().orEmpty()
        if (name.isBlank()) "SIM 1" else "SIM 1 · $name"
    }
}

private fun createRechargeScannerImageUri(context: Context): Uri {
    val directory = File(context.cacheDir, "scanner_captures").apply { mkdirs() }
    val imageFile = File(directory, "scratch_card_${System.currentTimeMillis()}.jpg")
    if (!imageFile.exists()) imageFile.createNewFile()
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}
