package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

private enum class ScratchCardItemStatus {
    Pending,
    Running,
    Started,
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
    val started: Boolean,
    val message: String
)

@Composable
fun ScratchCardRechargeSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scratchCards by remember { mutableStateOf<List<ScratchCardItem>>(emptyList()) }
    var statusText by remember { mutableStateOf("Pick one image to scan for 16-digit scratch card PINs.") }
    var delaySecondsText by remember {
        mutableStateOf(prefs.safeGetInt("scratch_card_delay_seconds", 12).coerceIn(5, 60).toString())
    }
    var isScanning by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var pendingPermissionStart by remember { mutableStateOf(false) }

    fun resetBatchState() {
        scratchCards = scratchCards.map { it.copy(status = ScratchCardItemStatus.Pending, note = "Waiting to start") }
    }

    fun startRechargeBatch() {
        if (scratchCards.isEmpty() || isRunning) return

        val delaySeconds = delaySecondsText.toLongOrNull()?.coerceIn(5L, 60L) ?: 12L
        delaySecondsText = delaySeconds.toString()
        prefs.edit().putInt("scratch_card_delay_seconds", delaySeconds.toInt()).apply()
        resetBatchState()

        scope.launch {
            isRunning = true
            statusText = "Starting ${scratchCards.size} scratch card recharges."

            for (index in scratchCards.indices) {
                val pin = scratchCards[index].pin
                scratchCards = scratchCards.replaceAt(
                    index,
                    scratchCards[index].copy(
                        status = ScratchCardItemStatus.Running,
                        note = "Dialing ${formatScratchCardPin(pin)}"
                    )
                )

                val result = startScratchCardRecharge(ctx, pin)
                scratchCards = scratchCards.replaceAt(
                    index,
                    scratchCards[index].copy(
                        status = if (result.started) ScratchCardItemStatus.Started else ScratchCardItemStatus.Failed,
                        note = result.message
                    )
                )

                if (index < scratchCards.lastIndex) {
                    statusText = if (result.started) {
                        "Card ${index + 1} of ${scratchCards.size} started. Next card in ${delaySeconds}s."
                    } else {
                        "Card ${index + 1} of ${scratchCards.size} could not start. Next card in ${delaySeconds}s."
                    }
                    delay(delaySeconds * 1_000L)
                }
            }

            val startedCount = scratchCards.count { it.status == ScratchCardItemStatus.Started }
            val failedCount = scratchCards.count { it.status == ScratchCardItemStatus.Failed }
            statusText = "Batch finished. Started $startedCount cards, failed $failedCount."
            vib(ctx, 70L)
            Toast.makeText(ctx, "Scratch card batch finished", Toast.LENGTH_SHORT).show()
            isRunning = false
            pendingPermissionStart = false
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

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingPermissionStart) {
            startRechargeBatch()
        } else if (!granted) {
            pendingPermissionStart = false
            statusText = "Allow phone call permission so the app can dial each recharge code automatically."
            Toast.makeText(ctx, "Call permission is needed for automatic recharge", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(
            title = "Scratch Card Recharge",
            subtitle = "Scan one image, detect many 16-digit PINs, then run *140*PIN# one by one.",
            onBack = onBack
        )
        Column(
            modifier = Modifier.padding(horizontal = UiDimens.ScreenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SpacingLg)
        ) {
            SettingsGroup("Batch Recharge") {
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Search,
                    title = "1. Scan A Single Image",
                    text = "Pick one photo from your phone. The app extracts every 16-digit scratch PIN it can find."
                )
                GroupDivider()
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Phone,
                    title = "2. Recharge Sequentially",
                    text = "The app dials *140*PIN# for each detected card, waits for your chosen delay, then continues with the next one."
                )
                GroupDivider()
                ScratchCardInfoRow(
                    icon = Icons.Rounded.Warning,
                    title = "Before You Start",
                    text = "Automatic recharge needs phone call permission. USSD accessibility automation should stay enabled for the smoothest flow."
                )
            }

            SettingsGroup("Controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = delaySecondsText,
                        onValueChange = { value ->
                            delaySecondsText = value.filter(Char::isDigit).take(2)
                        },
                        label = { Text("Delay Between Cards (seconds)", color = C.t2) },
                        supportingText = { Text("Use 5 to 60 seconds. Default is 12 seconds.", color = C.t3) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { pickImageLauncher.launch("image/*") },
                            enabled = !isScanning && !isRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C.amber,
                                contentColor = Color(0xFF10131A)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isScanning) "Scanning..." else "Pick Image", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val hasCallPermission = ContextCompat.checkSelfPermission(
                                    ctx,
                                    Manifest.permission.CALL_PHONE
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!hasCallPermission) {
                                    pendingPermissionStart = true
                                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                                } else {
                                    startRechargeBatch()
                                }
                            },
                            enabled = scratchCards.isNotEmpty() && !isScanning && !isRunning,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C.green,
                                contentColor = Color(0xFF08130F)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isRunning) "Running..." else "Start Recharge", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            previewBitmap = null
                            scratchCards = emptyList()
                            statusText = "Pick one image to scan for 16-digit scratch card PINs."
                            pendingPermissionStart = false
                        },
                        enabled = !isScanning && !isRunning && (previewBitmap != null || scratchCards.isNotEmpty()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C.surface,
                            contentColor = C.t1
                        ),
                        modifier = Modifier.fillMaxWidth()
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
                    color = C.surface.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.82f))
                ) {
                    Text(
                        text = statusText,
                        color = C.t2,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
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

            SettingsGroup("Detected PINs") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (scratchCards.isEmpty()) {
                            "No scratch cards detected yet."
                        } else {
                            "${scratchCards.size} scratch card PINs ready."
                        },
                        color = C.t2,
                        fontSize = 12.sp
                    )

                    scratchCards.forEachIndexed { index, item ->
                        ScratchCardItemCard(
                            index = index,
                            item = item
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(UiDimens.Spacing2xl))
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
private fun ScratchCardItemCard(
    index: Int,
    item: ScratchCardItem
) {
    val (label, color, icon) = when (item.status) {
        ScratchCardItemStatus.Pending -> Triple("Ready", C.amber, Icons.Rounded.Schedule)
        ScratchCardItemStatus.Running -> Triple("Running", C.blue, Icons.Rounded.Phone)
        ScratchCardItemStatus.Started -> Triple("Started", C.green, Icons.Rounded.CheckCircle)
        ScratchCardItemStatus.Failed -> Triple("Failed", C.red, Icons.Rounded.Error)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = C.surface.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${index + 1}",
                    color = C.t3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatScratchCardPin(item.pin),
                    color = C.t1,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = color.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.24f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(item.note, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
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

private fun startScratchCardRecharge(context: Context, pin: String): ScratchCardLaunchResult {
    val code = "*140*$pin#"
    val hasCallPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasCallPermission) {
        return ScratchCardLaunchResult(
            started = false,
            message = "Call permission is missing."
        )
    }

    return try {
        val intent = UssdHelper.buildCallIntent(context, code)
        if (intent.action != Intent.ACTION_CALL) {
            return ScratchCardLaunchResult(
                started = false,
                message = "Automatic dialing needs phone call permission."
            )
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ScratchCardLaunchResult(
                started = false,
                message = "No phone app is available to dial the recharge code."
            )
        }

        val keepAppUiVisible = BingwaMobileApp.wasInForegroundRecently()
        if (keepAppUiVisible) UssdNavigationService.armForegroundUi()
        context.startActivity(intent)
        if (keepAppUiVisible) UssdHelper.relaunchAppUi(context)

        ScratchCardLaunchResult(
            started = true,
            message = "Dialed ${formatScratchCardPin(pin)} with *140*PIN#."
        )
    } catch (security: SecurityException) {
        ScratchCardLaunchResult(
            started = false,
            message = security.message ?: "Android blocked the phone call."
        )
    } catch (error: Exception) {
        ScratchCardLaunchResult(
            started = false,
            message = error.message ?: "Could not launch the recharge code."
        )
    }
}
