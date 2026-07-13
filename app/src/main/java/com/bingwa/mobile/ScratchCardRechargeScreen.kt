package com.bingwa.mobile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay

private const val PREFS_SCRATCH_CARD = "scratch_card_prefs"
private const val KEY_RECENT_PINS = "recent_pins"
private const val KEY_RECHARGE_HISTORY = "recharge_history"
private const val KEY_USED_PIN_RECORDS = "used_pin_records"
private const val MAX_RECENT_PINS = 5
private const val FREE_RECHARGE_WINDOW_LIMIT = 10
private const val FREE_RECHARGE_WINDOW_MS = 24 * 60 * 60 * 1000L
private const val USED_PIN_RECORD_RETENTION_MS = 60 * 60 * 1000L

private sealed interface ScratchCardRechargeResult {
    data class Completed(val response: String) : ScratchCardRechargeResult
    data class Failed(val message: String) : ScratchCardRechargeResult
}

private data class ScratchRecentPinRecord(
    val pin: String,
    val finalResponse: String,
    val timestamp: Long
)

private data class ScratchUsedPinRecord(
    val pin: String,
    val simSelection: Int,
    val timestamp: Long,
    val finalResponse: String
)

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sims = remember { getAvailableSims(ctx) }
    val defaultRechargeSim = remember { currentUssdSimSelection(ctx) }
    val defaultScanMessage = "Pick one image to scan for 16-digit scratch card PINs."

    var pin by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isScanningImage by remember { mutableStateOf(false) }
    var isRefreshingBalance by remember { mutableStateOf(false) }
    var selectedRechargeSim by remember { mutableIntStateOf(defaultRechargeSim) }
    var recentPins by remember { mutableStateOf(loadRecentPins(ctx)) }
    var recentRechargeCount by remember { mutableIntStateOf(loadRecentRechargeCount(ctx)) }
    var usedPinRecords by remember { mutableStateOf(loadUsedPinRecords(ctx)) }
    var detectedPins by remember { mutableStateOf<List<String>>(emptyList()) }
    var finalResponse by remember { mutableStateOf("") }
    var responseMessage by remember { mutableStateOf("No final USSD response captured yet.") }
    var responseIsError by remember { mutableStateOf(false) }
    var balanceDisplay by remember {
        mutableStateOf(BalanceChecker.currentBalanceStr.ifBlank { "KES --" })
    }
    var lastBalanceCheckedAt by remember {
        mutableLongStateOf(if (BalanceChecker.currentBalanceStr.isBlank()) 0L else System.currentTimeMillis())
    }
    var scanMessage by remember {
        mutableStateOf(defaultScanMessage)
    }
    var processedRunCount by remember { mutableIntStateOf(0) }
    var batchCompletedPins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchFailedPins by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchActivePin by remember { mutableStateOf<String?>(null) }
    var queueFreeLeftSnapshot by remember {
        mutableIntStateOf((FREE_RECHARGE_WINDOW_LIMIT - recentRechargeCount).coerceAtLeast(0))
    }

    val selectedSimLabel = remember(selectedRechargeSim, sims) {
        describeUssdSimSelection(selectedRechargeSim, sims)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
    val runNowCount = when {
        detectedPins.isNotEmpty() -> detectedPins.size
        pin.length == 16 -> 1
        else -> 0
    }
    val freeLeftCount = (FREE_RECHARGE_WINDOW_LIMIT - recentRechargeCount).coerceAtLeast(0)
    val balanceCurrency = splitScratchBalance(balanceDisplay).first
    val balanceAmount = splitScratchBalance(balanceDisplay).second
    val progressFraction = if (runNowCount == 0) 0f else processedRunCount.toFloat() / runNowCount.toFloat()
    val batchTitle = when {
        isScanningImage -> "Scan in progress"
        isProcessing && runNowCount > 1 -> "Batch in progress"
        isProcessing -> "Recharge in progress"
        detectedPins.isNotEmpty() -> "Batch ready"
        pin.length == 16 -> "Single card armed"
        else -> "Batch status"
    }
    val batchSubtitle = when {
        isScanningImage -> "Reading one image and extracting every 16-digit scratch PIN."
        isProcessing && batchActivePin != null && runNowCount > 1 ->
            "Running ${formatQueuedPinLabel(batchActivePin.orEmpty())} on $selectedSimLabel with a fixed 2-second gap."
        isProcessing -> "Submitting the selected card on $selectedSimLabel and waiting for the final USSD response."
        detectedPins.isNotEmpty() ->
            "Queue built from one image. Review the card rail, confirm the SIM, then run the batch."
        pin.length == 16 -> "Manual PIN loaded. Select the SIM and run a single recharge."
        else -> "Idle - waiting for a scan to build the queue."
    }
    val tickerText = buildString {
        append("*141*")
        append(
            when {
                batchActivePin != null -> batchActivePin!!.chunked(4).joinToString("-")
                pin.length == 16 -> pin.chunked(4).joinToString("-")
                else -> "0000-0000-0000-0000"
            }
        )
        append("#  ·  ")
        append(
            when {
                isScanningImage -> "SCANNING IMAGE"
                isProcessing && runNowCount > 1 -> "BATCH RUNNING"
                isProcessing -> "RECHARGE RUNNING"
                detectedPins.isNotEmpty() -> "QUEUE ARMED"
                else -> "AWAITING SCAN"
            }
        )
        append("  ·  ")
        append(selectedSimLabel.uppercase(Locale.getDefault()))
        append("  ·  ")
        append(processedRunCount)
        append(" OF ")
        append(runNowCount)
        append(" RUN")
    }
    val consoleText = when {
        isScanningImage -> "Reading image for 16-digit scratch card PINs..."
        isProcessing && batchActivePin != null && runNowCount > 1 ->
            "Running ${formatQueuedPinLabel(batchActivePin.orEmpty())} on $selectedSimLabel. Waiting for the final USSD response."
        isProcessing -> "Submitting recharge on $selectedSimLabel. Waiting for the final USSD response."
        processedRunCount > 0 || finalResponse.isNotBlank() -> responseMessage
        detectedPins.isNotEmpty() -> "Detected ${detectedPins.size} scratch card PIN${if (detectedPins.size > 1) "s" else ""}. Ready to recharge."
        else -> defaultScanMessage
    }
    val consoleTint = when {
        responseIsError -> C.red
        isScanningImage || isProcessing -> C.orange
        detectedPins.isNotEmpty() -> C.green
        else -> C.t3
    }

    suspend fun runRechargeAttempt(
        targetPin: String,
        sourceLabel: String,
        rechargeSimForRun: Int,
        rechargeSimLabelForRun: String,
        showToast: Boolean
    ): ScratchCardRechargeResult {
        pin = targetPin
        finalResponse = ""
        responseMessage = "Waiting for final USSD response..."
        responseIsError = false
        return when (val result = startScratchCardRecharge(ctx, targetPin, rechargeSimForRun)) {
            is ScratchCardRechargeResult.Completed -> {
                val normalizedResponse = normalizeScratchResponse(result.response)
                saveRecentPin(ctx, targetPin, normalizedResponse)
                saveRechargeTimestamp(ctx)
                saveUsedPinRecord(ctx, targetPin, rechargeSimForRun, normalizedResponse)
                recentPins = loadRecentPins(ctx)
                recentRechargeCount = loadRecentRechargeCount(ctx)
                usedPinRecords = loadUsedPinRecords(ctx)
                finalResponse = normalizedResponse
                responseMessage = "$sourceLabel completed on $rechargeSimLabelForRun."
                if (showToast) {
                    Toast.makeText(ctx, "$sourceLabel completed on $rechargeSimLabelForRun.", Toast.LENGTH_SHORT).show()
                }
                result
            }
            is ScratchCardRechargeResult.Failed -> {
                responseIsError = true
                finalResponse = result.message
                responseMessage = "$sourceLabel failed on $rechargeSimLabelForRun."
                if (showToast) {
                    Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                }
                result
            }
        }
    }

    fun startRecharge(targetPins: List<String>, sourceLabel: String) {
        val cleanPins = targetPins.filter { it.length == 16 }
        if (cleanPins.isEmpty()) {
            Toast.makeText(ctx, "Please enter a complete 16-digit PIN", Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessing || isScanningImage) return
        if (sims.isEmpty()) {
            Toast.makeText(ctx, "No SIM available for scratch recharge.", Toast.LENGTH_SHORT).show()
            return
        }
        val rechargeSimForRun = selectedRechargeSim
        val rechargeSimLabelForRun = selectedSimLabel
        val totalRuns = cleanPins.size

        queueFreeLeftSnapshot = freeLeftCount
        batchCompletedPins = emptySet()
        batchFailedPins = emptySet()
        batchActivePin = null
        processedRunCount = 0

        scope.launch {
            isProcessing = true
            cleanPins.forEachIndexed { index, targetPin ->
                batchActivePin = targetPin
                val runLabel = if (totalRuns > 1) "Card ${index + 1} of $totalRuns" else sourceLabel
                val result = runRechargeAttempt(
                    targetPin = targetPin,
                    sourceLabel = runLabel,
                    rechargeSimForRun = rechargeSimForRun,
                    rechargeSimLabelForRun = rechargeSimLabelForRun,
                    showToast = totalRuns == 1
                )
                processedRunCount = index + 1
                when (result) {
                    is ScratchCardRechargeResult.Completed -> batchCompletedPins = batchCompletedPins + targetPin
                    is ScratchCardRechargeResult.Failed -> batchFailedPins = batchFailedPins + targetPin
                }
                if (index < cleanPins.lastIndex) {
                    batchActivePin = null
                    responseMessage = "Holding the fixed 2-second gap before the next card."
                    delay(2_000L)
                }
            }
            batchActivePin = null
            isProcessing = false
            if (totalRuns > 1) {
                val successCount = batchCompletedPins.size
                val failureCount = batchFailedPins.size
                responseIsError = failureCount > 0
                responseMessage = if (failureCount == 0) {
                    "Batch finished: $successCount of $totalRuns cards completed on $rechargeSimLabelForRun."
                } else {
                    "Batch finished: $successCount completed, $failureCount failed on $rechargeSimLabelForRun."
                }
                Toast.makeText(
                    ctx,
                    "Batch finished: $successCount/$totalRuns completed on $rechargeSimLabelForRun.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            recentRechargeCount = loadRecentRechargeCount(ctx)
            usedPinRecords = loadUsedPinRecords(ctx)
        }
    }

    fun requestBalanceRefresh() {
        if (isRefreshingBalance || BalanceChecker.checking) return
        isRefreshingBalance = true
        BalanceChecker.requestBalanceCheck(ctx)
        scope.launch {
            repeat(60) {
                if (!BalanceChecker.checking) return@repeat
                delay(250)
            }
            val latest = BalanceChecker.currentBalanceStr
            if (latest.isNotBlank()) {
                balanceDisplay = latest
                lastBalanceCheckedAt = System.currentTimeMillis()
            }
            isRefreshingBalance = false
        }
    }

    LaunchedEffect(freeLeftCount, detectedPins, isProcessing) {
        if (detectedPins.isEmpty() && !isProcessing) {
            queueFreeLeftSnapshot = freeLeftCount
        }
    }

    val onClearScan: () -> Unit = {
        if (!isProcessing && !isScanningImage) {
            pin = ""
            detectedPins = emptyList()
            scanMessage = defaultScanMessage
            processedRunCount = 0
            batchCompletedPins = emptySet()
            batchFailedPins = emptySet()
            batchActivePin = null
            responseIsError = false
            responseMessage = "Scratch queue cleared."
            finalResponse = ""
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isScanningImage = true
            detectedPins = emptyList()
            processedRunCount = 0
            batchCompletedPins = emptySet()
            batchFailedPins = emptySet()
            batchActivePin = null
            scanMessage = "Reading image for 16-digit scratch card PINs..."
            val extractedPins = runCatching { scanScratchCardPins(ctx, uri) }
                .getOrElse {
                    emptyList()
                }
            isScanningImage = false

            if (extractedPins.isEmpty()) {
                pin = ""
                scanMessage = "No 16-digit scratch-card PIN was found in that image."
                Toast.makeText(ctx, "No 16-digit PIN found in the selected image.", Toast.LENGTH_LONG).show()
            } else {
                queueFreeLeftSnapshot = freeLeftCount
                detectedPins = extractedPins
                pin = extractedPins.first()
                scanMessage = if (extractedPins.size == 1) {
                    "Detected 1 scratch card PIN. Select the recharge SIM, then run the recharge."
                } else {
                    "Detected ${extractedPins.size} scratch card PINs. Queue is ready for batch recharge."
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopStart)
                .padding(top = 24.dp)
                .background(
                    Brush.radialGradient(
                        listOf(C.orange.copy(alpha = 0.14f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(360.dp)
                .align(Alignment.TopEnd)
                .padding(top = 72.dp)
                .background(
                    Brush.radialGradient(
                        listOf(C.green.copy(alpha = 0.10f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            ScratchScreenHeader(
                onBack = onBack,
                simLabel = selectedSimLabel,
                readyCount = runNowCount,
                tickerText = tickerText,
                isBusy = isProcessing || isScanningImage
            )

            Spacer(Modifier.height(16.dp))

            ScratchBalanceCard(
                currency = balanceCurrency,
                amount = balanceAmount,
                simLabel = selectedSimLabel,
                checkedLabel = formatScratchCheckedLabel(lastBalanceCheckedAt, isRefreshingBalance),
                isRefreshing = isRefreshingBalance,
                onRefresh = ::requestBalanceRefresh
            )

            Spacer(Modifier.height(18.dp))

            ScratchBulkCard(
                freeLeftCount = freeLeftCount,
                runNowCount = runNowCount,
                processedRunCount = processedRunCount,
                progressFraction = progressFraction,
                selectedSimLabel = selectedSimLabel,
                sims = sims,
                selectedRechargeSim = selectedRechargeSim,
                selectedPin = pin,
                scanMessage = scanMessage,
                detectedPins = detectedPins,
                queueFreeLeftCount = queueFreeLeftSnapshot,
                batchTitle = batchTitle,
                batchSubtitle = batchSubtitle,
                consoleText = consoleText,
                consoleTint = consoleTint,
                batchActivePin = batchActivePin,
                batchCompletedPins = batchCompletedPins,
                batchFailedPins = batchFailedPins,
                isBusy = isProcessing || isScanningImage,
                onRechargeSimChange = { selectedRechargeSim = it },
                onPinChange = { raw -> pin = raw.filter { it.isDigit() }.take(16) },
                onPinClick = { pin = it },
                onClearScan = onClearScan,
                onRecharge = {
                    val pinsToRun = if (detectedPins.isNotEmpty()) detectedPins else listOf(pin)
                    val sourceLabel = if (detectedPins.size > 1) "Batch" else if (detectedPins.isNotEmpty()) "Scanned card" else "Manual card"
                    startRecharge(pinsToRun, sourceLabel)
                },
                onPickImage = { imagePickerLauncher.launch("image/*") }
            )

            Spacer(Modifier.height(18.dp))

            ScratchResponseCard(
                message = responseMessage,
                body = finalResponse.ifBlank { "No final USSD response captured yet." },
                isError = responseIsError
            )

            Spacer(Modifier.height(18.dp))

            ScratchRecentPinsCard(
                recentPins = recentPins,
                onPinClick = { pin = it }
            )

            Spacer(Modifier.height(28.dp))

            ScratchUsedPinsCard(
                records = usedPinRecords,
                sims = sims,
                onPinClick = { pin = it },
                onClearAll = {
                    clearUsedPinRecords(ctx)
                    usedPinRecords = emptyList()
                }
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ScratchScreenHeader(
    onBack: () -> Unit,
    simLabel: String,
    readyCount: Int,
    tickerText: String,
    isBusy: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.surface.copy(alpha = 0.76f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(C.green)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AGT-042",
                    color = C.orange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.width(8.dp))
                Text("·", color = C.border, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isBusy) "ACTIVE" else "LINKED",
                    color = C.t3,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    simLabel.uppercase(Locale.getDefault()),
                    color = C.t3,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clickable(onClick = onBack),
                color = C.card.copy(alpha = 0.96f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.60f))
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t2)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Scratch Card Recharge",
                    color = C.t1,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scan one image, detect every 16-digit PIN, then recharge from one dispatch panel.",
                    color = C.t2,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = C.surface.copy(alpha = 0.88f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.28f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tickerText,
                    color = if (isBusy) C.blue else C.t3,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        ScratchPill(
            text = if (isBusy) "Dispatch active" else "${String.format(Locale.getDefault(), "%02d", readyCount)} card(s) ready",
            tint = if (isBusy) C.orange else C.green
        )
    }
}

@Composable
private fun ScratchBalanceCard(
    currency: String,
    amount: String,
    simLabel: String,
    checkedLabel: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0E1310),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF263029))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0F1511),
                            Color(0xFF0A0F0C)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(C.green)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "AIRTIME BALANCE",
                        color = Color(0xFF5C8F83),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = !isRefreshing, onClick = onRefresh),
                    color = Color(0x1474E6D8),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF263029))
                ) {
                    Box(
                        modifier = Modifier.size(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Autorenew,
                            contentDescription = null,
                            tint = Color(0xFF5C8F83),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    currency,
                    color = Color(0xFF3D6B66),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    amount,
                    color = Color(0xFF74E6D8),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    simLabel,
                    color = Color(0xFF5C8F83),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    checkedLabel,
                    color = Color(0xFF4C5A54),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ScratchBulkCard(
    freeLeftCount: Int,
    runNowCount: Int,
    processedRunCount: Int,
    progressFraction: Float,
    selectedSimLabel: String,
    sims: List<android.telephony.SubscriptionInfo>,
    selectedRechargeSim: Int,
    selectedPin: String,
    scanMessage: String,
    detectedPins: List<String>,
    queueFreeLeftCount: Int,
    batchTitle: String,
    batchSubtitle: String,
    consoleText: String,
    consoleTint: Color,
    batchActivePin: String?,
    batchCompletedPins: Set<String>,
    batchFailedPins: Set<String>,
    isBusy: Boolean,
    onRechargeSimChange: (Int) -> Unit,
    onPinChange: (String) -> Unit,
    onPinClick: (String) -> Unit,
    onClearScan: () -> Unit,
    onRecharge: () -> Unit,
    onPickImage: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = C.cardHi.copy(alpha = 0.96f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.70f))
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                C.card.copy(alpha = 0.98f),
                                C.surface.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(batchTitle, color = C.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(batchSubtitle, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Surface(
                        color = C.bg.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.46f))
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = C.green, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ScratchCounterTile(
                        label = "FREE LEFT",
                        value = freeLeftCount,
                        accent = C.green,
                        modifier = Modifier.weight(1f)
                    )
                    ScratchCounterTile(
                        label = "RUN NOW",
                        value = runNowCount,
                        accent = C.orange,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.bg.copy(alpha = 0.76f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.28f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(Brush.horizontalGradient(listOf(C.blue, C.green)))
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${processedRunCount.coerceAtMost(runNowCount)} of $runNowCount cards run",
                        color = C.t3,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "${(progressFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = C.t3,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "BATCH RECHARGE",
                color = C.orange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Brush.horizontalGradient(listOf(C.border.copy(alpha = 0.72f), Color.Transparent)))
            )
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = C.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.60f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                ScratchRailStep(
                    step = "1",
                    title = "Scan a single image",
                    body = "Pick one photo from your phone. OCR extracts each 16-digit scratch PIN it can find.",
                    active = true
                )
                ScratchRailStep(
                    step = "2",
                    title = "Recharge in order",
                    body = "Queued cards run one by one on $selectedSimLabel, with the final USSD response captured before the next card.",
                    tag = "2s gap between cards"
                )
                ScratchRailStep(
                    step = "3",
                    title = "Free, then paid",
                    body = "The first $FREE_RECHARGE_WINDOW_LIMIT cards in each 24-hour window are free. Extra cards spill into paid overflow."
                )
                ScratchRailStep(
                    step = "!",
                    title = "Before you start",
                    body = "Your phone must support silent USSD for unattended batch recharge to finish cleanly.",
                    warning = true,
                    isLast = true
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = C.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.60f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = selectedPin,
                    onValueChange = onPinChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = {
                        Text("Manual 16-digit PIN override", color = C.t3)
                    },
                    supportingText = {
                        Text(
                            text = if (selectedPin.isBlank()) {
                                "Manual entry stays available when you do not want to scan an image."
                            } else {
                                "${selectedPin.length}/16 digits loaded"
                            },
                            color = C.t3,
                            fontSize = 12.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    colors = fieldColors()
                )

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onPickImage,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C.orange,
                        contentColor = C.bg,
                        disabledContainerColor = C.orange.copy(alpha = 0.42f),
                        disabledContentColor = C.bg.copy(alpha = 0.56f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isBusy && detectedPins.isEmpty()) "Scanning..." else "Pick Image",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRecharge,
                        enabled = (detectedPins.isNotEmpty() || selectedPin.length == 16) && !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.64f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = C.card.copy(alpha = 0.88f),
                            contentColor = C.t1,
                            disabledContainerColor = C.card.copy(alpha = 0.46f),
                            disabledContentColor = C.t3
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isBusy) "Running..." else if (detectedPins.size > 1) "Run Batch" else "Top Up Now",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = onClearScan,
                        enabled = !isBusy && (detectedPins.isNotEmpty() || selectedPin.isNotBlank()),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.42f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = C.t3,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = C.t3.copy(alpha = 0.60f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear Scan", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.bg.copy(alpha = 0.66f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.26f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                        Text(
                            "RECHARGE SIM",
                            color = C.t3,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            selectedSimLabel,
                            color = C.t1,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                UssdSimPickerRow(
                    title = "Choose SIM",
                    sims = sims,
                    current = selectedRechargeSim,
                    onSelect = onRechargeSimChange
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = C.surface.copy(alpha = 0.90f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.34f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(consoleTint)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    consoleText,
                    color = C.t2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp
                )
            }
        }

        if (detectedPins.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = C.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.54f))
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "CARD QUEUE",
                            color = C.orange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                        Text(
                            "${detectedPins.size} of ${maxOf(FREE_RECHARGE_WINDOW_LIMIT, detectedPins.size)} detected",
                            color = C.t3,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        scanMessage,
                        color = C.t3,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    Column {
                        for (index in 0 until maxOf(FREE_RECHARGE_WINDOW_LIMIT, detectedPins.size)) {
                            val queuedPin = detectedPins.getOrNull(index)
                            val isOverflow = index >= queueFreeLeftCount
                            val isSelected = queuedPin != null && queuedPin == selectedPin
                            val isActive = queuedPin != null && queuedPin == batchActivePin
                            val isCompleted = queuedPin != null && batchCompletedPins.contains(queuedPin)
                            val isFailed = queuedPin != null && batchFailedPins.contains(queuedPin)
                            ScratchQueueStub(
                                index = index + 1,
                                pin = queuedPin,
                                status = when {
                                    queuedPin == null -> "EMPTY"
                                    isActive -> "LIVE"
                                    isFailed -> "FAIL"
                                    isCompleted -> "DONE"
                                    isOverflow -> "PAID"
                                    else -> "FREE"
                                },
                                tint = when {
                                    queuedPin == null -> C.t3
                                    isFailed -> C.red
                                    isOverflow -> C.orange
                                    else -> C.green
                                },
                                selected = isSelected,
                                onClick = queuedPin?.let { { onPinClick(it) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScratchRailStep(
    step: String,
    title: String,
    body: String,
    tag: String? = null,
    active: Boolean = false,
    warning: Boolean = false,
    isLast: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = when {
                    warning -> C.red.copy(alpha = 0.10f)
                    active -> C.orange.copy(alpha = 0.10f)
                    else -> C.bg.copy(alpha = 0.88f)
                },
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(
                    1.dp,
                    when {
                        warning -> C.red.copy(alpha = 0.60f)
                        active -> C.orange.copy(alpha = 0.50f)
                        else -> C.border.copy(alpha = 0.52f)
                    }
                )
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        step,
                        color = when {
                            warning -> C.red
                            active -> C.orange
                            else -> C.t2
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(52.dp)
                        .background(C.border.copy(alpha = 0.30f))
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
            if (tag != null) {
                Spacer(Modifier.height(8.dp))
                ScratchPill(text = tag, tint = C.blue)
            }
            if (!isLast) {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ScratchQueueStub(
    index: Int,
    pin: String?,
    status: String,
    tint: Color,
    selected: Boolean,
    onClick: (() -> Unit)?
) {
    val background = when {
        pin == null -> C.bg.copy(alpha = 0.18f)
        selected -> tint.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = background,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = if (selected) 0.52f else 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                String.format(Locale.getDefault(), "%02d", index),
                color = C.t3,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(24.dp)
            )
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(C.bg.copy(alpha = 0.94f))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (pin == null) C.t3.copy(alpha = 0.50f) else tint)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = pin?.let { formatQueuedPinLabel(it) } ?: "•••• •••• •••• ••••",
                color = if (pin == null) C.t3 else tint,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                status,
                color = if (pin == null) C.t3 else tint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp
            )
        }
    }
}

private fun formatQueuedPinLabel(pin: String): String {
    if (pin.length != 16) return pin
    return "•••• •••• •••• ${pin.takeLast(4)}"
}

@Composable
private fun ScratchInnerPanel(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.surface.copy(alpha = 0.52f),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            content = {
                Text(
                    text = title,
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    color = C.t3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(14.dp))
                content()
            }
        )
    }
}

@Composable
private fun ScratchCounterTile(
    label: String,
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = C.t3, fontSize = 12.sp, letterSpacing = 1.6.sp)
            Text(
                String.format(Locale.getDefault(), "%02d", value),
                color = accent,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun ScratchInlineInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    trailingText: String,
    trailingEnabled: Boolean,
    onTrailingClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.36f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = C.cyanDim,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = C.blue, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = C.t3, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    value,
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier.clickable(enabled = trailingEnabled, onClick = onTrailingClick),
                color = if (trailingEnabled) C.greenDim else C.surface.copy(alpha = 0.26f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(
                    1.dp,
                    if (trailingEnabled) C.green.copy(alpha = 0.40f) else C.border.copy(alpha = 0.30f)
                )
            ) {
                Text(
                    trailingText,
                    color = if (trailingEnabled) C.green else C.blue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ScratchResponseCard(message: String, body: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) C.cardHi.copy(alpha = 0.90f) else C.greenDim.copy(alpha = 0.92f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, if (isError) C.red.copy(alpha = 0.26f) else C.green.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Final USSD response",
                        color = C.t1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The final device response appears here after each recharge attempt.",
                        color = C.t3,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                ScratchPill(
                    text = if (isError) "Attention" else "Latest result",
                    tint = if (isError) C.red else C.green
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                message,
                color = if (isError) C.red else C.green,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            ScratchResponsePreview(
                response = body,
                tint = if (isError) C.red else C.green,
                emptyLabel = "No final USSD response captured yet."
            )
        }
    }
}

@Composable
private fun ScratchRecentPinsCard(recentPins: List<ScratchRecentPinRecord>, onPinClick: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.58f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            ScratchCardHeader(
                icon = Icons.Rounded.History,
                title = "Recent recharges",
                subtitle = "Reuse the most recent successful scratch PINs."
            )
            Spacer(Modifier.height(12.dp))
            if (recentPins.isEmpty()) {
                ScratchEmptyState("No recent recharges yet")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentPins.forEachIndexed { index, recentPin ->
                        RecentPinItem(
                            index = index + 1,
                            record = recentPin,
                            onClick = { onPinClick(recentPin.pin) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScratchUsedPinsCard(
    records: List<ScratchUsedPinRecord>,
    sims: List<android.telephony.SubscriptionInfo>,
    onPinClick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.58f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ScratchCardHeader(
                    icon = Icons.Rounded.History,
                    title = "Used PIN records",
                    subtitle = "Recent usage history for the selected scratch cards.",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = records.isNotEmpty(), onClick = onClearAll),
                    color = C.surface.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.30f))
                ) {
                    Text(
                        "Clear all",
                        color = if (records.isNotEmpty()) C.red else C.t3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "These records can be cleared manually and they expire automatically after 1 hour.",
                color = C.t3,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            if (records.isEmpty()) {
                ScratchEmptyState("No used PIN records")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    records.forEachIndexed { index, record ->
                        UsedPinRecordItem(
                            index = index + 1,
                            record = record,
                            simLabel = describeUssdSimSelection(record.simSelection, sims),
                            onClick = { onPinClick(record.pin) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsedPinRecordItem(
    index: Int,
    record: ScratchUsedPinRecord,
    simLabel: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = C.orange)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(record.pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${simLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}  ·  ${formatUsedPinAgeLabel(record.timestamp)}",
                    color = C.t3,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = record.finalResponse,
                    tint = C.orange,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(text = "Reuse", tint = C.orange)
        }
    }
}

@Composable
private fun ScratchMetricHeading(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun RecentPinItem(
    index: Int,
    record: ScratchRecentPinRecord,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = C.cyan)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(record.pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatRecentPinAgeLabel(record.timestamp)}  ·  Tap to move this PIN back into the recharge field.",
                    color = C.t3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = record.finalResponse,
                    tint = C.cyan,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(text = "Use", tint = C.cyan)
        }
    }
}

@Composable
private fun ScratchHeroStat(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(
                label.uppercase(Locale.getDefault()),
                color = C.t3,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                color = C.t1,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScratchPill(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.34f))
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScratchCardLabel(index: Int, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.36f))
    ) {
        Text(
            text = "Scratch card $index",
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ScratchResponsePreview(
    response: String,
    tint: Color,
    emptyLabel: String
) {
    val normalizedResponse = response.ifBlank { emptyLabel }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tint.copy(alpha = 0.09f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "Final USSD response",
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = normalizedResponse,
                color = C.t2,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScratchCardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = C.surface.copy(alpha = 0.62f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.30f))
        ) {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = C.t2, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = C.t3, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ScratchEmptyState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.surface.copy(alpha = 0.42f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.24f))
    ) {
        Text(
            message,
            color = C.t3,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun ScratchDetectedPinItem(
    index: Int,
    pin: String,
    finalResponse: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) C.greenDim.copy(alpha = 0.28f) else C.surface.copy(alpha = 0.58f),
        border = BorderStroke(
            1.dp,
            if (selected) C.green.copy(alpha = 0.48f) else C.border.copy(alpha = 0.34f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScratchCardLabel(index = index, tint = if (selected) C.green else C.blue)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formatPinForDisplay(pin),
                    color = C.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (selected) "Currently selected for recharge." else "Tap to use this OCR result.",
                    color = if (selected) C.green else C.t3,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                ScratchResponsePreview(
                    response = finalResponse,
                    tint = if (selected) C.green else C.blue,
                    emptyLabel = "No final USSD response saved for this card yet."
                )
            }
            Spacer(Modifier.width(12.dp))
            ScratchPill(
                text = if (selected) "Selected" else "Use",
                tint = if (selected) C.green else C.cyan
            )
        }
    }
}

private fun formatPinForDisplay(pin: String): String {
    if (pin.length != 16) return pin
    return pin.chunked(4).joinToString(" ")
}

private fun loadRecentPins(context: Context, now: Long = System.currentTimeMillis()): List<ScratchRecentPinRecord> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val stored = prefs.getString(KEY_RECENT_PINS, "").orEmpty()
    val cleaned = when {
        stored.isBlank() -> emptyList()
        "|" !in stored && ";" !in stored -> stored.split(",")
            .mapIndexedNotNull { index, rawPin ->
                val pin = rawPin.takeIf { it.length == 16 } ?: return@mapIndexedNotNull null
                ScratchRecentPinRecord(
                    pin = pin,
                    finalResponse = "",
                    timestamp = now - index
                )
            }
        else -> stored.split(";")
            .mapNotNull { raw ->
                val parts = raw.split("|")
                if (parts.size < 2) return@mapNotNull null
                val pin = parts[0].takeIf { it.length == 16 } ?: return@mapNotNull null
                val response = Uri.decode(parts[1]).orEmpty()
                val timestamp = parts.getOrNull(2)?.toLongOrNull() ?: now
                ScratchRecentPinRecord(
                    pin = pin,
                    finalResponse = response,
                    timestamp = timestamp
                )
            }
    }
        .distinctBy { it.pin }
        .sortedByDescending { it.timestamp }
        .take(MAX_RECENT_PINS)
    prefs.edit().putString(KEY_RECENT_PINS, encodeRecentPins(cleaned)).apply()
    return cleaned
}

private fun saveRecentPin(
    context: Context,
    pin: String,
    finalResponse: String,
    timestamp: Long = System.currentTimeMillis()
) {
    if (pin.length != 16) return
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val currentPins = loadRecentPins(context, timestamp)
        .filterNot { it.pin == pin }
        .toMutableList()
    currentPins.add(
        0,
        ScratchRecentPinRecord(
            pin = pin,
            finalResponse = normalizeScratchResponse(finalResponse),
            timestamp = timestamp
        )
    )
    prefs.edit().putString(KEY_RECENT_PINS, encodeRecentPins(currentPins.take(MAX_RECENT_PINS))).apply()
}

private fun loadRecentRechargeCount(context: Context, now: Long = System.currentTimeMillis()): Int {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val cleaned = prefs.getString(KEY_RECHARGE_HISTORY, "").orEmpty()
        .split(",")
        .mapNotNull { it.toLongOrNull() }
        .filter { now - it in 0..FREE_RECHARGE_WINDOW_MS }
    prefs.edit().putString(KEY_RECHARGE_HISTORY, cleaned.joinToString(",")).apply()
    return cleaned.size
}

private fun saveRechargeTimestamp(context: Context, timestamp: Long = System.currentTimeMillis()) {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val values = prefs.getString(KEY_RECHARGE_HISTORY, "").orEmpty()
        .split(",")
        .mapNotNull { it.toLongOrNull() }
        .filter { timestamp - it in 0..FREE_RECHARGE_WINDOW_MS }
        .toMutableList()
    values += timestamp
    prefs.edit().putString(KEY_RECHARGE_HISTORY, values.joinToString(",")).apply()
}

private fun loadUsedPinRecords(context: Context, now: Long = System.currentTimeMillis()): List<ScratchUsedPinRecord> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val cleaned = prefs.getString(KEY_USED_PIN_RECORDS, "").orEmpty()
        .split(";")
        .mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size !in 3..4) return@mapNotNull null
            val pin = parts[0].takeIf { it.length == 16 } ?: return@mapNotNull null
            val simSelection = parts[1].toIntOrNull() ?: return@mapNotNull null
            val timestamp = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (now - timestamp !in 0..USED_PIN_RECORD_RETENTION_MS) return@mapNotNull null
            ScratchUsedPinRecord(
                pin = pin,
                simSelection = simSelection,
                timestamp = timestamp,
                finalResponse = Uri.decode(parts.getOrNull(3)).orEmpty()
            )
        }
        .sortedByDescending { it.timestamp }
    prefs.edit().putString(KEY_USED_PIN_RECORDS, encodeUsedPinRecords(cleaned)).apply()
    return cleaned
}

private fun saveUsedPinRecord(
    context: Context,
    pin: String,
    simSelection: Int,
    finalResponse: String,
    timestamp: Long = System.currentTimeMillis()
) {
    val current = loadUsedPinRecords(context, timestamp)
        .filterNot { it.pin == pin }
        .toMutableList()
    current.add(
        0,
        ScratchUsedPinRecord(
            pin = pin,
            simSelection = simSelection,
            timestamp = timestamp,
            finalResponse = normalizeScratchResponse(finalResponse)
        )
    )
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_USED_PIN_RECORDS, encodeUsedPinRecords(current)).apply()
}

private fun clearUsedPinRecords(context: Context) {
    context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_USED_PIN_RECORDS)
        .apply()
}

private fun splitScratchBalance(value: String): Pair<String, String> {
    val normalized = value.trim().ifBlank { "KES --" }
    val parts = normalized.split(Regex("\\s+"), limit = 2)
    return if (parts.size == 2 && parts[0].any { it.isLetter() }) {
        parts[0].uppercase(Locale.getDefault()) to parts[1]
    } else {
        "KES" to normalized
    }
}

private fun formatScratchCheckedLabel(timestamp: Long, isRefreshing: Boolean): String {
    if (isRefreshing) return "Checking now"
    if (timestamp <= 0L) return "Tap refresh to check"
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "Checked just now"
        minutes == 1L -> "Checked 1 min ago"
        minutes < 60L -> "Checked ${minutes} min ago"
        else -> "Checked ${minutes / 60L}h ago"
    }
}

private fun formatUsedPinAgeLabel(timestamp: Long): String {
    val deltaMinutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        deltaMinutes < 1L -> "Used just now"
        deltaMinutes == 1L -> "Used 1 min ago"
        deltaMinutes < 60L -> "Used ${deltaMinutes} min ago"
        else -> "Used ${deltaMinutes / 60L}h ago"
    }
}

private fun formatRecentPinAgeLabel(timestamp: Long): String {
    val deltaMinutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 60_000L
    return when {
        deltaMinutes < 1L -> "Saved just now"
        deltaMinutes == 1L -> "Saved 1 min ago"
        deltaMinutes < 60L -> "Saved ${deltaMinutes} min ago"
        else -> "Saved ${deltaMinutes / 60L}h ago"
    }
}

private fun encodeRecentPins(records: List<ScratchRecentPinRecord>): String =
    records.joinToString(";") { "${it.pin}|${Uri.encode(it.finalResponse)}|${it.timestamp}" }

private fun encodeUsedPinRecords(records: List<ScratchUsedPinRecord>): String =
    records.joinToString(";") { "${it.pin}|${it.simSelection}|${it.timestamp}|${Uri.encode(it.finalResponse)}" }

private fun normalizeScratchResponse(response: String): String =
    response.trim().ifBlank { "USSD completed but returned an empty response." }

private suspend fun scanScratchCardPins(context: Context, imageUri: Uri): List<String> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        val image = InputImage.fromFilePath(context, imageUri)
        val text = recognizer.awaitRecognition(image)
        extractScratchPins(text)
    } finally {
        recognizer.close()
    }
}

private suspend fun TextRecognizer.awaitRecognition(image: InputImage): String =
    suspendCancellableCoroutine { cont ->
        process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resumeWith(Result.success(result.text))
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWith(Result.failure(error))
            }
    }

private fun extractScratchPins(recognizedText: String): List<String> {
    if (recognizedText.isBlank()) return emptyList()

    val results = LinkedHashSet<String>()
    Regex("""(?<![A-Za-z0-9])(?:[0-9OoQqDdIiLlSsBbZzGg][\s-]*){16}(?![A-Za-z0-9])""")
        .findAll(recognizedText)
        .forEach { match ->
            val normalized = buildString {
                match.value.forEach { ch ->
                    when {
                        ch.isWhitespace() || ch == '-' -> Unit
                        else -> normalizeOcrDigit(ch)?.let(::append)
                    }
                }
            }
            if (normalized.length == 16) {
                results += normalized
            }
        }

    if (results.isNotEmpty()) return results.toList()

    val flattened = buildString {
        recognizedText.forEach { ch ->
            normalizeOcrDigit(ch)?.let(::append)
        }
    }
    if (flattened.length < 16) return emptyList()

    for (index in 0..flattened.length - 16) {
        results += flattened.substring(index, index + 16)
    }
    return results.toList()
}

private fun normalizeOcrDigit(ch: Char): Char? = when (ch) {
    in '0'..'9' -> ch
    'O', 'o', 'Q', 'q', 'D' -> '0'
    'I', 'l', 'L' -> '1'
    'S', 's' -> '5'
    'B' -> '8'
    'Z', 'z' -> '2'
    'G', 'g' -> '6'
    else -> null
}

private suspend fun startScratchCardRecharge(
    context: Context,
    pin: String,
    simSelection: Int
): ScratchCardRechargeResult {
    val ussdCode = "*141*$pin#"
    return try {
        suspendCancellableCoroutine { cont ->
            val executed = UssdHelper.dialUssd(
                context = context,
                ussdCode = ussdCode,
                silentOnly = true,
                subIdOverride = simSelection,
                onSuccess = { response ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(ScratchCardRechargeResult.Completed(response.trim())))
                    }
                },
                onFailure = { error ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(ScratchCardRechargeResult.Failed(error)))
                    }
                }
            )

            if (!executed && cont.isActive) {
                cont.resumeWith(
                    Result.success(
                        ScratchCardRechargeResult.Failed("Unable to start silent scratch-card recharge on this phone.")
                    )
                )
            }
        }
    } catch (_: Exception) {
        ScratchCardRechargeResult.Failed("Unable to start recharge on this phone.")
    }
}
