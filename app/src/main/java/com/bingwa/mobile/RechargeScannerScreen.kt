package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val RECHARGE_DESCRIPTION_PREFIX = "Recharge Scratch Card"
private const val RECHARGE_SCANNER_PREFS = "recharge_scanner"
private const val RECHARGE_TEMPLATE_KEY = "recharge_template"
private const val RECHARGE_SIM_KEY = "recharge_sim_selection"
private const val DEFAULT_RECHARGE_TEMPLATE = "*141*{code}#"

private data class RechargeScanCard(
    val id: Long,
    val code: String,
    val sourceLabel: String,
    val status: String = "Ready",
    val txId: Int = -1
)

@Composable
fun RechargeScannerScreen(allTxns: MutableList<Transaction>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(ctx) { ctx.getSharedPreferences(RECHARGE_SCANNER_PREFS, Context.MODE_PRIVATE) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var processingImages by remember { mutableStateOf(false) }
    var scanCards by remember { mutableStateOf<List<RechargeScanCard>>(emptyList()) }
    var rechargeTemplate by remember {
        mutableStateOf(
            prefs.safeGetString(RECHARGE_TEMPLATE_KEY, DEFAULT_RECHARGE_TEMPLATE)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_RECHARGE_TEMPLATE
        )
    }
    var selectedSim by remember {
        mutableIntStateOf(
            normalizeOfferSimSelection(
                prefs.safeGetInt(RECHARGE_SIM_KEY, USSD_SIM_SELECTION_SLOT_1)
            ).takeIf { it != OFFER_SIM_USE_GENERAL } ?: USSD_SIM_SELECTION_SLOT_1
        )
    }
    var scanSummary by remember { mutableStateOf("Use gallery or camera to scan airtime scratch cards.") }
    var runningTxId by remember { mutableIntStateOf(-1) }
    var activeQueue by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun persistRechargePrefs() {
        prefs.edit()
            .putString(RECHARGE_TEMPLATE_KEY, rechargeTemplate)
            .putInt(RECHARGE_SIM_KEY, selectedSim)
            .apply()
    }

    fun mergeDetectedCodes(codes: List<String>, sourceLabel: String) {
        if (codes.isEmpty()) return
        val known = scanCards.associateBy { it.code }
        val additions = codes
            .mapNotNull { code ->
                val clean = normalizeScratchCardDigits(code)
                if (clean.length != 16 || known.containsKey(clean)) null
                else RechargeScanCard(
                    id = System.nanoTime() + clean.hashCode().toLong(),
                    code = clean,
                    sourceLabel = sourceLabel
                )
            }
        if (additions.isNotEmpty()) {
            scanCards = scanCards + additions
            scanSummary = "Detected ${additions.size} card(s) from $sourceLabel."
            showReviewDialog = true
        }
    }

    suspend fun scanUris(uris: List<Uri>, sourceLabel: String) {
        if (uris.isEmpty()) return
        processingImages = true
        scanSummary = "Scanning ${uris.size} image(s)..."
        val detected = linkedSetOf<String>()
        var failures = 0
        uris.forEach { uri ->
            runCatching {
                recognizeScratchCardText(ctx, uri)
            }.onSuccess { text ->
                extractScratchCardCodes(text).forEach(detected::add)
            }.onFailure {
                failures += 1
            }
        }
        processingImages = false
        if (detected.isEmpty()) {
            scanSummary = if (failures > 0) {
                "No clear 16-digit scratch card was detected. Try a sharper image."
            } else {
                "No 16-digit scratch card was detected."
            }
            Toast.makeText(ctx, scanSummary, Toast.LENGTH_LONG).show()
            return
        }
        mergeDetectedCodes(detected.toList(), sourceLabel)
        if (failures > 0) {
            Toast.makeText(ctx, "Some images could not be scanned, but detected cards were kept.", Toast.LENGTH_LONG).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { scanUris(uris, "Gallery") }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val imageUri = pendingCameraUri
        pendingCameraUri = null
        if (!saved || imageUri == null) return@rememberLauncherForActivityResult
        scope.launch { scanUris(listOf(imageUri), "Camera") }
    }

    DisposableEffect(ctx, runningTxId, activeQueue) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                if (txId < 0) return
                val status = intent.getStringExtra("status").orEmpty()
                val updatedTx = loadTransactionByIdFromPrefs(context, txId)
                val resolvedStatus = updatedTx?.status?.ifBlank { status } ?: status
                if (resolvedStatus.isBlank()) return
                scanCards = scanCards.map { card ->
                    if (card.txId == txId) card.copy(status = resolvedStatus) else card
                }
                if (txId == runningTxId && !resolvedStatus.equals("Processing", ignoreCase = true) && !resolvedStatus.equals("Retrying", ignoreCase = true)) {
                    runningTxId = -1
                }
            }
        }
        val registered = registerAppReceiver(
            ctx,
            receiver,
            IntentFilter("com.bingwa.mobile.TX_UPDATED")
        )
        onDispose {
            if (registered) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    LaunchedEffect(activeQueue, runningTxId, scanCards, rechargeTemplate, selectedSim) {
        if (!activeQueue || runningTxId >= 0) return@LaunchedEffect
        val nextCard = scanCards.firstOrNull { it.status == "Queued" && it.code.length == 16 }
        if (nextCard == null) {
            activeQueue = false
            if (scanCards.any { it.status == "Success" || it.status == "Pending" }) {
                scanSummary = "Recharge queue finished."
            }
            return@LaunchedEffect
        }

        val finalCode = buildRechargeCode(rechargeTemplate, nextCard.code)
        if (finalCode.isBlank()) {
            scanCards = scanCards.map { card ->
                if (card.id == nextCard.id) card.copy(status = "Failed") else card
            }
            scanSummary = "Recharge format must include {code} or a valid USSD prefix."
            return@LaunchedEffect
        }

        val txId = TransactionStore.createPendingTransaction(
            context = ctx,
            description = RECHARGE_DESCRIPTION_PREFIX,
            amount = "Airtime Recharge",
            phone = "",
            ussd = finalCode,
            clientName = "Scratch Card",
            status = TransactionStatus.PENDING.value,
            source = TX_SOURCE_MANUAL,
            showInRecent = false,
            offerId = -1
        )
        val started = ctx.startRechargeAutomation(
            txId = txId,
            finalCode = finalCode,
            simSelection = selectedSim
        )
        scanCards = scanCards.map { card ->
            if (card.id == nextCard.id) {
                if (started) card.copy(status = "Processing", txId = txId)
                else card.copy(status = "Failed", txId = txId)
            } else {
                card
            }
        }
        if (started) {
            runningTxId = txId
            persistRechargePrefs()
            scanSummary = "Running recharge ${nextCard.code} on ${offerSimSelectionLabel(selectedSim)}."
        } else {
            saveTransactionOutcome(ctx, txId, TransactionStatus.FAILED.value, "Unable to start recharge automation.")
            broadcastTransactionUpdated(ctx, txId)
        }
    }

    val history = allTxns
        .filter { it.description.startsWith(RECHARGE_DESCRIPTION_PREFIX, ignoreCase = true) }
        .sortedByDescending { it.timestamp }
        .take(8)
    val reviewCards = scanCards.filter { it.status == "Ready" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF121617),
            border = BorderStroke(1.dp, Color(0xFF333B3E))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "SCRATCH CARD HISTORY",
                            color = C.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Recharge Scanner",
                            color = C.t1,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (activeQueue) C.green.copy(alpha = 0.14f) else C.cyan.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (activeQueue) C.green.copy(alpha = 0.26f) else C.cyan.copy(alpha = 0.24f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (activeQueue) C.green else C.cyan, CircleShape)
                            )
                            Text(
                                if (activeQueue) "QUEUE ACTIVE" else "READY",
                                color = if (activeQueue) C.green else C.cyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    scanSummary,
                    color = C.t2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                if (processingImages) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = C.amber,
                            trackColor = C.w08
                        )
                        Text(
                            "Reading scratch card digits...",
                            color = C.t3,
                            fontSize = 12.sp
                        )
                    }
                }

                if (scanCards.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.DocumentScanner, null, tint = C.t3, modifier = Modifier.size(28.dp))
                        Text(
                            "No scanned cards yet.",
                            color = C.t1,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Tap Scan Cards to extract 16-digit airtime codes.",
                            color = C.t2,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        scanCards.forEachIndexed { index, card ->
                            RechargeQueuedCardRow(
                                index = index,
                                card = card,
                                onDelete = {
                                    if (!activeQueue) {
                                        scanCards = scanCards.filterNot { item -> item.id == card.id }
                                    }
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (!activeQueue && scanCards.none { it.status == "Ready" || it.status == "Queued" || it.status == "Processing" }) {
                            scanCards = emptyList()
                        }
                        showSourceDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = !processingImages,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A1548),
                        contentColor = C.blue
                    )
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Scan Cards",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF121617),
            border = BorderStroke(1.dp, Color(0xFF333B3E))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.History, null, tint = C.t2, modifier = Modifier.size(18.dp))
                    Text(
                        "Recent recharge runs",
                        color = C.t1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (history.isEmpty()) {
                    Text(
                        "Queued recharges will appear here after execution starts.",
                        color = C.t2,
                        fontSize = 12.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { tx ->
                            RechargeHistoryRow(tx = tx)
                        }
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourceDialog = false }) {
                    Text("Close", color = C.t2)
                }
            },
            title = {
                Text(
                    "Scan Scratch Card",
                    color = C.t1,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Choose an option to scan your airtime cards.",
                        color = C.t2,
                        fontSize = 13.sp
                    )
                    RechargeSourceButton(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "Gallery",
                        detail = "Upload one or more saved images",
                        onClick = {
                            showSourceDialog = false
                            galleryLauncher.launch("image/*")
                        }
                    )
                    RechargeSourceButton(
                        icon = Icons.Filled.CameraAlt,
                        title = "Camera",
                        detail = "Take a photo and scan it immediately",
                        onClick = {
                            showSourceDialog = false
                            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                                Toast.makeText(ctx, "This phone does not have a usable camera.", Toast.LENGTH_SHORT).show()
                                return@RechargeSourceButton
                            }
                            val imageUri = createRechargeCameraUri(ctx)
                            if (imageUri == null) {
                                Toast.makeText(ctx, "Unable to prepare the camera image file.", Toast.LENGTH_SHORT).show()
                                return@RechargeSourceButton
                            }
                            pendingCameraUri = imageUri
                            cameraLauncher.launch(imageUri)
                        }
                    )
                }
            },
            containerColor = C.cardHi
        )
    }

    if (showReviewDialog) {
        val allValid = reviewCards.isNotEmpty() && reviewCards.all { normalizeScratchCardDigits(it.code).length == 16 }
        AlertDialog(
            onDismissRequest = {
                if (!activeQueue && !processingImages) showReviewDialog = false
            },
            confirmButton = {
                Button(
                    onClick = {
                        scanCards = scanCards.map { card ->
                            if (card.status == "Ready") {
                                card.copy(code = normalizeScratchCardDigits(card.code), status = "Queued", txId = -1)
                            } else {
                                card
                            }
                        }
                        activeQueue = true
                        runningTxId = -1
                        showReviewDialog = false
                    },
                    enabled = !activeQueue && !processingImages && allValid,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD2B3FF), contentColor = Color(0xFF301A5D))
                ) {
                    Text("Queue Airtime", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!activeQueue) showReviewDialog = false
                    }
                ) {
                    Text("Cancel", color = C.t2)
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Review Scanned Cards",
                        color = C.t1,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Please verify the 16-digit codes and select the target SIM.",
                        color = C.t2,
                        fontSize = 13.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RechargeSimChip(
                            label = "SIM 1",
                            selected = selectedSim == USSD_SIM_SELECTION_SLOT_1,
                            onClick = { selectedSim = USSD_SIM_SELECTION_SLOT_1 },
                            modifier = Modifier.weight(1f)
                        )
                        RechargeSimChip(
                            label = "SIM 2",
                            selected = selectedSim == USSD_SIM_SELECTION_SLOT_2,
                            onClick = { selectedSim = USSD_SIM_SELECTION_SLOT_2 },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = rechargeTemplate,
                        onValueChange = { rechargeTemplate = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Recharge format", color = C.t2) },
                        placeholder = { Text(DEFAULT_RECHARGE_TEMPLATE, color = C.t3) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, null, tint = C.t2) },
                        supportingText = {
                            Text(
                                "Use {code} where the 16-digit scratch card should be inserted.",
                                color = C.t3
                            )
                        },
                        singleLine = true,
                        colors = fieldColors()
                    )

                    reviewCards.forEachIndexed { index, card ->
                        OutlinedTextField(
                            value = card.code,
                            onValueChange = { next ->
                                val clean = next.filter { it.isDigit() }.take(16)
                                scanCards = scanCards.map { item ->
                                    if (item.id == card.id) item.copy(code = clean) else item
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Card ${index + 1}", color = C.t2) },
                            supportingText = {
                                Text(
                                    "${card.sourceLabel} · ${normalizeScratchCardDigits(card.code).length}/16 digits",
                                    color = C.t3
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = fieldColors()
                        )
                    }
                }
            },
            containerColor = C.cardHi
        )
    }
}

