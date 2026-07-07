package com.bingwa.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.telephony.SubscriptionInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SCRATCH_CARD_DIAL_PREFIX = "*141*"
private const val SCRATCH_CARD_FREE_LIMIT = 10
private const val SCRATCH_CARD_PAID_BLOCK_SIZE = 10
private const val SCRATCH_CARD_TOKENS_PER_BLOCK = 30
private const val SCRATCH_CARD_FREE_WINDOW_MS = 24L * 60L * 60L * 1_000L
private const val SCRATCH_CARD_BATCH_DELAY_MS = 2_000L
private const val KEY_SCRATCH_CARD_FREE_WINDOW_STARTED_AT = "scratch_card_free_window_started_at"
private const val KEY_SCRATCH_CARD_FREE_USED = "scratch_card_free_used"

private enum class ScratchCardItemStatus {
    Pending,
    Running,
    Success,
    Failed
}

private data class ScratchCardItem(
    val pin: String,
    val status: ScratchCardItemStatus = ScratchCardItemStatus.Pending,
    val note: String = "Waiting to start"
)

private data class ScratchCardScanResult(
    val bitmap: Bitmap,
    val pins: List<String>
)

private data class ScratchCardLaunchResult(
    val succeeded: Boolean,
    val message: String
)

private data class ScratchCardBillingPreview(
    val freeCardsApplied: Int,
    val chargeableCards: Int,
    val tokenCost: Int,
    val tokenBalance: Int,
    val unlimitedActive: Boolean,
    val freeCardsRemaining: Int,
    val resetAtMillis: Long,
    val nextWindowStartedAt: Long,
    val nextFreeCardsUsed: Int,
    val runnableCards: Int,
    val skippedCards: Int
) {
    val canStartBatch: Boolean
        get() = runnableCards > 0
}

