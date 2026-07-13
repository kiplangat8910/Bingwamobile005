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
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.WarningAmber
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
        mutableStateOf("Scan one scratch card image and Bingwa will extract every 16-digit PIN it can find.")
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
    val savedResponsesByPin = remember(recentPins, usedPinRecords) {
        buildMap {
            recentPins.forEach { put(it.pin, it.finalResponse) }
            usedPinRecords.forEach { put(it.pin, it.finalResponse) }
        }
    }

    fun startRecharge(targetPin: String, sourceLabel: String) {
        if (targetPin.length != 16) {
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

        pin = targetPin
        finalResponse = ""
        responseMessage = "Waiting for final USSD response..."
        responseIsError = false
        scope.launch {
            isProcessing = true
            when (val result = startScratchCardRecharge(ctx, targetPin, rechargeSimForRun)) {
                is ScratchCardRechargeResult.Completed -> {
                    val normalizedResponse = normalizeScratchResponse(result.response)
                    saveRecentPin(ctx, targetPin, normalizedResponse)
                    saveRechargeTimestamp(ctx)
                    saveUsedPinRecord(ctx, targetPin, rechargeSimForRun, normalizedResponse)
                    recentPins = loadRecentPins(ctx)
                    recentRechargeCount = loadRecentRechargeCount(ctx)
                    usedPinRecords = loadUsedPinRecords(ctx)
                    finalResponse = normalizedResponse
                    responseMessage = "$sourceLabel recharge completed on $rechargeSimLabelForRun."
                    Toast.makeText(ctx, "$sourceLabel recharge completed on $rechargeSimLabelForRun.", Toast.LENGTH_SHORT).show()
                }
                is ScratchCardRechargeResult.Failed -> {
                    responseIsError = true
                    finalResponse = result.message
                    responseMessage = "Recharge failed."
                    Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                }
            }
            isProcessing = false
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

    val onClear: () -> Unit = {
        if (!isProcessing && !isScanningImage) {
            pin = ""
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isScanningImage = true
            detectedPins = emptyList()
            scanMessage = "Scanning image for scratch-card PIN..."
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
                detectedPins = extractedPins
                pin = extractedPins.first()
                scanMessage = if (extractedPins.size == 1) {
                    "Detected 1 PIN. Select the recharge SIM, then tap Top up."
                } else {
                    "Detected ${extractedPins.size} PINs. Select the recharge SIM, then tap Top up."
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
                .padding(horizontal = 16.dp)
        ) {
            ScratchScreenHeader(
                onBack = onBack,
                simLabel = selectedSimLabel,
                freeLeftCount = freeLeftCount,
                readyCount = runNowCount,
                isBusy = isProcessing || isScanningImage
            )
            Spacer(Modifier.height(14.dp))

            ScratchStatusStrip(
                simLabel = selectedSimLabel,
                isBusy = isProcessing || isScanningImage,
                readyCount = runNowCount
            )

            Spacer(Modifier.height(16.dp))

            ScratchBulkCard(
                freeLeftCount = freeLeftCount,
                runNowCount = runNowCount,
                selectedSimLabel = selectedSimLabel,
                sims = sims,
                selectedRechargeSim = selectedRechargeSim,
                selectedPin = pin,
                scanMessage = scanMessage,
                detectedPins = detectedPins,
                savedResponsesByPin = savedResponsesByPin,
                isBusy = isProcessing || isScanningImage,
                onRechargeSimChange = { selectedRechargeSim = it },
                onPinChange = { raw -> pin = raw.filter { it.isDigit() }.take(16) },
                onPinClick = { pin = it },
                onClear = onClear,
                onRecharge = { startRecharge(pin, "Manual") },
                onPickImage = { imagePickerLauncher.launch("image/*") }
            )

            Spacer(Modifier.height(18.dp))

            ScratchStepsCard()

            Spacer(Modifier.height(18.dp))

            ScratchResponseCard(
                message = responseMessage,
                body = finalResponse.ifBlank { "No final USSD response captured yet." },
                isError = responseIsError
            )

            Spacer(Modifier.height(18.dp))

            ScratchBalanceCard(
                currency = balanceCurrency,
                amount = balanceAmount,
                simLabel = selectedSimLabel,
                checkedLabel = formatScratchCheckedLabel(lastBalanceCheckedAt, isRefreshingBalance),
                isRefreshing = isRefreshingBalance,
                onRefresh = ::requestBalanceRefresh
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
    freeLeftCount: Int,
    readyCount: Int,
    isBusy: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = C.cardHi.copy(alpha = 0.98f),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            C.orange.copy(alpha = 0.18f),
                            C.cardHi.copy(alpha = 0.96f),
                            C.surface.copy(alpha = 0.98f)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.clickable(onClick = onBack),
                    color = C.surface.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.44f))
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t1)
                    }
                }
                ScratchPill(
                    text = if (isBusy) "Recharge running" else "Ready to recharge",
                    tint = if (isBusy) C.orange else C.green
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "Scratch Card Recharge",
                color = C.t1,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan a card photo or enter a PIN manually, choose the recharge SIM, and top up from one clean workspace.",
                color = C.t2,
                fontSize = 14.sp,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScratchHeroStat(
                    label = "Active SIM",
                    value = simLabel,
                    accent = C.blue,
                    modifier = Modifier.weight(1f)
                )
                ScratchHeroStat(
                    label = "Ready",
                    value = String.format(Locale.getDefault(), "%02d cards", readyCount),
                    accent = C.green,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            ScratchHeroStat(
                label = "Free left today",
                value = "$freeLeftCount of $FREE_RECHARGE_WINDOW_LIMIT recharges remaining",
                accent = C.orange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ScratchStatusStrip(simLabel: String, isBusy: Boolean, readyCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScratchStatusTile(
            label = "Status",
            value = if (isBusy) "Busy" else "Idle",
            accent = if (isBusy) C.orange else C.green,
            modifier = Modifier.weight(1f)
        )
        ScratchStatusTile(
            label = "Recharge line",
            value = simLabel,
            accent = C.blue,
            modifier = Modifier.weight(1f)
        )
        ScratchStatusTile(
            label = "Queue",
            value = if (readyCount > 0) "$readyCount selected" else "Waiting",
            accent = C.orange,
            modifier = Modifier.weight(1f)
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
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, C.green.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF132B20).copy(alpha = 0.84f),
                            Color(0xFF101821).copy(alpha = 0.94f),
                            Color(0xFF09110D).copy(alpha = 0.96f)
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
                Column(modifier = Modifier.weight(1f)) {
                    ScratchMetricHeading(text = "AIRTIME BALANCE", accent = C.blue)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Balance checks use the current recharge line so you can verify the result immediately after topping up.",
                        color = C.t2,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = !isRefreshing, onClick = onRefresh),
                    color = C.surface.copy(alpha = 0.60f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.40f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Autorenew,
                            contentDescription = null,
                            tint = C.green,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRefreshing) "Checking" else "Refresh",
                            color = C.t1,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    currency,
                    color = C.t2,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    amount,
                    color = C.green.copy(alpha = 0.96f),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScratchPill(
                    text = simLabel,
                    tint = C.blue,
                    modifier = Modifier.weight(1f)
                )
                ScratchPill(
                    text = checkedLabel,
                    tint = C.green,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ScratchBulkCard(
    freeLeftCount: Int,
    runNowCount: Int,
    selectedSimLabel: String,
    sims: List<android.telephony.SubscriptionInfo>,
    selectedRechargeSim: Int,
    selectedPin: String,
    scanMessage: String,
    detectedPins: List<String>,
    savedResponsesByPin: Map<String, String>,
    isBusy: Boolean,
    onRechargeSimChange: (Int) -> Unit,
    onPinChange: (String) -> Unit,
    onPinClick: (String) -> Unit,
    onClear: () -> Unit,
    onRecharge: () -> Unit,
    onPickImage: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.98f),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.70f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF233247).copy(alpha = 0.72f),
                            Color(0xFF121C2B).copy(alpha = 0.96f),
                            Color(0xFF101622).copy(alpha = 0.98f)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Recharge control center",
                        color = C.t1,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use OCR for quick capture or type the PIN yourself, then recharge from the same panel without jumping between sections.",
                        color = C.t2,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
                Spacer(Modifier.width(14.dp))
                ScratchPill(
                    text = if (isBusy) "Processing" else "Manual + OCR",
                    tint = if (isBusy) C.orange else C.cyan
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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

            Spacer(Modifier.height(18.dp))

            Text(
                "Scratch PIN",
                color = C.t1,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = selectedPin,
                onValueChange = onPinChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                placeholder = {
                    Text("Enter or paste a 16-digit PIN", color = C.t3)
                },
                supportingText = {
                    Text(
                        text = if (selectedPin.isBlank()) {
                            "OCR results, recents, and used records all feed into this field."
                        } else {
                            "${selectedPin.length}/16 digits entered"
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

            if (selectedPin.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.surface.copy(alpha = 0.58f),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.30f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Selected PIN", color = C.t3, fontSize = 11.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                formatPinForDisplay(selectedPin),
                                color = C.t1,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            modifier = Modifier.clickable(enabled = !isBusy, onClick = onClear),
                            color = C.surface.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.30f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = null,
                                    tint = C.t2,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Clear", color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onPickImage,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.64f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C.surface.copy(alpha = 0.42f),
                        contentColor = C.t1,
                        disabledContainerColor = C.surface.copy(alpha = 0.22f),
                        disabledContentColor = C.t3
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan image", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onRecharge,
                    enabled = selectedPin.length == 16 && !isBusy,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C.orange,
                        contentColor = C.bg,
                        disabledContainerColor = C.orange.copy(alpha = 0.40f),
                        disabledContentColor = C.bg.copy(alpha = 0.56f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isBusy) "Working..." else "Top up now",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            ScratchInlineInfoCard(
                icon = Icons.Rounded.Search,
                title = if (detectedPins.isEmpty()) "SCAN STATUS" else "OCR RESULTS",
                value = scanMessage,
                trailingText = if (detectedPins.isEmpty()) "READY" else "${detectedPins.size} FOUND",
                trailingEnabled = false,
                onTrailingClick = {}
            )

            Spacer(Modifier.height(12.dp))

            ScratchInlineInfoCard(
                icon = Icons.Rounded.SimCard,
                title = "RECHARGE SIM",
                value = selectedSimLabel,
                trailingText = "SELECTED",
                trailingEnabled = false,
                onTrailingClick = {}
            )

            UssdSimPickerRow(
                title = "Recharge SIM",
                sims = sims,
                current = selectedRechargeSim,
                onSelect = onRechargeSimChange
            )

            if (detectedPins.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ScratchSectionHeader(
                    title = "Detected cards",
                    subtitle = "Cards stay in OCR order so you can top up them exactly as they appear."
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detectedPins.forEachIndexed { index, detectedPin ->
                        ScratchDetectedPinItem(
                            index = index + 1,
                            pin = detectedPin,
                            finalResponse = savedResponsesByPin[detectedPin].orEmpty(),
                            selected = detectedPin == selectedPin,
                            onClick = { onPinClick(detectedPin) }
                        )
                    }
                }
            }
        }
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
private fun ScratchStepsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.98f),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.60f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF172132).copy(alpha = 0.94f),
                            Color(0xFF111927).copy(alpha = 0.98f)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            ScratchSectionHeader(
                title = "How it works",
                subtitle = "The flow below matches the redesigned control panel above."
            )
            Spacer(Modifier.height(16.dp))
            ScratchStepRow(
                badge = "1",
                badgeAccent = C.orange,
                title = "Capture or enter the PIN",
                body = "Use the scan button for OCR, or type the 16-digit scratch PIN manually when you already have it."
            )
            Spacer(Modifier.height(14.dp))
            ScratchStepRow(
                badge = "2",
                badgeAccent = C.blue,
                title = "Choose the recharge line",
                body = "Review OCR results, keep the correct PIN selected, and confirm which SIM should dial the recharge code.",
                chip = "same control panel"
            )
            Spacer(Modifier.height(14.dp))
            ScratchStepRow(
                badge = "3",
                badgeAccent = C.green,
                title = "Top up and verify",
                body = "Run the recharge, then use the response and balance cards below to confirm what happened."
            )
            Spacer(Modifier.height(14.dp))
            ScratchWarningRow(
                title = "Before you start",
                body = "Your phone must support silent USSD and accessibility automation for this flow to finish unattended."
            )
        }
    }
}

@Composable
private fun ScratchStepRow(
    badge: String,
    badgeAccent: Color,
    title: String,
    body: String,
    chip: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = badgeAccent.copy(alpha = 0.10f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, badgeAccent.copy(alpha = 0.55f))
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = badgeAccent, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = C.t2, fontSize = 14.sp, lineHeight = 23.sp)
            chip?.let {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = C.greenDim,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, C.green.copy(alpha = 0.45f))
                ) {
                    Text(
                        it,
                        color = C.green,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScratchWarningRow(title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            color = C.redDim,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, C.red.copy(alpha = 0.70f))
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = C.red, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = C.t2, fontSize = 14.sp, lineHeight = 23.sp)
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
private fun ScratchStatusTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.cardHi.copy(alpha = 0.72f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.42f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(label, color = C.t3, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                color = C.t1,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
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
private fun ScratchSectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = C.t3, fontSize = 12.sp, lineHeight = 18.sp)
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
