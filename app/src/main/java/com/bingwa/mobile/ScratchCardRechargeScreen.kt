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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
private const val MAX_RECENT_PINS = 5
private const val FREE_RECHARGE_WINDOW_LIMIT = 10
private const val FREE_RECHARGE_WINDOW_MS = 24 * 60 * 60 * 1000L

private sealed interface ScratchCardRechargeResult {
    data class Completed(val response: String) : ScratchCardRechargeResult
    data class Failed(val message: String) : ScratchCardRechargeResult
}

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sims = remember { getAvailableSims(ctx) }

    var pin by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isScanningImage by remember { mutableStateOf(false) }
    var isRefreshingBalance by remember { mutableStateOf(false) }
    var recentPins by remember { mutableStateOf(loadRecentPins(ctx)) }
    var recentRechargeCount by remember { mutableIntStateOf(loadRecentRechargeCount(ctx)) }
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

    val selectedSimLabel = remember(sims) {
        describeUssdSimSelection(currentUssdSimSelection(ctx), sims)
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

    fun startRecharge(targetPin: String, sourceLabel: String) {
        if (targetPin.length != 16) {
            Toast.makeText(ctx, "Please enter a complete 16-digit PIN", Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessing || isScanningImage) return

        pin = targetPin
        finalResponse = ""
        responseMessage = "Waiting for final USSD response..."
        responseIsError = false
        scope.launch {
            isProcessing = true
            when (val result = startScratchCardRecharge(ctx, targetPin)) {
                is ScratchCardRechargeResult.Completed -> {
                    saveRecentPin(ctx, targetPin)
                    saveRechargeTimestamp(ctx)
                    recentPins = loadRecentPins(ctx)
                    recentRechargeCount = loadRecentRechargeCount(ctx)
                    finalResponse = result.response.ifBlank { "USSD completed but returned an empty response." }
                    responseMessage = "$sourceLabel recharge completed."
                    Toast.makeText(ctx, "$sourceLabel recharge completed.", Toast.LENGTH_SHORT).show()
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
                    "Detected 1 PIN. Recharge is starting automatically."
                } else {
                    "Detected ${extractedPins.size} PINs. The first PIN is starting automatically."
                }
                startRecharge(extractedPins.first(), "Scanned")
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
            ScratchScreenHeader(onBack = onBack)
            Spacer(Modifier.height(14.dp))

            ScratchStatusStrip(
                simLabel = selectedSimLabel,
                isBusy = isProcessing || isScanningImage,
                readyCount = runNowCount
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
                selectedSimLabel = selectedSimLabel,
                selectedPin = pin,
                scanMessage = scanMessage,
                detectedPins = detectedPins,
                isBusy = isProcessing || isScanningImage,
                onPinClick = { pin = it },
                onClear = onClear,
                onRecharge = { startRecharge(pin, "Manual") }
            )

            Spacer(Modifier.height(18.dp))

            ScratchStepsCard(
                isBusy = isProcessing || isScanningImage,
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
        }
    }
}

@Composable
private fun ScratchScreenHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "scratch-recharge.html",
            color = C.t1,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = { }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = C.t1)
        }
    }
}

