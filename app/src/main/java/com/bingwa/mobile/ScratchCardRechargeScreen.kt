package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val standaloneScratchCardRegex = Regex("""\b\d{16}\b""")
private val groupedScratchCardRegex = Regex("""(?<!\d)(\d{4})[\s-]+(\d{4})[\s-]+(\d{4})[\s-]+(\d{4})(?!\d)""")

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val settingsDataStore = remember(appContext) { SettingsDataStore(appContext) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val cards by ScratchCardRechargeManager.cards.collectAsState()
    val defaultSimSlot by settingsDataStore.rechargeScannerDefaultSim.collectAsState(initial = 1)
    val promptShown by settingsDataStore.rechargeScannerSimPromptShown.collectAsState(initial = false)
    val canModifyHistory = cards.none { it.status == ScratchCardStatus.PROCESSING }
    val visibleCards = remember(cards) { cards.take(5) }

    var defaultSimMenuExpanded by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var firstOpenSimSelection by remember(defaultSimSlot) { mutableIntStateOf(defaultSimSlot.coerceIn(1, 2)) }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(appContext) {
        ScratchCardRechargeManager.initialize(appContext)
    }

    DisposableEffect(recognizer) {
        onDispose { recognizer.close() }
    }

    suspend fun handleDetectedCodes(codes: List<String>, selectedSimSlot: Int) {
        val added = ScratchCardRechargeManager.enqueueCodes(appContext, codes, selectedSimSlot)
        if (added == 0 && codes.isNotEmpty()) {
            Toast.makeText(context, "Duplicate scratch card ignored", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun processUrisSequentially(uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(context, "No images selected", Toast.LENGTH_SHORT).show()
            return
        }
        isScanning = true
        try {
            val allCodes = mutableListOf<String>()
            uris.forEach { uri ->
                val image = InputImage.fromFilePath(context, uri)
                val codes = extractScratchCardCodes(recognizer.processAwait(image).text)
                if (codes.isEmpty()) {
                    Toast.makeText(context, "No valid 16-digit code found in image", Toast.LENGTH_SHORT).show()
                } else {
                    allCodes += codes
                }
            }
            handleDetectedCodes(allCodes, defaultSimSlot)
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to scan selected image", Toast.LENGTH_SHORT).show()
        } finally {
            isScanning = false
        }
    }

    suspend fun processCapturedBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(context, "No image captured", Toast.LENGTH_SHORT).show()
            return
        }
        isScanning = true
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val codes = extractScratchCardCodes(recognizer.processAwait(image).text)
            if (codes.isEmpty()) {
                Toast.makeText(context, "No valid 16-digit code found in image", Toast.LENGTH_SHORT).show()
            } else {
                handleDetectedCodes(codes, defaultSimSlot)
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to scan captured image", Toast.LENGTH_SHORT).show()
        } finally {
            isScanning = false
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch { processUrisSequentially(uris) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        scope.launch { processCapturedBitmap(bitmap) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(context, "Camera permission is needed to scan scratch cards", Toast.LENGTH_SHORT).show()
        }
    }

    if (!promptShown) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsDataStore.setRechargeScannerDefaultSim(firstOpenSimSelection)
                            settingsDataStore.markRechargeScannerSimPromptShown()
                        }
                    }
                ) {
                    Text("Continue", color = C.cyan)
                }
            },
            title = { Text("Recharge Scanner", color = C.t1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Which SIM should be used for recharge?", color = C.t1, fontSize = 14.sp)
                    SimChoiceRow(
                        title = "SIM 1",
                        subtitle = scratchCardSimLabel(appContext, 1),
                        selected = firstOpenSimSelection == 1,
                        color = C.green
                    ) {
                        firstOpenSimSelection = 1
                    }
                    SimChoiceRow(
                        title = "SIM 2",
                        subtitle = scratchCardSimLabel(appContext, 2),
                        selected = firstOpenSimSelection == 2,
                        color = C.blue
                    ) {
                        firstOpenSimSelection = 2
                    }
                }
            },
            containerColor = C.cardHi
        )
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCameraPermissionDialog = false
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("Allow", color = C.cyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text("Cancel", color = C.t2)
                }
            },
            title = { Text("Camera Permission", color = C.t1) },
            text = {
                Text(
                    "Recharge Scanner needs camera access to capture scratch cards.",
                    color = C.t2,
                    fontSize = 13.sp
                )
            },
            containerColor = C.cardHi
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        SettingsTopBar(
            title = "Recharge Scanner",
            subtitle = "Scan airtime cards from gallery or camera and recharge sequentially",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            SettingsGroup("Scanner Controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Default Recharge SIM", color = C.t2, fontSize = 12.sp)
                    Box {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = C.cardHi,
                            border = androidx.compose.foundation.BorderStroke(1.dp, C.border)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = promptShown && !isScanning) {
                                        defaultSimMenuExpanded = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SimBadge(slot = defaultSimSlot)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        scratchCardSimLabel(appContext, defaultSimSlot),
                                        color = C.t1,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Used automatically for newly scanned cards",
                                        color = C.t2,
                                        fontSize = 12.sp
                                    )
                                }
                                Text("Change", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        DropdownMenu(
                            expanded = defaultSimMenuExpanded,
                            onDismissRequest = { defaultSimMenuExpanded = false }
                        ) {
                            listOf(1, 2).forEach { slot ->
                                DropdownMenuItem(
                                    text = { Text(scratchCardSimLabel(appContext, slot)) },
                                    onClick = {
                                        defaultSimMenuExpanded = false
                                        scope.launch {
                                            settingsDataStore.setRechargeScannerDefaultSim(slot)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (!promptShown || isScanning) return@Button
                                galleryLauncher.launch("image/*")
                            },
                            enabled = promptShown && !isScanning,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C.cyan,
                                contentColor = Color(0xFF0B0E11)
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(if (isScanning) "Scanning..." else "Gallery")
                        }
                        Button(
                            onClick = {
                                if (!promptShown || isScanning) return@Button
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    cameraLauncher.launch(null)
                                } else {
                                    showCameraPermissionDialog = true
                                }
                            },
                            enabled = promptShown && !isScanning,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C.cardHi,
                                contentColor = C.t1
                            ),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Camera")
                        }
                    }
                }
            }

            SettingsGroup("Scratch Card History") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Queued And Completed Cards", color = C.t1, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                if (cards.size > 5) "Showing oldest 5 of ${cards.size} saved cards" else "Oldest cards stay at the top",
                                color = C.t2,
                                fontSize = 12.sp
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch { ScratchCardRechargeManager.clearHistory(appContext) }
                            },
                            enabled = cards.isNotEmpty() && canModifyHistory && !isScanning
                        ) {
                            Text("Clear History", color = if (cards.isNotEmpty() && canModifyHistory) C.red else C.t3)
                        }
                    }

                    if (visibleCards.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = C.cardHi,
                            border = androidx.compose.foundation.BorderStroke(1.dp, C.border)
                        ) {
                            Text(
                                "Scan a scratch card image to start automatic recharge.",
                                modifier = Modifier.padding(16.dp),
                                color = C.t2,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(
                                items = visibleCards,
                                key = { _, card -> card.id }
                            ) { index, card ->
                                ScratchCardRow(
                                    title = "Card ${index + 1}",
                                    card = card,
                                    enabled = card.status != ScratchCardStatus.PROCESSING && !isScanning,
                                    onSimChange = { simSlot ->
                                        scope.launch {
                                            ScratchCardRechargeManager.updateCardSim(appContext, card.id, simSlot)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(UiDimens.SpacingLg))
        }
    }
}

@Composable
private fun ScratchCardRow(
    title: String,
    card: ScratchCardEntry,
    enabled: Boolean,
    onSimChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = scratchCardStatusColor(card.status)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = C.cardHi,
        border = androidx.compose.foundation.BorderStroke(1.dp, C.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = C.t1, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(maskScratchCardCode(card.code), color = C.t2, fontSize = 13.sp)
                }
                SimBadge(slot = card.simSlot)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(card.status.label, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                Text("Assigned ${scratchCardSimLabel(LocalContext.current.applicationContext, card.simSlot)}", color = C.t2, fontSize = 12.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Card SIM", color = C.t2, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Box {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = C.card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = enabled) { expanded = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(scratchCardSimLabel(LocalContext.current.applicationContext, card.simSlot), color = C.t1, fontSize = 12.sp)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf(1, 2).forEach { slot ->
                            DropdownMenuItem(
                                text = { Text(scratchCardSimLabel(LocalContext.current.applicationContext, slot)) },
                                onClick = {
                                    expanded = false
                                    onSimChange(slot)
                                }
                            )
                        }
                    }
                }
            }
            if (card.lastMessage.isNotBlank()) {
                Text(card.lastMessage, color = C.t3, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SimChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) color.copy(alpha = 0.12f) else C.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color else C.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (selected) color else C.borderHi, CircleShape)
                    .background(if (selected) color else Color.Transparent, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = C.t1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = C.t2, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SimBadge(slot: Int) {
    val tint = if (slot == 2) C.blue else C.green
    Surface(
        shape = RoundedCornerShape(50),
        color = tint.copy(alpha = 0.14f)
    ) {
        Text(
            text = if (slot == 2) "Slot 2" else "Slot 1",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun scratchCardStatusColor(status: ScratchCardStatus): Color = when (status) {
    ScratchCardStatus.PENDING -> C.amber
    ScratchCardStatus.PROCESSING -> C.cyan
    ScratchCardStatus.SUCCESS -> C.green
    ScratchCardStatus.FAILED -> C.red
}

private fun scratchCardSimLabel(context: Context, slot: Int): String {
    val sims = getAvailableSims(context)
    return if (slot == 2) {
        describeUssdSimSelection(USSD_SIM_SELECTION_SLOT_2, sims)
    } else {
        describeUssdSimSelection(USSD_SIM_SELECTION_SLOT_1, sims)
    }
}

private fun maskScratchCardCode(code: String): String {
    val digits = code.filter(Char::isDigit).padStart(16, '*')
    val first = digits.take(4)
    val last = digits.takeLast(4)
    return "$first **** **** $last"
}

private fun extractScratchCardCodes(text: String): List<String> {
    val found = LinkedHashSet<String>()
    groupedScratchCardRegex.findAll(text).forEach { match ->
        found += match.groupValues.drop(1).joinToString("")
    }
    standaloneScratchCardRegex.findAll(text).forEach { match ->
        found += match.value
    }
    return found.toList()
}

private suspend fun TextRecognizer.processAwait(image: InputImage): com.google.mlkit.vision.text.Text =
    suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }
