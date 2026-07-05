package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.telephony.SubscriptionInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SCRATCH_CARD_PREFS = "scratch_card_history"
private const val SCRATCH_CARD_LIST_KEY = "items"
private const val SCRATCH_CARD_QUEUE_PREFS = "scratch_card_queue"
private const val SCRATCH_CARD_QUEUE_RUNNING_KEY = "running"
private const val SCRATCH_CARD_HISTORY_LIMIT = 50
private const val SCRATCH_CARD_VISIBLE_LIMIT = 5

private enum class ScratchCardQueueStatus(val value: String) {
    QUEUED("Queued"),
    PROCESSING("Processing"),
    SUCCESS("Success"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    companion object {
        fun fromValue(raw: String?): ScratchCardQueueStatus =
            entries.firstOrNull { it.value.equals(raw, ignoreCase = true) } ?: QUEUED
    }
}

private data class ScratchCardHistoryItem(
    val id: Long,
    val code: String,
    val simSelection: Int,
    val status: String,
    val source: String,
    val addedAt: Long,
    val txId: Int = -1,
    val lastResponse: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("code", code)
        put("simSelection", simSelection)
        put("status", status)
        put("source", source)
        put("addedAt", addedAt)
        put("txId", txId)
        put("lastResponse", lastResponse)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScratchCardHistoryItem {
            return ScratchCardHistoryItem(
                id = obj.optLong("id", System.currentTimeMillis()),
                code = normalizeScratchCardDigits(obj.optString("code")),
                simSelection = obj.optInt("simSelection", USSD_SIM_SELECTION_SLOT_1),
                status = ScratchCardQueueStatus.fromValue(obj.optString("status")).value,
                source = obj.optString("source"),
                addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                txId = obj.optInt("txId", -1),
                lastResponse = obj.optString("lastResponse")
            )
        }
    }
}

private data class ScratchCardEnqueueResult(
    val addedCount: Int,
    val duplicateCount: Int
)

private data class ScratchCardScanResult(
    val codes: List<String>,
    val error: String? = null
)

private object ScratchCardHistoryStore {
    private val lock = Any()

    fun load(context: Context): List<ScratchCardHistoryItem> = synchronized(lock) {
        val raw = context.getSharedPreferences(SCRATCH_CARD_PREFS, Context.MODE_PRIVATE)
            .safeGetString(SCRATCH_CARD_LIST_KEY, "[]")
            ?: "[]"
        return@synchronized try {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                ScratchCardHistoryItem.fromJson(array.getJSONObject(index))
            }.filter { it.code.length == 16 }
                .sortedByDescending { it.addedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, items: List<ScratchCardHistoryItem>) {
        synchronized(lock) {
            val trimmed = items.sortedByDescending { it.addedAt }.take(SCRATCH_CARD_HISTORY_LIMIT)
            val array = JSONArray().apply {
                trimmed.forEach { put(it.toJson()) }
            }
            context.getSharedPreferences(SCRATCH_CARD_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(SCRATCH_CARD_LIST_KEY, array.toString())
                .apply()
        }
    }

    fun update(context: Context, entryId: Long, transform: (ScratchCardHistoryItem) -> ScratchCardHistoryItem): ScratchCardHistoryItem? {
        synchronized(lock) {
            val current = load(context).toMutableList()
            val index = current.indexOfFirst { it.id == entryId }
            if (index < 0) return null
            val updated = transform(current[index])
            current[index] = updated
            save(context, current)
            return updated
        }
    }

    fun updateByTransactionId(
        context: Context,
        txId: Int,
        transform: (ScratchCardHistoryItem) -> ScratchCardHistoryItem
    ): ScratchCardHistoryItem? {
        synchronized(lock) {
            val current = load(context).toMutableList()
            val index = current.indexOfFirst { it.txId == txId }
            if (index < 0) return null
            val updated = transform(current[index])
            current[index] = updated
            save(context, current)
            return updated
        }
    }

    fun append(context: Context, items: List<ScratchCardHistoryItem>) {
        synchronized(lock) {
            save(context, load(context) + items)
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            context.getSharedPreferences(SCRATCH_CARD_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(SCRATCH_CARD_LIST_KEY)
                .apply()
        }
    }
}

internal object ScratchCardRechargeManager {
    private val lock = Any()

    fun enqueueCodes(
        context: Context,
        codes: List<String>,
        defaultSimSelection: Int,
        source: String
    ): ScratchCardEnqueueResult {
        val normalizedCodes = codes
            .map(::normalizeScratchCardDigits)
            .filter { it.length == 16 }
            .distinct()

        if (normalizedCodes.isEmpty()) return ScratchCardEnqueueResult(0, 0)

        val existingCodes = ScratchCardHistoryStore.load(context).map { it.code }.toSet()
        val uniqueCodes = normalizedCodes.filterNot(existingCodes::contains)
        val duplicates = normalizedCodes.size - uniqueCodes.size

        if (uniqueCodes.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val items = uniqueCodes.mapIndexed { index, code ->
                ScratchCardHistoryItem(
                    id = now + index,
                    code = code,
                    simSelection = defaultSimSelection,
                    status = ScratchCardQueueStatus.QUEUED.value,
                    source = source,
                    addedAt = now + index
                )
            }
            ScratchCardHistoryStore.append(context, items)
            startNextIfIdle(context)
        }

        return ScratchCardEnqueueResult(
            addedCount = uniqueCodes.size,
            duplicateCount = duplicates
        )
    }

    fun updateSimSelection(context: Context, entryId: Long, simSelection: Int) {
        ScratchCardHistoryStore.update(context, entryId) { entry ->
            entry.copy(simSelection = simSelection)
        }
    }

    fun resumePendingQueue(context: Context) {
        val items = ScratchCardHistoryStore.load(context)
        val hasActive = items.any { ScratchCardQueueStatus.fromValue(it.status) == ScratchCardQueueStatus.PROCESSING }
        if (!hasActive) {
            setQueueRunning(context, false)
            startNextIfIdle(context)
        }
    }

    fun handleTransactionUpdate(context: Context, txId: Int, status: String, response: String) {
        if (txId < 0) return
        val updated = ScratchCardHistoryStore.updateByTransactionId(context, txId) { entry ->
            entry.copy(
                status = normalizeScratchQueueStatus(status).value,
                lastResponse = response.trim()
            )
        } ?: return

        val normalized = ScratchCardQueueStatus.fromValue(updated.status)
        if (normalized == ScratchCardQueueStatus.PROCESSING) {
            setQueueRunning(context, true)
            return
        }
        if (isScratchCardTerminalStatus(normalized)) {
            setQueueRunning(context, false)
            startNextIfIdle(context)
        }
    }

    fun isQueueRunning(context: Context): Boolean {
        return context.getSharedPreferences(SCRATCH_CARD_QUEUE_PREFS, Context.MODE_PRIVATE)
            .safeGetBoolean(SCRATCH_CARD_QUEUE_RUNNING_KEY, false)
    }

    private fun startNextIfIdle(context: Context) {
        var nextItem: ScratchCardHistoryItem? = null
        synchronized(lock) {
            if (isQueueRunning(context)) return
            val current = ScratchCardHistoryStore.load(context)
            val next = current
                .sortedBy { it.addedAt }
                .firstOrNull { ScratchCardQueueStatus.fromValue(it.status) == ScratchCardQueueStatus.QUEUED }
                ?: return

            val txId = createPendingTransaction(
                ctx = context,
                description = "Scratch Card Recharge",
                amount = "0",
                phone = "",
                ussd = buildScratchRechargeCode(next.code),
                clientName = "",
                status = TransactionStatus.PROCESSING.value,
                source = TX_SOURCE_AIRTIME,
                showInRecent = false,
                offerId = -1
            )

            if (txId < 0) {
                ScratchCardHistoryStore.update(context, next.id) { item ->
                    item.copy(
                        status = ScratchCardQueueStatus.FAILED.value,
                        lastResponse = "Could not create a recharge task."
                    )
                }
                return
            }

            val updated = next.copy(
                txId = txId,
                status = ScratchCardQueueStatus.PROCESSING.value,
                lastResponse = "Queued for recharge using ${buildScratchRechargeCode(next.code)}"
            )
            ScratchCardHistoryStore.update(context, next.id) { updated }
            setQueueRunning(context, true)
            nextItem = updated
        }

        val item = nextItem ?: return

        val intent = Intent(context, AutomationService::class.java).apply {
            putExtra("mode", OFFER_EXECUTION_MODE_SIMPLE)
            putExtra("code", buildScratchRechargeCode(item.code))
            putExtra("phoneNumber", "")
            putExtra("txId", item.txId)
            putExtra("offerId", -1)
            putExtra("offerName", "Scratch Card Recharge")
            putExtra("simSelection", item.simSelection)
            putExtra("signatureEnabled", false)
            putExtra("signatureMode", "STOP")
            putExtra("signatureLearning", false)
            putExtra("returnToAppAggressively", true)
        }
        val started = ServiceLauncher.startAutomationService(context, intent)
        if (!started) {
            setQueueRunning(context, false)
            ScratchCardHistoryStore.update(context, item.id) { historyItem ->
                historyItem.copy(
                    status = ScratchCardQueueStatus.FAILED.value,
                    lastResponse = "USSD automation could not start. Open Bingwa Mobile and remove battery restrictions."
                )
            }
            startNextIfIdle(context)
        }
    }

    private fun setQueueRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(SCRATCH_CARD_QUEUE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SCRATCH_CARD_QUEUE_RUNNING_KEY, running)
            .apply()
    }
}

private object ScratchCardScanner {
    private val exactSixteenDigitCodeRegex = Regex("""(?<!\d)(?:\d[\s-]*){16}(?!\d)""")

    fun scanBitmap(bitmap: Bitmap, onResult: (ScratchCardScanResult) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        process(image, onResult)
    }

    fun scanUri(context: Context, uri: Uri, onResult: (ScratchCardScanResult) -> Unit) {
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse { error ->
            onResult(ScratchCardScanResult(emptyList(), error.message ?: "Could not open selected image."))
            return
        }
        process(image, onResult)
    }

    private fun process(image: InputImage, onResult: (ScratchCardScanResult) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(
                    ScratchCardScanResult(
                        codes = extractCodes(visionText.text)
                    )
                )
            }
            .addOnFailureListener { error ->
                onResult(ScratchCardScanResult(emptyList(), error.message ?: "Text recognition failed."))
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }

    private fun extractCodes(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalizedText = text
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')

        return exactSixteenDigitCodeRegex.findAll(normalizedText)
            .map { match -> normalizeScratchCardDigits(match.value) }
            .filter { it.length == 16 }
            .distinct()
            .toList()
    }
}

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sims = remember { getAvailableSims(ctx) }
    var scratchCards by remember { mutableStateOf(ScratchCardHistoryStore.load(ctx)) }
    var isScanning by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf("") }
    var scanSummary by remember { mutableStateOf("Upload one image or many images at once. The scanner reads every image separately and queues every valid 16-digit card code one by one.") }