@Composable
private fun ScratchStatusStrip(simLabel: String, isBusy: Boolean, readyCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.56f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.40f))
    ) {
        Text(
            text = buildString {
                append(simLabel.uppercase(Locale.getDefault()))
                append("  ·  ")
                append(if (isBusy) "BATCH LIVE" else "BATCH IDLE")
                append("  ·  ")
                append(String.format(Locale.getDefault(), "%02d READY", readyCount))
            },
            color = C.blue.copy(alpha = 0.82f),
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
        border = BorderStroke(1.dp, C.green.copy(alpha = 0.20f))
    ) {
        Box(
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
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScratchMetricHeading(text = "AIRTIME BALANCE", accent = C.blue)
                    Surface(
                        onClick = onRefresh,
                        enabled = !isRefreshing,
                        color = C.surface.copy(alpha = 0.60f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.40f))
                    ) {
                        Box(
                            modifier = Modifier.size(50.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Autorenew,
                                contentDescription = null,
                                tint = C.green,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
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

                Spacer(Modifier.height(10.dp))

                Text(
                    "$simLabel  ·  $checkedLabel",
                    color = C.t3,
                    fontSize = 13.sp,
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
    selectedSimLabel: String,
    selectedPin: String,
    scanMessage: String,
    detectedPins: List<String>,
    isBusy: Boolean,
    onPinClick: (String) -> Unit,
    onClear: () -> Unit,
    onRecharge: () -> Unit
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
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Bulk scratch recharge",
                        color = C.t1,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Scan once, review the cards, pick one SIM, then run the whole scratch workflow step by step.",
                        color = C.t2,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    )
                }
                Spacer(Modifier.width(14.dp))
                Surface(
                    color = C.surface.copy(alpha = 0.50f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.24f))
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(C.cyan.copy(alpha = 0.10f), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = C.green,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            Spacer(Modifier.height(12.dp))

            ScratchInlineInfoCard(
                icon = Icons.Rounded.Bolt,
                title = if (selectedPin.length == 16) "SELECTED PIN" else "SCAN STATUS",
                value = if (selectedPin.length == 16) formatPinForDisplay(selectedPin) else scanMessage,
                trailingText = if (selectedPin.length == 16) {
                    if (isBusy) "LIVE" else "TOP UP"
                } else {
                    "WAIT"
                },
                trailingEnabled = selectedPin.length == 16 && !isBusy,
                onTrailingClick = onRecharge
            )

            Spacer(Modifier.height(12.dp))

            ScratchInlineInfoCard(
                icon = Icons.Rounded.SimCard,
                title = "CURRENT DEFAULT SIM",
                value = selectedSimLabel,
                trailingText = "DEFAULT",
                trailingEnabled = false,
                onTrailingClick = {}
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
                        Text("Clear selected PIN", color = C.t2, fontSize = 13.sp)
                        Surface(
                            onClick = onClear,
                            enabled = !isBusy,
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

            if (detectedPins.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Detected cards",
                    color = C.t2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detectedPins.forEach { detectedPin ->
                        RecentPinItem(pin = detectedPin, onClick = { onPinClick(detectedPin) })
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
                onClick = onTrailingClick,
                enabled = trailingEnabled,
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
private fun ScratchStepsCard(isBusy: Boolean, onPickImage: () -> Unit) {
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
            ScratchStepRow(
                badge = "1",
                badgeAccent = C.orange,
                title = "Scan a single image",
                body = "Pick one photo from your phone. The app extracts every 16-digit scratch PIN it can find."
            )
            Spacer(Modifier.height(14.dp))
            ScratchStepRow(
                badge = "2",
                badgeAccent = C.blue,
                title = "Review detected cards",
                body = "Choose the right PIN, confirm the default SIM, then let silent USSD handle the recharge.",
                chip = "silent recharge"
            )
            Spacer(Modifier.height(14.dp))
            ScratchStepRow(
                badge = "3",
                badgeAccent = C.green,
                title = "Track the final response",
                body = "Every run updates the response card below so you can verify whether the recharge completed."
            )
            Spacer(Modifier.height(14.dp))
            ScratchWarningRow(
                title = "Before you start",
                body = "Your phone must support silent USSD and accessibility automation for this flow to finish unattended."
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onPickImage,
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.orange,
                    contentColor = C.bg,
                    disabledContainerColor = C.orange.copy(alpha = 0.40f),
                    disabledContentColor = C.bg.copy(alpha = 0.60f)
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (isBusy) "Working..." else "Pick Image",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
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
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, if (isError) C.red.copy(alpha = 0.26f) else C.green.copy(alpha = 0.30f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Final USSD response",
                color = C.t1,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                color = if (isError) C.red else C.green,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(body, color = C.t2, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ScratchRecentPinsCard(recentPins: List<String>, onPinClick: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.58f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = C.t2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Recents", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
            if (recentPins.isEmpty()) {
                Text("No recent recharges", color = C.t3, fontSize = 14.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentPins.forEach { recentPin ->
                        RecentPinItem(pin = recentPin, onClick = { onPinClick(recentPin) })
                    }
                }
            }
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
    pin: String,
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
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.cyan)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatPinForDisplay(pin),
                color = C.t1,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
    }
}

private fun formatPinForDisplay(pin: String): String {
    if (pin.length != 16) return pin
    return pin.chunked(4).joinToString(" ")
}

private fun loadRecentPins(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val pinsString = prefs.getString(KEY_RECENT_PINS, "") ?: ""
    return pinsString.split(",").filter { it.length == 16 }.take(MAX_RECENT_PINS)
}

private fun saveRecentPin(context: Context, pin: String) {
    if (pin.length != 16) return
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val currentPins = loadRecentPins(context).toMutableList()
    currentPins.remove(pin)
    currentPins.add(0, pin)
    val newPins = currentPins.take(MAX_RECENT_PINS)
    prefs.edit().putString(KEY_RECENT_PINS, newPins.joinToString(",")).apply()
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

private suspend fun startScratchCardRecharge(context: Context, pin: String): ScratchCardRechargeResult {
    val ussdCode = "*141*$pin#"
    return try {
        suspendCancellableCoroutine { cont ->
            val executed = SilentUssd.execute(
                context = context,
                ussdCode = ussdCode,
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