@Composable
private fun RechargeSourceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1B2022),
        border = BorderStroke(1.dp, C.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(C.cyan.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = C.cyan, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = C.t2, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RechargeSimChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFF2A2040) else Color(0xFF181D20),
        border = BorderStroke(1.dp, if (selected) Color(0xFFD2B3FF) else C.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Icon(Icons.Filled.SimCard, null, tint = if (selected) Color(0xFFD2B3FF) else C.t2, modifier = Modifier.size(16.dp))
            Text(label, color = C.t1, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RechargeQueuedCardRow(
    index: Int,
    card: RechargeScanCard,
    onDelete: () -> Unit
) {
    val statusColor = rechargeStatusColor(card.status)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF191D1F),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Card ${index + 1}",
                    color = C.t2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RechargeStatusBadge(status = card.status, color = statusColor)
                    if (card.status == "Ready") {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Filled.DeleteOutline, null, tint = C.t3, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Text(
                card.code,
                color = C.t1,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                card.sourceLabel,
                color = C.t3,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun RechargeHistoryRow(tx: Transaction) {
    val statusColor = rechargeStatusColor(tx.status)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF181C1E),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tx.status,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tx.date,
                    color = C.t3,
                    fontSize = 11.sp
                )
            }
            Text(
                extractScratchCardCodes(tx.ussdCode).firstOrNull().orEmpty().ifBlank { tx.ussdCode },
                color = C.t1,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            if (tx.ussdResponse.isNotBlank()) {
                Text(
                    tx.ussdResponse,
                    color = C.t2,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun RechargeStatusBadge(status: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (status.equals("Processing", ignoreCase = true)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = color,
                    trackColor = color.copy(alpha = 0.2f)
                )
            } else if (status.equals("Success", ignoreCase = true)) {
                Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(12.dp))
            }
            Text(
                status.uppercase(Locale.getDefault()),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun rechargeStatusColor(status: String): Color = when (status.trim().uppercase(Locale.getDefault())) {
    "SUCCESS" -> C.green
    "FAILED", "CANCELLED" -> C.red
    "PROCESSING" -> C.amber
    "QUEUED", "PENDING", "UNDERMAINTENANCE", "RETRYING" -> C.cyan
    else -> C.t2
}

private fun createRechargeCameraUri(context: Context): Uri? {
    val file = runCatching {
        File.createTempFile("scratch_card_", ".jpg", context.cacheDir).apply {
            deleteOnExit()
        }
    }.getOrNull() ?: return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

private fun normalizeScratchCardDigits(value: String): String =
    value.filter { it.isDigit() }.take(16)

private fun buildRechargeCode(template: String, digits: String): String {
    val cleanDigits = normalizeScratchCardDigits(digits)
    if (cleanDigits.length != 16) return ""
    val trimmedTemplate = template.trim().ifBlank { DEFAULT_RECHARGE_TEMPLATE }
    val withDigits = if (trimmedTemplate.contains("{code}", ignoreCase = true)) {
        trimmedTemplate.replace("{code}", cleanDigits, ignoreCase = true)
    } else {
        trimmedTemplate + cleanDigits
    }
    return UssdHelper.normalizeUssdCode(withDigits)
}

private fun extractScratchCardCodes(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val normalized = text
        .uppercase(Locale.getDefault())
        .replace('O', '0')
        .replace('I', '1')
        .replace('L', '1')
        .replace('S', '5')
        .replace('B', '8')
    val matches = linkedSetOf<String>()
    Regex("""(?:\d[\s-]*){16,48}""")
        .findAll(normalized)
        .forEach { match ->
            val digits = match.value.filter(Char::isDigit)
            when {
                digits.length == 16 -> matches += digits
                digits.length > 16 -> digits.chunked(16)
                    .filter { it.length == 16 }
                    .forEach(matches::add)
            }
        }
    if (matches.isNotEmpty()) return matches.toList()
    return normalized
        .lineSequence()
        .map { line -> line.filter(Char::isDigit) }
        .filter { it.length == 16 }
        .distinct()
        .toList()
}

private suspend fun recognizeScratchCardText(context: Context, uri: Uri): String {
    val image = InputImage.fromFilePath(context, uri)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { result ->
                recognizer.close()
                continuation.resume(result.text)
            }
            .addOnFailureListener { error ->
                recognizer.close()
                continuation.resumeWithException(error)
            }
    }
}

private fun Context.startRechargeAutomation(
    txId: Int,
    finalCode: String,
    simSelection: Int,
    returnToAppAggressively: Boolean = true
): Boolean {
    return ServiceLauncher.startAutomationService(
        this,
        Intent(this, AutomationService::class.java).apply {
            putExtra("mode", OFFER_EXECUTION_MODE_SIMPLE)
            putExtra("code", finalCode)
            putExtra("phoneNumber", "")
            putExtra("txId", txId)
            putExtra("offerId", -1)
            putExtra("offerName", RECHARGE_DESCRIPTION_PREFIX)
            putExtra("simSelection", normalizeOfferSimSelection(simSelection))
            putExtra("signatureEnabled", false)
            putExtra("signatureMode", "STOP")
            putExtra("signatureLearning", false)
            putExtra("returnToAppAggressively", returnToAppAggressively)
        }
    )
}