    fun refreshHistory() {
        scratchCards = ScratchCardHistoryStore.load(ctx)
    }

    fun finishBatchScan(
        source: String,
        foundCodes: List<String>,
        processedImages: Int,
        imagesWithoutCode: Int,
        lastError: String?
    ) {
        val enqueueResult = ScratchCardRechargeManager.enqueueCodes(
            context = ctx,
            codes = foundCodes,
            defaultSimSelection = currentUssdSimSelection(ctx),
            source = source
        )
        refreshHistory()
        isScanning = false

        scanSummary = buildString {
            if (enqueueResult.addedCount > 0) {
                append("Queued ${enqueueResult.addedCount} scratch card")
                if (enqueueResult.addedCount != 1) append('s')
                append(" from $processedImages image")
                if (processedImages != 1) append('s')
                append(".")
            } else {
                append("No valid 16-digit scratch card was found.")
            }
            if (imagesWithoutCode > 0) {
                append(" $imagesWithoutCode image")
                if (imagesWithoutCode != 1) append('s')
                append(" had no valid code.")
            }
            if (enqueueResult.duplicateCount > 0) {
                append(" ${enqueueResult.duplicateCount} duplicate code")
                if (enqueueResult.duplicateCount != 1) append('s')
                append(" were skipped.")
            }
            if (!lastError.isNullOrBlank()) {
                append(" Last issue: $lastError")
            }
        }
        if (enqueueResult.addedCount > 0) vib(ctx, 70L)
        Toast.makeText(ctx, scanSummary, Toast.LENGTH_LONG).show()
    }

    fun scanSingleBitmap(bitmap: Bitmap) {
        isScanning = true
        scanStatus = "Scanning camera image..."
        ScratchCardScanner.scanBitmap(bitmap) { result ->
            finishBatchScan(
                source = "Camera",
                foundCodes = result.codes,
                processedImages = 1,
                imagesWithoutCode = if (result.codes.isEmpty()) 1 else 0,
                lastError = result.error
            )
        }
    }