@Composable
fun ScratchCardRechargeSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableSims = getAvailableSims(ctx).sortedBy { it.simSlotIndex }
    val defaultBatchSimSubId = resolvePreferredUssdSubId(ctx)
    val defaultSimLabel = if (availableSims.isNotEmpty()) {
        describeScratchCardSimSelection(defaultBatchSimSubId, availableSims)
    } else {
        "Current SIM setting"
    }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scratchCards by remember { mutableStateOf<List<ScratchCardItem>>(emptyList()) }
    var statusText by remember { mutableStateOf("Pick one image to scan for 16-digit scratch card PINs.") }
    var isScanning by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var showSimPicker by remember { mutableStateOf(false) }
    var selectedBatchSimSubId by remember { mutableStateOf<Int?>(defaultBatchSimSubId) }
    val billingPreview = remember(scratchCards) {
        previewScratchCardBilling(ctx, scratchCards.size)
    }
    val selectedSimLabel = remember(selectedBatchSimSubId, defaultBatchSimSubId, availableSims) {
        describeScratchCardSimSelection(selectedBatchSimSubId ?: defaultBatchSimSubId, availableSims)
    }

    fun startRechargeBatch(selectedSubId: Int?, selectedSimLabel: String) {
        if (scratchCards.isEmpty() || isRunning) return

        val batchBilling = previewScratchCardBilling(ctx, scratchCards.size)
        if (!batchBilling.canStartBatch) {
            statusText = "No free cards are available right now and you do not have enough tokens to recharge any more scratch cards in this batch."
            Toast.makeText(ctx, "No free cards or tokens available", Toast.LENGTH_SHORT).show()
            return
        }

        if (!batchBilling.unlimitedActive) {
            if (batchBilling.tokenCost > 0 && !TokenManager(ctx).spendTokens(batchBilling.tokenCost)) {
                statusText = "This batch needs ${batchBilling.tokenCost} tokens, but your balance changed before the recharge started."
                Toast.makeText(ctx, "Token balance changed. Try again.", Toast.LENGTH_SHORT).show()
                return
            }
            saveScratchCardBillingUsage(ctx, batchBilling)
        }

        val runnableCount = batchBilling.runnableCards.coerceAtMost(scratchCards.size)
        scratchCards = scratchCards.mapIndexed { index, item ->
            if (index < runnableCount) {
                item.copy(status = ScratchCardItemStatus.Pending, note = "Waiting to start")
            } else {
                item.copy(
                    status = ScratchCardItemStatus.Pending,
                    note = "Waiting for tokens or unlimited access before this card can be recharged"
                )
            }
        }
        val batchSummary = buildScratchCardBatchSummary(batchBilling, scratchCards.size)

        scope.launch {
            isRunning = true
            statusText = "$batchSummary Starting $runnableCount scratch card recharges on $selectedSimLabel."

            for (index in 0 until runnableCount) {
                val pin = scratchCards[index].pin
                scratchCards = scratchCards.replaceAt(
                    index,
                    scratchCards[index].copy(
                        status = ScratchCardItemStatus.Running,
                        note = "Running silent recharge for ${formatScratchCardPin(pin)}"
                    )
                )

                val result = startScratchCardRecharge(ctx, pin, selectedSubId)
                scratchCards = scratchCards.replaceAt(
                    index,
                    scratchCards[index].copy(
                        status = if (result.succeeded) ScratchCardItemStatus.Success else ScratchCardItemStatus.Failed,
                        note = result.message
                    )
                )

                if (index < runnableCount - 1) {
                    statusText = if (result.succeeded) {
                        "Card ${index + 1} of $runnableCount finished. Next card in 2s."
                    } else {
                        "Card ${index + 1} of $runnableCount failed. Next card in 2s."
                    }
                    delay(SCRATCH_CARD_BATCH_DELAY_MS)
                }
            }

            val startedCards = scratchCards.take(runnableCount)
            val startedCount = startedCards.count { it.status == ScratchCardItemStatus.Success }
            val failedCount = startedCards.count { it.status == ScratchCardItemStatus.Failed }
            statusText = if (batchBilling.skippedCards > 0) {
                "Batch finished. Completed $startedCount cards, failed $failedCount, and left ${batchBilling.skippedCards} cards waiting for tokens or unlimited access."
            } else {
                "Batch finished. Completed $startedCount cards, failed $failedCount."
            }
            vib(ctx, 70L)
            Toast.makeText(ctx, "Scratch card batch finished", Toast.LENGTH_SHORT).show()
            isRunning = false
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            isScanning = true
            statusText = "Scanning image for scratch card PINs..."
            try {
                val result = scanScratchCardImage(ctx, uri)
                previewBitmap = result.bitmap
                scratchCards = result.pins.map {
                    ScratchCardItem(pin = it, status = ScratchCardItemStatus.Pending, note = "Ready to recharge")
                }
                statusText = if (result.pins.isEmpty()) {
                    "No 16-digit scratch card PINs were found in the selected image."
                } else {
                    "Found ${result.pins.size} scratch card PINs. Review them, then start the batch."
                }
                if (result.pins.isNotEmpty()) vib(ctx)
            } catch (e: Exception) {
                previewBitmap = null
                scratchCards = emptyList()
                statusText = "Could not scan that image. ${e.message ?: "Try another image."}"
                Toast.makeText(ctx, "Image scan failed", Toast.LENGTH_SHORT).show()
            } finally {
                isScanning = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
    ) {
        if (showSimPicker) {
            ScratchCardSimPickerDialog(
                sims = availableSims,
                selectedSubId = selectedBatchSimSubId,
                defaultSimLabel = defaultSimLabel,
                onSelect = { selectedBatchSimSubId = it },
                onDismiss = { showSimPicker = false },
                onConfirm = { showSimPicker = false }
            )
        }
        SettingsTopBar(
            title = "Scratch Card Recharge",
            subtitle = "Scan one image, detect many 16-digit PINs, then run *141*PIN# one by one.",
            onBack = onBack
        )
        Column(
            modifier = Modifier.padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            ScratchCardHeroCard(
                cardCount = scratchCards.size,
                billingPreview = billingPreview,
                selectedSimLabel = selectedSimLabel
            )

            SettingsGroup("Batch Recharge") {
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Search,
                    title = "1. Scan A Single Image",
                    text = "Pick one photo from your phone. The app extracts every 16-digit scratch PIN it can find."
                )
                GroupDivider()
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Phone,
                    title = "2. Recharge Silently",
                    text = "The app sends each *141*PIN# request silently in the background, records the final USSD response, then continues to the next card."
                )
                GroupDivider()
                ScratchCardInfoRow(
                    icon = Icons.Rounded.CheckCircle,
                    title = "3. Free Then Paid",
                    text = "The first 10 scratch cards are free in each 24-hour window. After that, every extra block of up to 10 cards costs 30 tokens."
                )
                GroupDivider()
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Warning,
                    title = "Before You Start",
                    text = "Scratch cards now run with a fixed 2-second gap. Silent USSD must be supported on the phone for the background flow to work."
                )
            }

            SettingsGroup("Controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = C.surface.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, C.amber.copy(alpha = 0.22f))
                    ) {
                        ScratchCardBulletMessage(
                            text = buildScratchCardPolicyLabel(billingPreview),
                            accent = C.amber
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = C.cardHi.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "SIM for this bulk recharge",
                                color = C.t1,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Current batch SIM: $selectedSimLabel. You can change it before starting, and the same SIM will be used for every card in the queue.",
                                color = C.t2,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    ScratchCardActionButton(
                        label = if (isScanning) "Scanning image..." else "Pick Image",
                        icon = Icons.Rounded.Search,
                        onClick = { pickImageLauncher.launch("image/*") },
                        enabled = !isScanning && !isRunning,
                        containerColor = C.amber,
                        contentColor = Color(0xFF10131A)
                    )

                    ScratchCardActionButton(
                        label = "Choose SIM",
                        icon = Icons.Rounded.SimCard,
                        onClick = {
                            selectedBatchSimSubId = selectedBatchSimSubId ?: defaultBatchSimSubId ?: availableSims.firstOrNull()?.subscriptionId
                            showSimPicker = true
                        },
                        enabled = !isScanning && !isRunning,
                        containerColor = C.surface.copy(alpha = 0.96f),
                        contentColor = C.t1,
                        borderColor = C.border.copy(alpha = 0.78f)
                    )

                    ScratchCardActionButton(
                        label = if (isRunning) "Running recharge..." else "Start Batch Recharge",
                        icon = Icons.Rounded.Phone,
                        onClick = {
                            val effectiveSubId = selectedBatchSimSubId ?: defaultBatchSimSubId
                            val simLabel = describeScratchCardSimSelection(effectiveSubId, availableSims)
                            startRechargeBatch(effectiveSubId, simLabel)
                        },
                        enabled = scratchCards.isNotEmpty() && !isScanning && !isRunning,
                        containerColor = C.green,
                        contentColor = Color(0xFF08130F)
                    )

                    Button(
                        onClick = {
                            previewBitmap = null
                            scratchCards = emptyList()
                            statusText = "Pick one image to scan for 16-digit scratch card PINs."
                        },
                        enabled = !isScanning && !isRunning && (previewBitmap != null || scratchCards.isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = C.t3,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = C.t3.copy(alpha = 0.38f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        border = null,
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Clear Scan", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            SettingsGroup("Status") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = C.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
                ) {
                    ScratchCardBulletMessage(
                        text = statusText,
                        accent = if (scratchCards.isEmpty()) C.t3 else C.amber
                    )
                }
            }

            if (previewBitmap != null) {
                SettingsGroup("Selected Image") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, C.border.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
                    ) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Selected scratch card image",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SettingsGroup("Card Queue") {
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
                        Text(
                            text = "Queue preview",
                            color = C.t2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${scratchCards.size} of ${maxOf(SCRATCH_CARD_FREE_LIMIT, scratchCards.size)} detected",
                            color = C.amber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = C.surface.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            val visibleSlots = maxOf(SCRATCH_CARD_FREE_LIMIT, scratchCards.size)
                            for (index in 0 until visibleSlots) {
                                ScratchCardQueueRow(
                                    slotIndex = index,
                                    item = scratchCards.getOrNull(index)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(UiDimens.Spacing2xl))
    }
}

@Composable
private fun ScratchCardHeroCard(
    cardCount: Int,
    billingPreview: ScratchCardBillingPreview,
    selectedSimLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = C.cardHi.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(C.amber.copy(alpha = 0.15f))
                        .border(1.dp, C.amber.copy(alpha = 0.24f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SimCard, contentDescription = null, tint = C.amber)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bulk scratch recharge", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Scan once, review the cards, choose one SIM, then run the whole batch with a cleaner step-by-step flow.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScratchCardStatChip(
                    label = "Detected",
                    value = cardCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                ScratchCardStatChip(
                    label = "Free left",
                    value = billingPreview.freeCardsRemaining.toString(),
                    modifier = Modifier.weight(1f)
                )
                ScratchCardStatChip(
                    label = "Run now",
                    value = billingPreview.runnableCards.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = C.surface.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Phone, contentDescription = null, tint = C.green, modifier = Modifier.size(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Selected batch SIM", color = C.t3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(selectedSimLabel, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScratchCardActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color = Color.Transparent
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.45f),
            disabledContentColor = contentColor.copy(alpha = 0.55f)
        ),
        border = if (borderColor == Color.Transparent) null else BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ScratchCardBulletMessage(
    text: String,
    accent: Color
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(accent.copy(alpha = 0.82f))
        )
        Text(
            text = text,
            color = C.t2,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScratchCardStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = C.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.76f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = C.t3, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = C.t1, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ScratchCardInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(C.cardHi)
                .border(1.dp, C.border.copy(alpha = 0.72f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = C.amber)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun ScratchCardSimPickerDialog(
    sims: List<SubscriptionInfo>,
    selectedSubId: Int?,
    defaultSimLabel: String,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.cardHi,
        title = {
            Text("Choose SIM for this batch", color = C.t1)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The SIM you choose here will be used for every scratch card in this bulk recharge.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )

                if (sims.isEmpty()) {
                    ScratchCardSimOptionRow(
                        label = defaultSimLabel,
                        detail = "The app will use its current SIM setting for this batch.",
                        selected = true,
                        onClick = { onSelect(null) }
                    )
                } else {
                    sims.forEach { sim ->
                        ScratchCardSimOptionRow(
                            label = formatScratchCardSimLabel(sim),
                            detail = buildScratchCardSimDetail(sim),
                            selected = selectedSubId == sim.subscriptionId,
                            onClick = { onSelect(sim.subscriptionId) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Use this SIM", color = C.green)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = C.t2)
            }
        }
    )
}

@Composable
private fun ScratchCardSimOptionRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) C.green.copy(alpha = 0.54f) else C.border.copy(alpha = 0.72f)
    val backgroundColor = if (selected) C.green.copy(alpha = 0.10f) else C.surface.copy(alpha = 0.74f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background((if (selected) C.green else C.amber).copy(alpha = 0.14f))
                    .border(
                        1.dp,
                        (if (selected) C.green else C.amber).copy(alpha = 0.28f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.SimCard,
                    contentDescription = null,
                    tint = if (selected) C.green else C.amber,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
            }

            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = C.green, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatScratchCardSimLabel(sim: SubscriptionInfo): String {
    val slotNumber = (sim.simSlotIndex + 1).coerceAtLeast(1)
    val displayName = sim.displayName?.toString()?.trim().orEmpty()
    return if (displayName.isBlank()) {
        "SIM $slotNumber"
    } else {
        "SIM $slotNumber · $displayName"
    }
}

private fun buildScratchCardSimDetail(sim: SubscriptionInfo): String {
    val carrierName = sim.carrierName?.toString()?.trim().orEmpty()
    return if (carrierName.isBlank()) {
        "Use this SIM for every card in the batch."
    } else {
        "$carrierName will be used for every card in the batch."
    }
}

private fun describeScratchCardSimSelection(selectedSubId: Int?, sims: List<SubscriptionInfo>): String {
    val selectedSim = selectedSubId?.let { chosenId -> sims.firstOrNull { it.subscriptionId == chosenId } }
    return when {
        selectedSim != null -> formatScratchCardSimLabel(selectedSim)
        sims.isNotEmpty() -> formatScratchCardSimLabel(sims.first())
        else -> "Current SIM setting"
    }
}

@Composable
private fun ScratchCardQueueRow(
    slotIndex: Int,
    item: ScratchCardItem?
) {
    val slotLabel = (slotIndex + 1).toString().padStart(2, '0')
    val (statusLabel, color) = when (item?.status) {
        ScratchCardItemStatus.Pending -> "READY" to C.amber
        ScratchCardItemStatus.Running -> "RUNNING" to C.blue
        ScratchCardItemStatus.Success -> "DONE" to C.green
        ScratchCardItemStatus.Failed -> "FAILED" to C.red
        null -> "EMPTY" to C.t3
    }
    val pinText = item?.pin?.let(::formatScratchCardPin) ?: ".... .... .... ...."

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = slotLabel,
                color = C.t3,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(28.dp)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(C.cardHi.copy(alpha = 0.72f))
                    .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(color.copy(alpha = 0.72f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = pinText,
                color = if (item == null) C.t3.copy(alpha = 0.85f) else C.t2,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = statusLabel,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (item != null && item.note.isNotBlank()) {
            Text(
                text = item.note,
                color = C.t3,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 46.dp, end = 6.dp, bottom = 10.dp)
            )
        }

        if (slotIndex >= 0) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(C.border.copy(alpha = 0.20f))
            )
        }
    }
}

private suspend fun scanScratchCardImage(context: Context, uri: Uri): ScratchCardScanResult {
    val bitmap = loadScratchCardBitmap(context, uri)
    val text = recognizeScratchCardText(bitmap)
    return ScratchCardScanResult(
        bitmap = bitmap,
        pins = extractScratchCardPins(text)
    )
}

private suspend fun loadScratchCardBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    scaleScratchCardBitmap(rawBitmap)
}

private fun scaleScratchCardBitmap(bitmap: Bitmap, maxSide: Int = 2200): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val largestSide = maxOf(width, height)
    if (largestSide <= maxSide) {
        return if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
    }

    val scale = maxSide.toFloat() / largestSide.toFloat()
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        .copy(Bitmap.Config.ARGB_8888, false)
}

private suspend fun recognizeScratchCardText(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    continuation.resume(result.text)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }
    } finally {
        recognizer.close()
    }
}

private fun extractScratchCardPins(text: String): List<String> {
    // Match only 16-digit runs, allowing spaces or hyphens between groups.
    val pattern = Regex("""(?<!\d)(?:\d[\s-]*){16}(?!\d)""")
    val orderedPins = LinkedHashSet<String>()
    pattern.findAll(text).forEach { match ->
        val digits = match.value.filter(Char::isDigit)
        if (digits.length == 16) {
            orderedPins.add(digits)
        }
    }
    return orderedPins.toList()
}

private fun formatScratchCardPin(pin: String): String = pin.chunked(4).joinToString(" ")

private fun List<ScratchCardItem>.replaceAt(index: Int, item: ScratchCardItem): List<ScratchCardItem> =
    mapIndexed { currentIndex, currentItem -> if (currentIndex == index) item else currentItem }

private fun previewScratchCardBilling(context: Context, batchSize: Int, now: Long = System.currentTimeMillis()): ScratchCardBillingPreview {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val unlimitedActive = UnlimitedManager(context).isActive()
    val tokenBalance = TokenManager(context).getBalance()
    val normalizedBatchSize = batchSize.coerceAtLeast(0)
    val storedWindowStartedAt = prefs.safeGetLong(KEY_SCRATCH_CARD_FREE_WINDOW_STARTED_AT, 0L)
    val windowActive = storedWindowStartedAt > 0L && now - storedWindowStartedAt < SCRATCH_CARD_FREE_WINDOW_MS
    val windowStartedAt = if (windowActive) storedWindowStartedAt else 0L
    val freeUsed = if (windowActive) {
        prefs.safeGetInt(KEY_SCRATCH_CARD_FREE_USED, 0).coerceIn(0, SCRATCH_CARD_FREE_LIMIT)
    } else {
        0
    }
    val freeCardsRemaining = (SCRATCH_CARD_FREE_LIMIT - freeUsed).coerceAtLeast(0)
    val freeCardsApplied = minOf(normalizedBatchSize, freeCardsRemaining)
    val remainingCards = (normalizedBatchSize - freeCardsApplied).coerceAtLeast(0)
    val coveredChargeableCards = if (unlimitedActive) {
        remainingCards
    } else {
        val affordableBlocks = tokenBalance / SCRATCH_CARD_TOKENS_PER_BLOCK
        minOf(remainingCards, affordableBlocks * SCRATCH_CARD_PAID_BLOCK_SIZE)
    }
    val tokenCost = if (unlimitedActive || coveredChargeableCards <= 0) {
        0
    } else {
        ((coveredChargeableCards + SCRATCH_CARD_PAID_BLOCK_SIZE - 1) / SCRATCH_CARD_PAID_BLOCK_SIZE) * SCRATCH_CARD_TOKENS_PER_BLOCK
    }
    val runnableCards = freeCardsApplied + coveredChargeableCards
    val skippedCards = (normalizedBatchSize - runnableCards).coerceAtLeast(0)
    val nextWindowStartedAt = when {
        unlimitedActive -> windowStartedAt
        freeCardsApplied <= 0 -> windowStartedAt
        windowStartedAt > 0L -> windowStartedAt
        else -> now
    }
    val nextFreeCardsUsed = when {
        unlimitedActive -> freeUsed
        freeCardsApplied <= 0 -> freeUsed
        else -> (freeUsed + freeCardsApplied).coerceAtMost(SCRATCH_CARD_FREE_LIMIT)
    }
    val resetAtMillis = if (nextWindowStartedAt > 0L) {
        nextWindowStartedAt + SCRATCH_CARD_FREE_WINDOW_MS
    } else {
        0L
    }
    return ScratchCardBillingPreview(
        freeCardsApplied = freeCardsApplied,
        chargeableCards = coveredChargeableCards,
        tokenCost = tokenCost,
        tokenBalance = tokenBalance,
        unlimitedActive = unlimitedActive,
        freeCardsRemaining = freeCardsRemaining,
        resetAtMillis = resetAtMillis,
        nextWindowStartedAt = nextWindowStartedAt,
        nextFreeCardsUsed = nextFreeCardsUsed,
        runnableCards = runnableCards,
        skippedCards = skippedCards
    )
}

private fun saveScratchCardBillingUsage(context: Context, preview: ScratchCardBillingPreview) {
    if (preview.unlimitedActive) return
    if (preview.nextWindowStartedAt <= 0L) return
    context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_SCRATCH_CARD_FREE_WINDOW_STARTED_AT, preview.nextWindowStartedAt)
        .putInt(KEY_SCRATCH_CARD_FREE_USED, preview.nextFreeCardsUsed.coerceIn(0, SCRATCH_CARD_FREE_LIMIT))
        .apply()
}

private fun buildScratchCardPolicyLabel(preview: ScratchCardBillingPreview): String {
    val balanceText = if (preview.unlimitedActive) {
        "Unlimited access is active, so every card after the free 10 also runs without spending tokens."
    } else if (preview.skippedCards > 0 && preview.runnableCards > 0) {
        "You have ${preview.tokenBalance} tokens, so only ${preview.runnableCards} cards can run right now. Add more tokens or unlimited access to recharge the remaining ${preview.skippedCards} cards."
    } else {
        "You have ${preview.tokenBalance} tokens. Extra scratch card batches cost 30 tokens per 10 cards."
    }
    val freeText = if (preview.freeCardsRemaining > 0) {
        "${preview.freeCardsRemaining} of the free $SCRATCH_CARD_FREE_LIMIT cards are still available right now."
    } else if (preview.resetAtMillis > 0L) {
        "The free $SCRATCH_CARD_FREE_LIMIT-card allowance resets in ${formatScratchCardRemainingWindow(preview.resetAtMillis - System.currentTimeMillis())}."
    } else {
        "The free $SCRATCH_CARD_FREE_LIMIT-card allowance resets 24 hours after you use it."
    }
    return "$freeText $balanceText"
}

private fun buildScratchCardBatchSummary(preview: ScratchCardBillingPreview, batchSize: Int): String {
    if (preview.unlimitedActive) {
        return "Unlimited access active. The first ${preview.freeCardsApplied} cards are free and the remaining ${preview.chargeableCards} cards use unlimited access in this $batchSize-card batch."
    }
    if (preview.runnableCards <= 0) {
        return "No cards can be recharged right now."
    }
    if (preview.skippedCards > 0 && preview.tokenCost <= 0) {
        return "No tokens available. Only the first ${preview.runnableCards} free cards will be recharged from this $batchSize-card upload."
    }
    if (preview.skippedCards > 0) {
        return "This upload recharges ${preview.runnableCards} cards now, using ${preview.freeCardsApplied} free cards and ${preview.tokenCost} tokens for ${preview.chargeableCards} extra cards. ${preview.skippedCards} cards will wait for more tokens or unlimited access."
    }
    if (preview.tokenCost <= 0) {
        return "This $batchSize-card batch uses ${preview.freeCardsApplied} free cards."
    }
    return if (preview.freeCardsApplied > 0) {
        "This batch uses ${preview.freeCardsApplied} free cards and charges ${preview.tokenCost} tokens for ${preview.chargeableCards} extra cards."
    } else {
        "This batch charges ${preview.tokenCost} tokens for ${preview.chargeableCards} cards."
    }
}

private fun formatScratchCardRemainingWindow(remainingMs: Long): String {
    val safeMs = remainingMs.coerceAtLeast(0L)
    val totalMinutes = safeMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private suspend fun startScratchCardRecharge(context: Context, pin: String, subIdOverride: Int? = null): ScratchCardLaunchResult {
    val code = "$SCRATCH_CARD_DIAL_PREFIX$pin#"
    return suspendCancellableCoroutine { continuation ->
        var resumed = false
        fun finish(result: ScratchCardLaunchResult) {
            if (resumed) return
            resumed = true
            continuation.resume(result)
        }

        try {
            val started = UssdHelper.dialUssd(
                context = context,
                ussdCode = code,
                silentOnly = true,
                subIdOverride = subIdOverride,
                onSuccess = { response ->
                    finish(
                        ScratchCardLaunchResult(
                            succeeded = true,
                            message = "Final response: ${formatScratchCardResponse(response)}"
                        )
                    )
                },
                onFailure = { error ->
                    finish(
                        ScratchCardLaunchResult(
                            succeeded = false,
                            message = "Final response failed: ${formatScratchCardResponse(error)}"
                        )
                    )
                }
            )
            if (!started) {
                finish(
                    ScratchCardLaunchResult(
                        succeeded = false,
                        message = "Final response failed: Silent USSD could not start on this phone."
                    )
                )
            }
        } catch (error: Exception) {
            finish(
                ScratchCardLaunchResult(
                    succeeded = false,
                    message = "Final response failed: ${formatScratchCardResponse(error.message ?: "Could not start silent USSD.")}"
                )
            )
        }
    }
}

private fun formatScratchCardResponse(response: String): String {
    val compact = response
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return compact.ifBlank { "No response text returned." }
}