    fun scanGalleryImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isScanning = true
        val collectedCodes = mutableListOf<String>()
        var emptyImages = 0
        var lastError: String? = null

        fun processAt(index: Int) {
            if (index >= uris.size) {
                finishBatchScan(
                    source = if (uris.size == 1) "Gallery" else "Gallery Bulk",
                    foundCodes = collectedCodes,
                    processedImages = uris.size,
                    imagesWithoutCode = emptyImages,
                    lastError = lastError
                )
                return
            }
            scanStatus = "Scanning image ${index + 1} of ${uris.size}..."
            ScratchCardScanner.scanUri(ctx, uris[index]) { result ->
                if (result.codes.isEmpty()) {
                    emptyImages += 1
                } else {
                    collectedCodes += result.codes
                }
                if (!result.error.isNullOrBlank()) {
                    lastError = result.error
                }
                processAt(index + 1)
            }
        }

        processAt(0)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            scanSingleBitmap(bitmap)
        } else {
            Toast.makeText(ctx, "Camera capture was cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scanGalleryImages(uris)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraLauncher.launch(null)
        } else {
            Toast.makeText(ctx, "Camera permission is needed to scan scratch cards.", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        ScratchCardRechargeManager.resumePendingQueue(ctx)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshHistory()
            }
        }
        val registered = registerAppReceiver(
            context = ctx,
            receiver = receiver,
            filter = IntentFilter().apply {
                addAction(ACTION_TX_CREATED)
                addAction("com.bingwa.mobile.TX_UPDATED")
            }
        )
        onDispose {
            if (registered) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshHistory()
    }

    val visibleScratchCards = remember(scratchCards) {
        scratchCards
            .sortedByDescending { it.addedAt }
            .take(SCRATCH_CARD_VISIBLE_LIMIT)
    }
    val queueRunning = remember(scratchCards) {
        scratchCards.any { ScratchCardQueueStatus.fromValue(it.status) == ScratchCardQueueStatus.PROCESSING }
    }
    val queuedCount = remember(scratchCards) {
        scratchCards.count { ScratchCardQueueStatus.fromValue(it.status) == ScratchCardQueueStatus.QUEUED }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        SettingsTopBar(
            title = "Recharge Scanner",
            subtitle = "Scan airtime scratch cards from camera or gallery",
            onBack = onBack
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = C.cardHi,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(C.cyan.copy(alpha = 0.12f))
                                    .border(1.dp, C.cyan.copy(alpha = 0.28f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = C.cyan)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Bulk upload supported", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "If you select many images, the system scans them one by one, extracts every 16-digit code, then recharges them in queue order.",
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
                            Button(
                                onClick = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Text("Gallery", color = C.bg, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    val hasCamera = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                                    if (!hasCamera) {
                                        Toast.makeText(ctx, "This phone does not have a usable camera.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            cameraLauncher.launch(null)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.amber)
                            ) {
                                Text("Camera", color = C.bg, fontWeight = FontWeight.Bold)
                            }
                        }

                        Surface(
                            color = Color(0xFF15191B),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Queue status", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        queueRunning -> "A recharge is currently running. $queuedCount more card(s) are waiting in the queue."
                                        queuedCount > 0 -> "$queuedCount scratch card(s) are ready and will start automatically."
                                        else -> "No queued scratch cards right now."
                                    },
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                                if (isScanning) {
                                    Text(scanStatus, color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                } else {
                                    Text(scanSummary, color = C.t3, fontSize = 11.sp, lineHeight = 16.sp)
                                }
                            }
                        }

                        Surface(
                            color = Color(0xFF15191B),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Recharge format", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Every extracted code is executed as *141*sd# where sd is the 16-digit scratch card number.", color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Scratch Card History", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Latest Card 1 to Card 5 with SIM selection and queue status", color = C.t2, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            if (queueRunning) {
                                Toast.makeText(ctx, "Wait for the current recharge to finish before clearing history.", Toast.LENGTH_SHORT).show()
                            } else {
                                ScratchCardHistoryStore.clear(ctx)
                                refreshHistory()
                                Toast.makeText(ctx, "Scratch card history cleared.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = C.red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear", color = C.red, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (visibleScratchCards.isEmpty()) {
                item {
                    Surface(
                        color = C.cardHi,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("No scratch cards scanned yet", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Open Gallery for one or many images, or use Camera for a fresh scan. Each detected code will appear here and recharge one by one automatically.",
                                color = C.t2,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = visibleScratchCards,
                    key = { _, item -> item.id }
                ) { index, item ->
                    ScratchCardHistoryRow(
                        index = index,
                        item = item,
                        sims = sims,
                        onSimSelectionChanged = { simSelection ->
                            ScratchCardRechargeManager.updateSimSelection(ctx, item.id, simSelection)
                            refreshHistory()
                        }
                    )
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun ScratchCardHistoryRow(
    index: Int,
    item: ScratchCardHistoryItem,
    sims: List<SubscriptionInfo>,
    onSimSelectionChanged: (Int) -> Unit
) {
    val status = ScratchCardQueueStatus.fromValue(item.status)
    val accent = when (status) {
        ScratchCardQueueStatus.SUCCESS -> C.green
        ScratchCardQueueStatus.FAILED, ScratchCardQueueStatus.CANCELLED -> C.red
        ScratchCardQueueStatus.PROCESSING -> C.cyan
        ScratchCardQueueStatus.QUEUED -> C.amber
    }
    var simMenuExpanded by remember(item.id) { mutableStateOf(false) }

    Surface(
        color = C.cardHi,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (status) {
                            ScratchCardQueueStatus.SUCCESS -> Icons.Rounded.CheckCircle
                            ScratchCardQueueStatus.FAILED, ScratchCardQueueStatus.CANCELLED -> Icons.Rounded.Warning
                            else -> Icons.Rounded.Schedule
                        },
                        contentDescription = null,
                        tint = accent
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Card ${index + 1}", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(formatScratchCardCode(item.code), color = C.t1, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${item.source} • ${formatScratchCardDate(item.addedAt)}", color = C.t3, fontSize = 11.sp)
                }
                Box {
                    TextButton(onClick = { simMenuExpanded = true }) {
                        Icon(Icons.Rounded.SimCard, contentDescription = null, tint = C.cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            describeUssdSimSelection(item.simSelection, sims),
                            color = C.cyan,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = simMenuExpanded,
                        onDismissRequest = { simMenuExpanded = false },
                        modifier = Modifier
                            .background(C.cardHi, RoundedCornerShape(12.dp))
                            .border(1.dp, C.border, RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Slot 1", color = C.t1) },
                            onClick = {
                                onSimSelectionChanged(USSD_SIM_SELECTION_SLOT_1)
                                simMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Slot 2", color = C.t1) },
                            onClick = {
                                onSimSelectionChanged(USSD_SIM_SELECTION_SLOT_2)
                                simMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(status.value, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    buildScratchRechargeCode(item.code),
                    color = C.t2,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (item.lastResponse.isNotBlank()) {
                Surface(
                    color = Color(0xFF15191B),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Text(
                        text = item.lastResponse,
                        color = C.t2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun normalizeScratchCardDigits(raw: String): String {
    return raw
        .replace('O', '0')
        .replace('o', '0')
        .replace('I', '1')
        .replace('l', '1')
        .replace(Regex("\\D+"), "")
        .take(16)
}

private fun buildScratchRechargeCode(code: String): String = "*141*${normalizeScratchCardDigits(code)}#"

private fun formatScratchCardCode(code: String): String {
    return normalizeScratchCardDigits(code)
        .chunked(4)
        .joinToString(" ")
}

private fun formatScratchCardDate(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun normalizeScratchQueueStatus(raw: String): ScratchCardQueueStatus {
    return when {
        raw.equals(TransactionStatus.SUCCESS.value, ignoreCase = true) -> ScratchCardQueueStatus.SUCCESS
        raw.equals(TransactionStatus.FAILED.value, ignoreCase = true) -> ScratchCardQueueStatus.FAILED
        raw.equals(TransactionStatus.CANCELLED.value, ignoreCase = true) -> ScratchCardQueueStatus.CANCELLED
        raw.equals(TransactionStatus.PROCESSING.value, ignoreCase = true) -> ScratchCardQueueStatus.PROCESSING
        else -> ScratchCardQueueStatus.PROCESSING
    }
}

private fun isScratchCardTerminalStatus(status: ScratchCardQueueStatus): Boolean {
    return status == ScratchCardQueueStatus.SUCCESS ||
        status == ScratchCardQueueStatus.FAILED ||
        status == ScratchCardQueueStatus.CANCELLED
}
