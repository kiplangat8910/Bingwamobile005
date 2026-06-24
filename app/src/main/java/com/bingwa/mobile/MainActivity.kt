@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:Suppress("DEPRECATION")

package com.bingwa.mobile

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

private data class ManualSearchEntry(
    val name: String,
    val phone: String,
    val source: String,
    val lastSeen: Long = 0L
)
// ─── MainActivity ─────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private var pendingStartupPermissions: Array<String> = emptyArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        val denied = perms.filterValues { !it }.keys
        if (denied.isNotEmpty()) Toast.makeText(this, "Permissions denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindowSafely()

        AppTheme.load(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { OfferRepository.ensureSeeded(applicationContext) }
                .onFailure { Log.e("MainActivity", "Offer seeding failed", it) }
        }

        pendingStartupPermissions = mutableListOf<String>().apply {
            listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
                .forEach { if (ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED) add(it) }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        setContent {
            SafeStartupRoot()
        }

        window.decorView.post {
            requestStartupPermissionsSafely()
            warmUpLaunchState()
        }
    }

    private fun warmUpLaunchState() {
        runCatching { RelayManager.load(this) }
            .onFailure { Log.e("MainActivity", "Launch warm-up failed", it) }
    }

    private fun configureWindowSafely() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }.onFailure { Log.e("MainActivity", "Window setup failed", it) }
    }

    private fun requestStartupPermissionsSafely() {
        if (pendingStartupPermissions.isEmpty()) return
        runCatching { permissionLauncher.launch(pendingStartupPermissions) }
            .onFailure { Log.e("MainActivity", "Permission launch failed", it) }
    }
}

@Composable
private fun SafeStartupRoot() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val startupResult = runCatching {
        val mode = AppTheme.mode
        val accent = AppTheme.accent
        val dark = when (mode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }
        val scheme = buildAppColorScheme(accent, dark)

        LaunchedEffect(scheme, dark) { applyVolcanicPaletteFromScheme(scheme, dark) }
        SideEffect {
            runCatching {
                view.context.findActivity()?.let { activity ->
                    WindowInsetsControllerCompat(activity.window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }.onFailure { error ->
                Log.e("MainActivity", "Window inset styling failed", error)
            }
        }

        MaterialTheme(colorScheme = scheme) { BingwaApp() }
    }

    startupResult.onFailure { error ->
        Log.e("MainActivity", "Startup composition failed", error)
    }

    if (startupResult.isFailure) {
        MaterialTheme(colorScheme = buildAppColorScheme(ThemeAccent.BYBIT, true)) {
            StartupFallbackScreen(
                startupResult.exceptionOrNull()?.javaClass?.simpleName?.ifBlank { "Startup error" }
                    ?: "Startup error"
            ) {
                runCatching {
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let(ctx::startActivity)
                }
            }
        }
    }
}

@Composable
private fun StartupFallbackScreen(errorLabel: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0F))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset((-120).dp, (-80).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFF59E0B).copy(alpha = 0.10f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(80.dp, (-20).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF22D3EE).copy(alpha = 0.09f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF1A1C22),
                border = BorderStroke(1.dp, Color(0xFF2A2E38).copy(alpha = 0.9f)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF21242B).copy(alpha = 0.96f),
                                    Color(0xFF1A1C22).copy(alpha = 0.98f)
                                )
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Bingwa Mobile",
                        color = Color(0xFFF1F3F8),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "Startup compatibility mode",
                        color = Color(0xFF9CA3AF),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Issue: $errorLabel",
                        color = Color(0xFFEF4444),
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF22D3EE),
                            contentColor = Color(0xFF0A0B0F)
                        )
                    ) {
                        Text("Retry Launch", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "The app keeps your existing setup and retries the normal experience when available.",
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

// ─── Helper Functions ────────────────────────────────────────────────────
fun vib(ctx: Context, durationMs: Long = 30L) {
    try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") v.vibrate(durationMs)
    } catch (_: Exception) {}
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

private fun hasPermissions(context: Context, vararg permissions: String): Boolean =
    permissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

private fun canUsePhoneAutomation(context: Context): Boolean {
    if (!hasPermissions(
            context,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
    ) return false
    return context.getSystemService(Context.TELEPHONY_SERVICE) is TelephonyManager
}

private fun shouldAutoStartPhoneAutomation(context: Context): Boolean {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    return prefs.safeGetBoolean("automation_enabled", true) && canUsePhoneAutomation(context)
}

private fun requestBalanceCheckSafely(context: Context): Boolean =
    runCatching {
        BalanceChecker.requestBalanceCheck(context)
        true
    }.getOrElse { error ->
        Log.e("MainActivity", "Unable to start balance refresh", error)
        false
    }

fun canonicalNameTokens(value: String): List<String> =
    value.trim()
        .replace(Regex("[^A-Za-z\\s'\\-]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim('\'', '-', ' ') }
        .filter { it.length > 1 }
        .map { it.lowercase(Locale.getDefault()) }

fun formatClientName(raw: String, maxThreeNameLength: Int = 24): String {
    val tokens = canonicalNameTokens(raw)
    if (tokens.isEmpty()) return ""
    val selected = when {
        tokens.size <= 2 -> tokens
        tokens.size == 3 && tokens.joinToString(" ").length <= maxThreeNameLength -> tokens
        else -> listOf(tokens.first(), tokens.last())
    }
    return selected.joinToString(" ") { token ->
        token.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
    }
}

fun namesLikelyMatch(first: String, second: String): Boolean {
    val a = canonicalNameTokens(first)
    val b = canonicalNameTokens(second)
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (a.first() == b.first() && a.last() == b.last()) return true
    return a.intersect(b.toSet()).size >= 2
}

private fun inferTransactionSource(description: String, amount: String, clientName: String, ussdCode: String): String {
    if (amount.trim().startsWith("-") || description.contains("airtime", ignoreCase = true)) return TX_SOURCE_AIRTIME
    if (ussdCode.isNotBlank() && clientName.isNotBlank()) return TX_SOURCE_AUTOMATED
    return TX_SOURCE_SYSTEM
}

private fun inferRecentVisibility(description: String, amount: String, clientName: String, ussdCode: String): Boolean =
    inferTransactionSource(description, amount, clientName, ussdCode) == TX_SOURCE_AUTOMATED

private fun transactionFromStorage(obj: JSONObject, fallbackId: Int = -1): Transaction {
    val description = obj.optString("description", "")
    val amount = obj.optString("amount", "")
    val clientName = obj.optString("clientName", "")
    val ussdCode = obj.optString("ussdCode", "")
    val status = obj.optString("status", TransactionStatus.PENDING.value)
    val source = obj.optString("source").ifBlank {
        inferTransactionSource(description, amount, clientName, ussdCode)
    }
    val showInRecent = if (obj.has("showInRecent")) {
        obj.optBoolean("showInRecent", false)
    } else {
        inferRecentVisibility(description, amount, clientName, ussdCode)
    }
    return Transaction(
        id = obj.optInt("id", fallbackId),
        description = description,
        amount = amount,
        amountValue = Regex("""\d+(?:\.\d+)?""").find(amount)?.value?.toDoubleOrNull() ?: 0.0,
        date = obj.optString("date", ""),
        status = status,
        statusEnum = TransactionStatus.fromString(status),
        ussdCode = ussdCode,
        phoneNumber = obj.optString("phoneNumber", ""),
        response = obj.optString("response", ""),
        timestamp = obj.optLong("timestamp", 0L),
        clientName = clientName,
        ussdResponse = obj.optString("ussdResponse", ""),
        ussdTranscript = obj.optString("ussdTranscript", ""),
        source = source,
        showInRecent = showInRecent,
        offerId = obj.optInt("offerId", -1),
        completedAt = obj.optLong("completedAt", 0L),
        executionDurationMs = obj.optLong("executionDurationMs", 0L)
    )
}

private fun transactionToJson(tx: Transaction): JSONObject =
    JSONObject().apply {
        put("id", tx.id)
        put("description", tx.description)
        put("amount", tx.amount)
        put("date", tx.date)
        put("status", tx.status)
        put("ussdCode", tx.ussdCode)
        put("phoneNumber", tx.phoneNumber)
        put("clientName", tx.clientName)
        put("ussdResponse", tx.ussdResponse)
        put("ussdTranscript", tx.ussdTranscript)
        put("timestamp", tx.timestamp)
        put("source", tx.source)
        put("showInRecent", tx.showInRecent)
        put("offerId", tx.offerId)
        put("completedAt", tx.completedAt)
        put("executionDurationMs", tx.executionDurationMs)
    }

fun loadTransactionByIdFromPrefs(context: Context, txId: Int): Transaction? {
    return TransactionStore.findById(context, txId)
}

fun broadcastTransactionCreated(context: Context, txId: Int) {
    if (txId < 0) return
    context.sendBroadcast(
        Intent(ACTION_TX_CREATED)
            .setPackage(context.packageName)
            .apply { putExtra("txId", txId) }
    )
}

fun broadcastTransactionUpdated(context: Context, txId: Int) {
    if (txId < 0) return
    context.sendBroadcast(
        Intent("com.bingwa.mobile.TX_UPDATED")
            .setPackage(context.packageName)
            .apply { putExtra("txId", txId) }
    )
}

fun resolveClientNameByPhone(context: Context, phone: String): String {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (!normalized.matches(Regex("^0\\d{9}$"))) return ""

    val contacts = loadContacts(context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE))
    contacts.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == normalized && it.name.isNotBlank() }?.let {
        return formatClientName(it.name)
    }

    for (tx in TransactionStore.load(context)) {
        if (SmsCommandHandler.normalizePhone(tx.phoneNumber) == normalized && tx.clientName.isNotBlank()) {
            return formatClientName(tx.clientName)
        }
    }
    return ""
}

fun upsertSavedContact(context: Context, phone: String, name: String) {
    val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
    val formattedName = formatClientName(name)
    if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return
    if (formattedName.isBlank()) return

    val prefs = context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE)
    val current = loadContacts(prefs).toMutableList()
    val idx = current.indexOfFirst { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
    if (idx >= 0) {
        val existing = current[idx]
        val improved = choosePreferredClientName(existing.name, formattedName)
        if (improved.isNotBlank() && improved != existing.name) {
            current[idx] = existing.copy(name = improved)
            saveContacts(prefs, current)
        }
        return
    }

    current.add(SavedContact(formattedName, normalizedPhone))
    saveContacts(prefs, current)
}

fun choosePreferredClientName(current: String, candidate: String): String {
    val currentFormatted = formatClientName(current)
    val candidateFormatted = formatClientName(candidate)
    if (candidateFormatted.isBlank()) return currentFormatted
    if (currentFormatted.isBlank()) return candidateFormatted

    val currentTokens = canonicalNameTokens(currentFormatted)
    val candidateTokens = canonicalNameTokens(candidateFormatted)
    return when {
        candidateTokens.size > currentTokens.size -> candidateFormatted
        candidateTokens.size == currentTokens.size && candidateFormatted.length > currentFormatted.length -> candidateFormatted
        else -> currentFormatted
    }
}

private fun mergeManualSearchEntry(existing: ManualSearchEntry?, incoming: ManualSearchEntry): ManualSearchEntry {
    if (existing == null) return incoming
    val preferredName = choosePreferredClientName(existing.name, incoming.name)
    val preferredSource = when {
        existing.source == "Saved" || incoming.source != "Saved" -> existing.source
        else -> incoming.source
    }
    return ManualSearchEntry(
        name = preferredName,
        phone = existing.phone,
        source = preferredSource,
        lastSeen = maxOf(existing.lastSeen, incoming.lastSeen)
    )
}

private fun buildManualSearchEntries(
    context: Context,
    allTxns: List<Transaction>,
    smsContacts: List<SavedContact>
): List<ManualSearchEntry> {
    val merged = linkedMapOf<String, ManualSearchEntry>()

    fun addEntry(name: String, phone: String, source: String, lastSeen: Long = 0L) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return
        val incoming = ManualSearchEntry(
            name = formatClientName(name),
            phone = normalizedPhone,
            source = source,
            lastSeen = lastSeen
        )
        merged[normalizedPhone] = mergeManualSearchEntry(merged[normalizedPhone], incoming)
    }

    loadContacts(context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE))
        .forEach { addEntry(it.name, it.phone, "Saved") }
    smsContacts.forEach { addEntry(it.name, it.phone, "M-PESA SMS") }
    allTxns.forEach { tx ->
        addEntry(tx.clientName, tx.phoneNumber, "History", tx.timestamp)
    }

    return merged.values.sortedWith(
        compareByDescending<ManualSearchEntry> { it.lastSeen }
            .thenBy { it.name.ifBlank { it.phone }.lowercase(Locale.getDefault()) }
    )
}

private fun rankManualSearchEntries(query: String, entries: List<ManualSearchEntry>): List<ManualSearchEntry> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()

    val lowered = trimmed.lowercase(Locale.getDefault())
    val nameTokens = canonicalNameTokens(trimmed)
    val digits = trimmed.filter(Char::isDigit)

    return entries.asSequence()
        .mapNotNull { entry ->
            val loweredName = entry.name.lowercase(Locale.getDefault())
            val normalizedPhone = SmsCommandHandler.normalizePhone(entry.phone)
            var score = 0

            if (digits.isNotBlank()) {
                score += when {
                    normalizedPhone == digits -> 170
                    normalizedPhone.startsWith(digits) -> 125
                    normalizedPhone.contains(digits) -> 95
                    else -> 0
                }
            }

            if (lowered.isNotBlank()) {
                score += when {
                    loweredName == lowered -> 180
                    loweredName.startsWith(lowered) -> 140
                    loweredName.contains(lowered) -> 100
                    else -> 0
                }
            }

            if (nameTokens.isNotEmpty()) {
                val entryTokens = canonicalNameTokens(entry.name)
                if (nameTokens.all { token -> entryTokens.any { it.startsWith(token) || it == token } }) {
                    score += 80
                }
            }

            score += when (entry.source) {
                "Saved" -> 20
                "M-PESA SMS" -> 10
                else -> 0
            }

            if (score <= 0) null else entry to score
        }
        .sortedWith(
            compareByDescending<Pair<ManualSearchEntry, Int>> { it.second }
                .thenByDescending { it.first.lastSeen }
                .thenBy { it.first.name.ifBlank { it.first.phone }.lowercase(Locale.getDefault()) }
        )
        .map { it.first }
        .take(6)
        .toList()
}

private fun autoMatchManualEntries(phone: String, entries: List<ManualSearchEntry>): List<ManualSearchEntry> {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (normalized.length < 3) return emptyList()

    return rankManualSearchEntries(phone, entries).sortedWith(
        compareByDescending<ManualSearchEntry> { SmsCommandHandler.normalizePhone(it.phone) == normalized }
            .thenByDescending { it.source == "Saved" }
            .thenByDescending { it.lastSeen }
            .thenBy { it.name.ifBlank { it.phone }.lowercase(Locale.getDefault()) }
    )
}

fun loadTransactions(ctx: Context, into: MutableList<Transaction>) {
    try {
        into.clear()
        into.addAll(TransactionStore.load(ctx))
    } catch (e: Exception) {
        Log.e("Transactions", "loadTransactions error", e)
    }
}

fun saveTransactions(ctx: Context, list: List<Transaction>) {
    try {
        TransactionStore.save(ctx, list)
    } catch (e: Exception) {
        Log.e("Transactions", "saveTransactions error", e)
    }
}

fun createPendingTransaction(
    ctx: Context,
    description: String,
    amount: String,
    phone: String,
    ussd: String,
    clientName: String = "",
    status: String = TransactionStatus.PENDING.value,
    source: String = TX_SOURCE_SYSTEM,
    showInRecent: Boolean = false,
    offerId: Int = -1
): Int {
    return try {
        TransactionStore.createPendingTransaction(
            context = ctx,
            description = description,
            amount = amount,
            phone = phone,
            ussd = ussd,
            clientName = clientName,
            status = status,
            source = source,
            showInRecent = showInRecent,
            offerId = offerId
        )
    } catch (e: Exception) {
        Log.e("Transactions", "createPendingTransaction error", e)
        -1
    }
}

private fun formatRemainingTimeWithSuffix(ms: Long, suffix: String): String {
    if (ms <= 0L) return "Expired"
    val totalSeconds = ms / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "${days}d ${hours}h ${minutes}m ${seconds}s $suffix"
}

private fun nextCountdownRefreshDelay(ms: Long): Long {
    if (ms <= 0L) return 15_000L
    val remainder = ms % 1_000L
    return when {
        remainder == 0L -> 1_000L
        remainder <= 80L -> 120L
        else -> (remainder + 40L).coerceAtMost(1_000L)
    }
}

fun formatRemainingTimeHome(ms: Long): String = formatRemainingTimeWithSuffix(ms, "left")

fun formatRemainingTimeDetailed(ms: Long): String = formatRemainingTimeWithSuffix(ms, "remaining")

fun formatSignatureLearnedAt(timestamp: Long): String =
    if (timestamp <= 0L) "Not learned yet"
    else SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun OfferItem.clearPendingSignatureReview(): OfferItem = copy(
    pendingLearnedSignature = emptyList(),
    pendingSignatureLearnedAt = 0L,
    pendingSignatureLearningCaptures = emptyList()
)

private data class LearnedStepDetail(
    val stepIndex: Int,
    val menuTitle: String,
    val enteredInput: String,
    val selectedOptionLabel: String,
    val recordedTexts: List<String>,
    val menuOptionsSnapshot: List<String>
)

private fun buildLearnedStepDetails(
    learnedSteps: List<UssdSignatureStep>,
    learningCaptures: List<UssdLearningCapture>
): List<LearnedStepDetail> {
    val stepsByIndex = learnedSteps.associateBy { it.stepIndex }
    val capturesByIndex = learningCaptures.groupBy { it.stepIndex }
    val allStepIndexes = (stepsByIndex.keys + capturesByIndex.keys)
        .distinct()
        .sortedBy { if (it < 0) Int.MAX_VALUE else it }

    return allStepIndexes.map { stepIndex ->
        val step = stepsByIndex[stepIndex]
        val captures = capturesByIndex[stepIndex].orEmpty()
        val latestCapture = captures.lastOrNull()
        LearnedStepDetail(
            stepIndex = stepIndex,
            menuTitle = step?.menuTitle.orEmpty(),
            enteredInput = latestCapture?.enteredInput.orEmpty().ifBlank { step?.expectedInput.orEmpty() },
            selectedOptionLabel = latestCapture?.selectedOptionLabel.orEmpty().ifBlank { step?.selectedOptionLabel.orEmpty() },
            recordedTexts = (listOf(step?.menuText.orEmpty()) + captures.map { it.popupText.trim() })
                .filter { it.isNotBlank() }
                .distinct(),
            menuOptionsSnapshot = step?.menuOptionsSnapshot.orEmpty()
        )
    }
}

fun loadContacts(prefs: SharedPreferences): List<SavedContact> =
    runCatching {
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedContact(o.optString("name", ""), o.getString("phone"))
        }
    }.getOrDefault(emptyList())

fun saveContacts(prefs: SharedPreferences, list: List<SavedContact>) {
    val arr = JSONArray()
    list.forEach { c -> arr.put(JSONObject().apply { put("name", c.name); put("phone", c.phone) }) }
    prefs.edit().putString("list", arr.toString()).apply()
}

fun firstNameFrom(fullName: String): String =
    fullName.trim().split(" ").firstOrNull { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: fullName

fun buildSmsMessage(ctx: Context, outcome: String, fullName: String, offer: String, amount: String, phone: String): String {
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val key = when (outcome.lowercase()) {
        "success" -> "sms_tpl_success"
        "pending" -> "sms_tpl_pending"
        "limit_notice" -> "sms_tpl_limit_notice"
        else -> "sms_tpl_failed"
    }
    val defaultTpl = when (outcome.lowercase()) {
        "success" -> DEFAULT_TPL_SUCCESS
        "pending" -> DEFAULT_TPL_PENDING
        "limit_notice" -> DEFAULT_TPL_LIMIT_NOTICE
        else -> DEFAULT_TPL_FAILED
    }
    val template = prefs.getString(key, defaultTpl) ?: defaultTpl
    val maskedPhone = if (phone.length >= 10) phone.take(4) + "XXXXXX" else phone
    return template
        .replace("{name}", firstNameFrom(fullName))
        .replace("{offer}", offer)
        .replace("{amount}", amount)
        .replace("{phone}", maskedPhone)
}

@SuppressLint("Range")
fun extractMpesaContacts(ctx: Context, daysBack: Int): List<SavedContact> {
    val list = mutableListOf<SavedContact>()
    val seen = mutableSetOf<String>()
    val cutoff = System.currentTimeMillis() - (daysBack * 86400000L)
    val receiver = MpesaReceiver()
    try {
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(c.getColumnIndex(Telephony.Sms.ADDRESS)) ?: continue
                val body = c.getString(c.getColumnIndex(Telephony.Sms.BODY)) ?: continue
                val date = c.getLong(c.getColumnIndex(Telephony.Sms.DATE))
                if (date < cutoff) continue
                if (!address.equals("MPESA", true) && !body.contains("M-Pesa", true)) continue
                val rawPhone = receiver.extractPhoneOrMasked(body).ifBlank {
                    Regex("""(?:254|0)(?:7\d{8}|1\d{8})""").find(body)?.value.orEmpty()
                }
                val name = formatClientName(receiver.extractClientName(body))
                val phone = receiver.resolveMaskedNumber(ctx, rawPhone, name)
                if (!phone.matches(Regex("^0\\d{9}$"))) continue
                if (phone in seen) continue
                seen.add(phone)
                list.add(SavedContact(name, phone))
            }
        }
    } catch (e: Exception) {
        Log.e("MpesaImport", "SMS read error", e)
    }
    return list
}

@SuppressLint("MissingPermission")
fun getAvailableSims(ctx: Context): List<SubscriptionInfo> {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
    return try {
        (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager)
            ?.activeSubscriptionInfoList
            ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

fun calculateOverview(ctx: Context): List<Pair<String, Int>> {
    val prefs = ctx.getSharedPreferences("transactions", Context.MODE_PRIVATE)
    val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { return emptyList() }
    val map = mutableMapOf<String, Int>()
    for (i in 0 until arr.length()) {
        val desc = arr.getJSONObject(i).optString("description", "")
        if (desc.isNotBlank()) map[desc] = (map[desc] ?: 0) + 1
    }
    return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

// ─── Shared UI Components ───────────────────────────────────────────────
@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.cyan.copy(alpha = 0.85f), unfocusedBorderColor = C.border.copy(alpha = 0.9f),
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan,
    focusedContainerColor = C.cardHi.copy(alpha = 0.98f),
    unfocusedContainerColor = C.card.copy(alpha = 0.96f),
    focusedPlaceholderColor = C.t3,
    unfocusedPlaceholderColor = C.t3,
    focusedLeadingIconColor = C.t2,
    unfocusedLeadingIconColor = C.t2,
    focusedTrailingIconColor = C.t2,
    unfocusedTrailingIconColor = C.t2
)

@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.cyan, unfocusedBorderColor = C.border,
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan, focusedContainerColor = C.cardHi, unfocusedContainerColor = C.cardHi
)

@Composable
fun VolcanicSurface(
    modifier: Modifier = Modifier,
    elevation: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = C.card,
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.65f)),
        shadowElevation = elevation
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    listOf(C.cardHi.copy(alpha = 0.96f), C.card.copy(alpha = 0.98f))
                )
            )
        ) {
            content()
        }
    }
}

@Composable
fun PageHeader(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.2).sp,
            color = C.t1
        )
        Text(
            subtitle,
            color = C.t2,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ManualHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, top = 6.dp, end = 24.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.0).sp,
            color = C.t1,
            textAlign = TextAlign.Center
        )
        Text(
            subtitle,
            color = C.t2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.35f))))
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = C.t1, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun OverviewStatChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SettingsOverviewCard(
    themeMode: String,
    autoEnabled: Boolean,
    remoteEnabled: Boolean,
    twoPhoneEnabled: Boolean
) {
    val accent = when {
        autoEnabled && twoPhoneEnabled -> C.green
        autoEnabled -> C.cyan
        else -> C.amber
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.cardHi.copy(alpha = 0.94f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.11f), C.cardHi.copy(alpha = 0.98f), C.surface.copy(alpha = 0.96f))
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Control Center", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Manage appearance, automation, relay mode, alerts, and customer notifications from one place.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Tune, null, tint = accent, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverviewStatChip("Theme", themeMode, C.cyan)
                OverviewStatChip("Automation", if (autoEnabled) "ON" else "OFF", if (autoEnabled) C.green else C.amber)
                OverviewStatChip("Remote", if (remoteEnabled) "ARMED" else "OFF", if (remoteEnabled) C.blue else C.t3)
                OverviewStatChip("Relay", if (twoPhoneEnabled) "2-PHONE" else "SINGLE", if (twoPhoneEnabled) C.purple else C.t3)
            }
        }
    }
}

@Composable
private fun PurchasePerkChip(icon: ImageVector, label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(13.dp))
            Text(
                label,
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TokenHeroInfoLine(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = C.t3, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokensHeroCard(
    balance: Int,
    activePlan: UnlimitedManager.Plan?,
    remainingMs: Long
) {
    val accent = C.amber
    val totalSeconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val remainingLabel = if (activePlan != null) "${days}d ${hours}h ${minutes}m ${seconds}s" else ""
    val heroAlignment = if (activePlan != null) Alignment.Start else Alignment.CenterHorizontally
    val detailTextAlign = if (activePlan != null) TextAlign.Start else TextAlign.Center
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp
        val balanceValueFontSize = balanceValueFontSize(
            value = balance.toString(),
            short = if (compact) 56.sp else 64.sp,
            medium = if (compact) 48.sp else 56.sp,
            long = if (compact) 42.sp else 48.sp,
            extraLong = if (compact) 34.sp else 40.sp
        )
        val detailFontSize = if (compact) 12.sp else 13.sp
        val remainingFontSize = balanceCaptionFontSize(
            value = remainingLabel,
            short = if (activePlan != null) {
                if (compact) 20.sp else 22.sp
            } else {
                if (compact) 18.sp else 22.sp
            },
            medium = if (activePlan != null) {
                if (compact) 18.sp else 20.sp
            } else {
                if (compact) 16.sp else 19.sp
            },
            long = if (compact) 16.sp else 18.sp
        )
        val activeProgress = if (activePlan != null && activePlan.durationMs > 0L) {
            (remainingMs.toFloat() / activePlan.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF14181A),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF21211C),
                                Color(0xFF14181A),
                                Color(0xFF0F1418)
                            )
                        )
                    )
                    .padding(
                        horizontal = if (compact) 18.dp else 22.dp,
                        vertical = if (compact) 18.dp else 22.dp
                    ),
                horizontalAlignment = heroAlignment,
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
            ) {
                if (activePlan != null) {
                    val accentActive = C.green
                    val remainingText = buildAnnotatedString {
                        append("${days}d ${hours}h ${minutes}m ")
                        withStyle(SpanStyle(color = accentActive)) {
                            append("${seconds}s")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accentActive)
                            )
                            Text(
                                "Unlimited active",
                                color = accentActive,
                                fontSize = detailFontSize,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        Text(
                            "Admin grant",
                            color = C.t2,
                            fontSize = detailFontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "REMAINING TIME",
                        color = C.t3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        remainingText,
                        fontSize = remainingFontSize,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = remainingFontSize * 1.05f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(C.surface.copy(alpha = 0.92f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(activeProgress)
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(accentActive.copy(alpha = 0.92f), accentActive.copy(alpha = 0.62f))
                                    )
                                )
                        )
                    }
                    val durationDays = (activePlan.durationMs / 86_400_000L).toInt().coerceAtLeast(0)
                    val windowLabel = when {
                        durationDays > 0 -> "${durationDays}-day"
                        else -> "${(activePlan.durationMs / 3_600_000L).toInt().coerceAtLeast(1)}-hour"
                    }
                    Text(
                        "${(activeProgress * 100f).toInt()}% of your $windowLabel window left",
                        color = C.t2,
                        fontSize = detailFontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(C.border.copy(alpha = 0.65f))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TokenHeroInfoLine(
                            icon = Icons.Outlined.Schedule,
                            label = "Stays active until the timer ends"
                        )
                        TokenHeroInfoLine(
                            icon = Icons.Outlined.History,
                            label = "Stored tokens return once this expires"
                        )
                    }
                } else {
                    Text(
                        balance.toString(),
                        fontSize = balanceValueFontSize,
                        fontWeight = FontWeight.Black,
                        lineHeight = balanceValueFontSize,
                        color = C.t1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "AVAILABLE TOKENS",
                        color = accent,
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                        textAlign = detailTextAlign,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Bolt, null, tint = accent, modifier = Modifier.size(16.dp))
                            Text(
                                "1 token = 1 USSD call",
                                color = C.t2,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .height(18.dp)
                                .background(C.border.copy(alpha = 0.65f))
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Shield, null, tint = accent, modifier = Modifier.size(16.dp))
                            Text(
                                "Never expire",
                                color = C.t2,
                                fontSize = if (compact) 12.sp else 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FieldLabel(text: String) = Text(
    text, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.3.sp, modifier = Modifier.padding(bottom = 8.dp)
)

@Composable
private fun ManualSectionCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector = Icons.Outlined.Tune,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor by animateColorAsState(
        if (highlighted) accent.copy(alpha = 0.26f) else C.border.copy(alpha = 0.9f),
        label = "console_section_border"
    )
    val iconBg by animateColorAsState(
        if (highlighted) accent.copy(alpha = 0.12f) else C.surface,
        label = "console_section_icon_bg"
    )
    val accentWidth by animateDpAsState(
        if (highlighted) 24.dp else 16.dp,
        label = "console_section_accent"
    )
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = C.card.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(iconBg)
                            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(title, color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Box(
                        Modifier
                            .width(accentWidth)
                            .height(4.dp)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.9f))
                    )
                }
                Divider(color = C.w08)
                content()
            }
        )
    }
}

@Composable
private fun ManualHeroCard(
    dispatchReady: Boolean,
    bannerState: String?,
    enabledOfferCount: Int,
    directoryCount: Int,
    historyCount: Int,
    smsSearchLoading: Boolean
) {
    val statusColor = when {
        bannerState == "failed" -> C.red
        bannerState == "success" -> C.green
        bannerState == "pending" -> C.amber
        bannerState == "relayed" -> C.blue
        dispatchReady -> C.green
        smsSearchLoading -> C.cyan
        else -> C.cyan
    }
    val statusLabel = when {
        bannerState == "pending" -> "Dispatch in progress"
        bannerState == "success" -> "Last dispatch completed successfully"
        bannerState == "failed" -> "Last dispatch failed"
        bannerState == "relayed" -> "Request forwarded to relay"
        dispatchReady -> "Ready to execute dispatch"
        smsSearchLoading -> "Refreshing smart match directory"
        else -> "Enter customer details to prepare dispatch"
    }
    Surface(
        color = C.cardHi.copy(alpha = 0.9f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            statusColor.copy(alpha = 0.14f),
                            C.cardHi.copy(alpha = 0.98f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.18f))
                    ) {
                        Text(
                            "CONSOLE READY",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                    Text("Ready to dispatch", color = C.t1, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(
                        "Simple manual dispatch with quick customer matching and clear status feedback.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(statusColor.copy(alpha = 0.24f), statusColor.copy(alpha = 0.08f))
                            )
                        )
                        .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AutoMode, null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ManualQuickStat("Enabled offers", enabledOfferCount.toString(), C.cyan)
                ManualQuickStat("Directory", directoryCount.toString(), C.green)
                ManualQuickStat("History", historyCount.toString(), C.blue)
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = statusColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.20f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("STATUS", color = statusColor.copy(alpha = 0.72f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualQuickStat(label: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = C.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(label.uppercase(), color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun RowScope.ManualTabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) C.cardHi else Color.Transparent,
        label = "console_tab_bg"
    )
    val fg by animateColorAsState(
        if (selected) C.t1 else C.t2,
        label = "console_tab_fg"
    )
    val border by animateColorAsState(
        if (selected) C.borderHi.copy(alpha = 0.65f) else Color.Transparent,
        label = "console_tab_border"
    )
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .heightIn(min = 52.dp)
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(bg, bg)
                    )
                } else {
                    Brush.linearGradient(listOf(bg, bg))
                }
            )
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text,
                color = fg,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ManualHistoryPreviewCard(
    tx: Transaction,
    onClick: () -> Unit
) {
    val statusColor = when (tx.status) {
        TransactionStatus.SUCCESS.value -> C.green
        TransactionStatus.FAILED.value, TransactionStatus.CANCELLED.value -> C.red
        TransactionStatus.PROCESSING.value, TransactionStatus.PENDING.value, TransactionStatus.RETRYING.value -> C.amber
        else -> C.t2
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = C.card.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        tx.clientName.ifBlank { "Unknown Customer" },
                        color = C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        tx.phoneNumber.ifBlank { "Phone not available" },
                        color = C.t2,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        tx.amount,
                        color = C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            tx.status,
                            color = statusColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tx.description.ifBlank { "Transaction" },
                    color = C.cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    tx.date,
                    color = C.t3,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun FeedbackBanner(msg: String, color: Color) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.10f), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(msg, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PillBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MiniTag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.10f)) {
        Text(text, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingsGroup(title: String, accent: Color = C.amber, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(30.dp), clip = false)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF21211C),
                        Color(0xFF14181A),
                        Color(0xFF0F1418)
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(30.dp))
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                title.uppercase(),
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.6.sp
            )
        }
        Divider(color = accent.copy(alpha = 0.18f), thickness = 0.8.dp)
        content()
    }
}

@Composable
fun GroupDivider(accent: Color = C.amber) = Divider(
    color = accent.copy(alpha = 0.16f),
    thickness = 0.8.dp,
    modifier = Modifier.padding(horizontal = 20.dp)
)

@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(sub, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
        }
        ToggleSwitch(checked, onChange)
    }
}

@Composable
fun LinkRow(icon: ImageVector, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1F21))
                .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                color = C.t1,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                sub,
                color = C.t2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            null,
            tint = color,
            modifier = Modifier
                .size(22.dp)
        )
    }
}

@Composable
fun LimitRow(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = value.toString(), onValueChange = { onValueChange(it.toIntOrNull() ?: value) },
            modifier = Modifier.width(96.dp), shape = RoundedCornerShape(14.dp),
            colors = fieldColors(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
fun SimPickerRow(title: String, sub: String, sims: List<SubscriptionInfo>, current: Int, onSelect: (Int) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Rounded.SimCard)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (current == -1) "Default SIM" else sims.find { it.subscriptionId == current }?.displayName?.toString() ?: "SIM $current",
                color = C.t2, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Box {
            TextButton(
                onClick = { exp = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Change", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = exp, onDismissRequest = { exp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                DropdownMenuItem(text = { Text("Default", color = C.t1) }, onClick = { onSelect(-1); exp = false })
                sims.forEach { s ->
                    DropdownMenuItem(text = { Text("${s.displayName} · Slot ${s.simSlotIndex + 1}", color = C.t1) }, onClick = { onSelect(s.subscriptionId); exp = false })
                }
            }
        }
    }
}

@Composable
fun SettingsRowIcon(icon: ImageVector, tint: Color = C.t1) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(C.surface.copy(alpha = 0.95f))
            .border(1.dp, C.border.copy(alpha = 0.85f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val target = if (checked) 25.dp else 3.dp
    val offset by animateDpAsState(target, animationSpec = tween(200), label = "toggle")
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) C.cyan.copy(alpha = 0.92f) else C.surface)
            .border(1.dp, if (checked) C.cyan.copy(alpha = 0.35f) else C.border.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) C.bg else C.t2)
        )
    }
}

@Composable
fun TemplateEditor(label: String, value: String, accentColor: Color, hint: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(13.dp).clip(RoundedCornerShape(2.dp)).background(accentColor))
            Spacer(Modifier.width(7.dp))
            Text(label, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            minLines = 3, maxLines = 5,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = C.t1),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor, unfocusedBorderColor = C.border,
                focusedContainerColor = C.cardHi, unfocusedContainerColor = C.cardHi,
                cursorColor = accentColor, focusedTextColor = C.t1, unfocusedTextColor = C.t1
            ),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(5.dp))
        Text(hint, color = C.t3, fontSize = 10.sp, letterSpacing = 0.3.sp)
    }
}

@Composable
fun TemplatePreview(template: String, accentColor: Color) {
    val preview = template
        .replace("{name}", "Mary")
        .replace("{offer}", "1GB Daily")
        .replace("{amount}", "50")
        .replace("{phone}", "0704XXXXXX")
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PREVIEW", color = C.t3, fontSize = 9.sp, letterSpacing = 2.sp)
            Spacer(Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(50), color = accentColor.copy(alpha = 0.10f)) {
                Text("sample data", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = accentColor.copy(alpha = 0.65f), fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.05f))
                .border(1.dp, accentColor.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(accentColor))
                    Spacer(Modifier.width(6.dp))
                    Text("SMS to 0704XXXXXX", color = accentColor.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(preview, color = C.t1.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
fun DialogField(label: String, value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType = KeyboardType.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(hint, color = C.t3) },
            colors = dialogFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
        )
        if (hint.isNotBlank()) {
            Text(hint, color = C.t3, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
fun DialogDropdown(label: String, value: String, opts: List<String>, expanded: Boolean, onToggle: () -> Unit, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(C.cardHi.copy(alpha = 0.98f), C.card.copy(alpha = 0.92f))
                    )
                )
                .border(1.dp, if (expanded) C.cyan.copy(alpha = 0.55f) else C.border, RoundedCornerShape(18.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(C.w04),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = if (expanded) C.cyan else C.t2)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onToggle,
                modifier = Modifier
                    .background(C.cardHi, RoundedCornerShape(12.dp))
                    .border(1.dp, C.border, RoundedCornerShape(12.dp))
            ) {
                opts.forEach { o ->
                    DropdownMenuItem(text = { Text(o, color = C.t1) }, onClick = { onSelect(o) })
                }
            }
        }
    }
}

// ─── Mpesa Import Dialog ─────────────────────────────────────────────────
@Composable
fun MpesaImportDialog(onDismiss: () -> Unit, onImported: (List<SavedContact>) -> Unit) {
    var daysBack by remember { mutableIntStateOf(30) }
    var contacts by remember { mutableStateOf<List<SavedContact>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    LaunchedEffect(daysBack) {
        loading = true
        contacts = withContext(Dispatchers.IO) { extractMpesaContacts(ctx, daysBack) }.sortedBy { it.name }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from M-PESA SMS") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scan last")
                    Spacer(Modifier.width(8.dp))
                    Slider(value = daysBack.toFloat(), onValueChange = { daysBack = it.toInt() }, valueRange = 1f..90f, steps = 89)
                    Spacer(Modifier.width(8.dp))
                    Text("$daysBack days")
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Filter…") }, modifier = Modifier.fillMaxWidth())
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filt = contacts.filter { search.isBlank() || it.name.contains(search, true) || it.phone.contains(search) }
                    if (filt.isEmpty()) Text("No contacts found.")
                    else LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(filt, key = { it.phone }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.w04)
                                    .clickable { selected = if (c.phone in selected) selected - c.phone else selected + c.phone }
                                    .padding(10.dp)
                            ) {
                                Checkbox(checked = c.phone in selected, onCheckedChange = { s -> selected = if (s) selected + c.phone else selected - c.phone })
                                Text(c.name.ifBlank { c.phone })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImported(contacts.filter { it.phone in selected }) }, enabled = selected.isNotEmpty()) {
                Text("Import ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Root App ────────────────────────────────────────────────────────────
@Composable
fun BingwaApp() {
    val ctx = LocalContext.current
    val tm = remember { TokenManager(ctx) }
    val unlimitedManager = remember { UnlimitedManager(ctx) }
    val appPrefs = remember { ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tokenBal by remember { mutableIntStateOf(tm.getBalance()) }
    var airBal by remember { mutableStateOf(BalanceChecker.currentBalanceStr) }
    var isRefreshing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(appPrefs.safeGetBoolean("automation_enabled", true)) }
    var remainingMs by remember { mutableLongStateOf(unlimitedManager.remainingMs()) }
    val txns = remember { mutableStateListOf<Transaction>() }
    val relayCfg by RelayManager.configState.collectAsState()
    val mirroredPrimaryAirtime by RelayManager.mirroredPrimaryAirtime.collectAsState()
    val toggleRunning = {
        running = !running
        appPrefs.edit().putBoolean("automation_enabled", running).apply()
        if (!running) ctx.stopService(Intent(ctx, BalanceChecker::class.java))
        else if (canUsePhoneAutomation(ctx)) ServiceLauncher.startBalanceChecker(ctx)
        vib(ctx, if (running) 140L else 120L)
    }

    LaunchedEffect(Unit) {
        loadTransactions(ctx, txns)
        remainingMs = unlimitedManager.remainingMs()
        runCatching { RelayManager.load(ctx) }
            .onFailure { Log.e("MainActivity", "Relay warm-up failed", it) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            remainingMs = unlimitedManager.remainingMs()
            delay(if (remainingMs > 0L) nextCountdownRefreshDelay(remainingMs) else 30_000L)
        }
    }

    DisposableEffect(Unit) {
        BalanceChecker.balanceCallback = { display -> airBal = display; isRefreshing = false }
        TokenManager.tokenBalanceListener = { newTok ->
            tokenBal = newTok
            remainingMs = unlimitedManager.remainingMs()
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                when (intent.action) {
                    ACTION_TX_CREATED -> {
                        val tx = loadTransactionByIdFromPrefs(ctx, txId) ?: return
                        val idx = txns.indexOfFirst { it.id == txId }
                        if (idx >= 0) txns[idx] = tx else txns.add(0, tx)
                    }
                    "com.bingwa.mobile.TX_UPDATED" -> {
                        val idx = txns.indexOfFirst { it.id == txId }
                        val updatedTx = loadTransactionByIdFromPrefs(ctx, txId)
                        if (idx >= 0 && updatedTx != null) {
                            txns[idx] = updatedTx
                        } else if (updatedTx != null) {
                            txns.add(0, updatedTx)
                        }
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.TX_UPDATED")
            addAction(ACTION_TX_CREATED)
        })
        onDispose {
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(ctx, relayCfg.enabled, relayCfg.role, relayCfg.method) {
        if (relayCfg.enabled && relayCfg.role == "PRIMARY" && relayCfg.method == "HOTSPOT") {
            runCatching { RelayManager.startHotspotMonitor(ctx) }
                .onFailure { Log.e("MainActivity", "Unable to start relay hotspot monitor", it) }
        } else {
            runCatching { RelayManager.stopHotspotMonitor() }
        }
        onDispose { runCatching { RelayManager.stopHotspotMonitor() } }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            VolcanicNavBar(
                current = screen,
                running = running,
                onSelect = { screen = it },
                onToggleRunning = toggleRunning
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().background(C.bg).padding(pad)) {
            AnimatedContent(targetState = screen, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "screen") { s ->
                val unlimitedLabel = unlimitedManager.getActivePlan()?.label?.takeIf { remainingMs > 0L }
                val displayedAirBal =
                    if (relayCfg.enabled && relayCfg.role == "RELAY") mirroredPrimaryAirtime.ifBlank { airBal }
                    else airBal
                when (s) {
                    Screen.Home     -> HomeScreenVolcanic(
                        tokenBal = tokenBal,
                        airBal = displayedAirBal,
                        isRefreshing = isRefreshing,
                        txns = txns,
                        running = running,
                        unlimitedLabel = unlimitedLabel,
                        unlimitedRemaining = unlimitedLabel?.let { formatRemainingTimeHome(remainingMs) },
                        onRefresh = {
                            if (relayCfg.enabled && relayCfg.role == "RELAY") {
                                airBal = mirroredPrimaryAirtime.ifBlank { airBal }
                            } else if (!isRefreshing) {
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(ctx)) isRefreshing = false
                            }
                        },
                        onToggleRunning = toggleRunning
                    )
                    Screen.Manual   -> ManualScreen(txns)
                    Screen.Tokens   -> TokensScreen()
                    Screen.Contacts -> ContactsScreen()
                    Screen.Settings -> GroupedSettingsScreen()
                }
            }
        }
    }
}

// ─── Bottom Navigation Bar ───────────────────────────────────────────────
@Composable
private fun VolcanicNavBar(current: Screen, running: Boolean, onSelect: (Screen) -> Unit, onToggleRunning: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp)
    ) {
        val compact = maxWidth < 420.dp
        val centerSlotWidth = if (compact) 92.dp else 106.dp
        val navHeight = if (compact) 78.dp else 86.dp
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = C.surface.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f)),
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                C.cyan.copy(alpha = 0.06f),
                                Color.Transparent,
                                C.blue.copy(alpha = 0.05f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(navHeight)
                        .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NAV_ITEMS.take(2).forEach { item ->
                            NavBarItemButton(item, current == item, compact = compact) { onSelect(item) }
                        }
                    }
                    Spacer(Modifier.width(centerSlotWidth))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NAV_ITEMS.drop(2).forEach { item ->
                            NavBarItemButton(item, current == item, compact = compact) { onSelect(item) }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = if (compact) (-6).dp else (-8).dp)
                        .width(centerSlotWidth),
                    contentAlignment = Alignment.Center
                ) {
                    StartNavButton(running = running, onClick = onToggleRunning, compact = compact)
                }
            }
        }
    }
}

@Composable
private fun NavBarItemButton(item: Screen, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val selectedTint = C.amber
    Column(
        modifier = Modifier
            .width(if (compact) 60.dp else 68.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (selected) C.amber.copy(alpha = 0.18f) else Color.Transparent,
            border = if (selected) BorderStroke(1.dp, C.amber.copy(alpha = 0.28f)) else BorderStroke(1.dp, Color.Transparent)
        ) {
            Box(
                modifier = Modifier.size(width = if (compact) 42.dp else 46.dp, height = if (compact) 36.dp else 38.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (selected) item.iconSel else item.icon,
                    null,
                    tint = if (selected) selectedTint else C.t3,
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                )
            }
        }
        Text(
            item.label,
            modifier = Modifier.fillMaxWidth(),
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) selectedTint else C.t3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StartNavButton(running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ctx = LocalContext.current
    val color = if (running) C.green else C.red
    val auraAnim = rememberInfiniteTransition(label = "start_nav_aura")
    val pulseScale by auraAnim.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "start_nav_pulse_scale"
    )
    val pulseAlpha by auraAnim.animateFloat(
        initialValue = if (running) 0.24f else 0.16f,
        targetValue = if (running) 0.10f else 0.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "start_nav_pulse_alpha"
    )
    val holdAction = {
        vib(ctx, 70L)
        Toast.makeText(
            ctx,
            if (running) "Stopping automation..." else "Starting automation...",
            Toast.LENGTH_SHORT
        ).show()
        onClick()
    }
    Box(
        modifier = modifier
            .size(if (compact) 82.dp else 92.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 68.dp else 78.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(CircleShape)
                .background(color.copy(alpha = pulseAlpha))
        )
        Surface(
            shape = CircleShape,
            color = C.cardHi,
            border = BorderStroke(2.dp, color.copy(alpha = 0.88f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .size(if (compact) 60.dp else 68.dp)
                .combinedClickable(
                    onClick = {
                        Toast.makeText(
                            ctx,
                            if (running) "Long press to stop automation" else "Long press to start automation",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onLongClick = holdAction
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    color.copy(alpha = 0.34f),
                                    color.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    Icons.Outlined.PowerSettingsNew,
                    null,
                    tint = color,
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp)
                )
            }
        }
    }
}

@Composable
private fun AutomationControlCard(running: Boolean, onToggle: () -> Unit) {
    val accent = if (running) C.red else C.green
    val anim = rememberInfiniteTransition(label = "ctrl_anim")
    val ringScale by anim.animateFloat(1f, 1.28f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "ringScale")
    val ringAlpha by anim.animateFloat(0.22f, 0.06f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "ringAlpha")
    val btnFg = if (accent.luminance() > 0.45f) Color.Black else Color.White

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = C.card,
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.38f)),
        shadowElevation = 8.dp
    ) {
        Box {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent))))
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 0.dp)
                    .fillMaxWidth(0.52f).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent)))
            )
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxSize().scale(ringScale).clip(CircleShape)
                            .border(1.5.dp, accent.copy(alpha = ringAlpha), CircleShape)
                    )
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape)
                            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f))))
                    ) {
                        if (running) {
                            Box(Modifier.align(Alignment.Center).size(11.dp).clip(RoundedCornerShape(3.dp)).background(btnFg))
                        } else {
                            Canvas(Modifier.matchParentSize()) {
                                val w = size.width
                                val h = size.height
                                val p = Path().apply {
                                    moveTo(w * 0.42f, h * 0.33f)
                                    lineTo(w * 0.42f, h * 0.67f)
                                    lineTo(w * 0.70f, h * 0.50f)
                                    close()
                                }
                                drawPath(p, btnFg)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (running) "Automation Running" else "Automation Paused",
                        color = C.t1,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (running) "Tap to stop background processing" else "Tap to start background processing",
                        color = C.t2,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
                Button(
                    onClick = onToggle,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = btnFg),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "STOP" else "START", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, letterSpacing = 1.0.sp)
                }
            }
        }
    }
}

// ─── Home Screen ──────────────────────────────────────────────────────────
@Composable
fun HomeScreenVolcanic(
    tokenBal: Int,
    airBal: String,
    isRefreshing: Boolean,
    txns: MutableList<Transaction>,
    running: Boolean,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    onRefresh: () -> Unit,
    onToggleRunning: () -> Unit
) {
    val ctx = LocalContext.current
    var dayKey by remember { mutableIntStateOf(currentDayKey()) }
    LaunchedEffect(dayKey) {
        delay(millisUntilNextMidnight() + 250L)
        dayKey = currentDayKey()
    }
    val automatedTxns = txns
        .asSequence()
        .filter { it.showInRecent && transactionDayKey(it) == dayKey }
        .sortedByDescending { transactionTimestamp(it) }
        .toList()
    val sentCount = automatedTxns.size
    val pendingCount = automatedTxns.count {
        it.statusEnum == TransactionStatus.PROCESSING ||
            it.statusEnum == TransactionStatus.PENDING ||
            it.statusEnum == TransactionStatus.RETRYING
    }
    val failedCount = automatedTxns.count {
        it.statusEnum == TransactionStatus.FAILED || it.statusEnum == TransactionStatus.CANCELLED
    }
    val completedCount = automatedTxns.count { it.statusEnum == TransactionStatus.SUCCESS }
    var selectedTxId by rememberSaveable { mutableIntStateOf(-1) }
    val selectedTx = automatedTxns.firstOrNull { it.id == selectedTxId }
    val chromeAnim = rememberInfiniteTransition(label = "home_chrome")
    val spin by chromeAnim.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin"
    )
    val topTransactions = automatedTxns

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0C0D),
                        Color(0xFF0A0C0D),
                        Color(0xFF08090A)
                    )
                )
            )
    ) {
        Box(
            Modifier
                .size(320.dp)
                .offset((-120).dp, (-60).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFB454).copy(alpha = 0.08f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(70.dp, (-20).dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF74E6D8).copy(alpha = 0.07f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 20.dp)
        ) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HomeHeroHeader(running = running)
                        HomeSplitBalanceCard(
                            airBal = airBal.ifBlank { "0.00" },
                            tokenValue = if (unlimitedLabel != null) "Unlimited" else tokenBal.toString(),
                            tokenHint = unlimitedRemaining ?: "Never expire",
                            sentCount = sentCount,
                            pendingCount = pendingCount,
                            failedCount = failedCount,
                            completedCount = completedCount,
                            isRefreshing = isRefreshing,
                            spin = spin,
                            onRefresh = onRefresh
                        )
                        HomeActivityHeading(automatedCount = automatedTxns.size)
                        HomeActivityPanel(
                            transactions = topTransactions,
                            onOpenTransaction = { selectedTxId = it.id },
                            onDeleteTransaction = { tx ->
                                txns.remove(tx)
                                saveTransactions(ctx, txns.toList())
                                if (selectedTxId == tx.id) selectedTxId = -1
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        if (selectedTx != null) {
            TransactionDetailDialog(
                tx = selectedTx,
                onDismiss = { selectedTxId = -1 },
                onDelete = {
                    txns.removeAll { it.id == selectedTx.id }
                    saveTransactions(ctx, txns.toList())
                    selectedTxId = -1
                },
                onRetry = { tx ->
                    val result = retryRecentTransaction(ctx, tx)
                    Toast.makeText(ctx, result.message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    if (result.success) {
                        selectedTxId = if (result.newTxId >= 0) result.newTxId else -1
                    }
                }
            )
        }
    }
}

@Composable
private fun HomeHardwareStrip(
    timeLabel: String,
    batteryPercent: Int?,
    modifier: Modifier = Modifier
) {
    val stripBg = Color(0xFF15191B)
    val lineSoft = Color(0xFF262D2F)
    val textDim = Color(0xFF8A9396)
    val textDimmer = Color(0xFF5B6366)
    val amber = Color(0xFFFFB454)
    val cyan = Color(0xFF74E6D8)
    val ledAnim = rememberInfiniteTransition(label = "home_led")
    val amberAlpha by ledAnim.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "home_led_alpha"
    )

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(116.dp)
                .height(21.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(Color(0xFF050605))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.015f), stripBg)
                    )
                )
                .border(1.dp, lineSoft, RoundedCornerShape(24.dp))
                .padding(start = 22.dp, top = 15.dp, end = 22.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(cyan).shadow(4.dp, CircleShape))
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer { alpha = amberAlpha }
                        .clip(CircleShape)
                        .background(amber)
                )
                Box(Modifier.size(6.dp).clip(CircleShape).background(textDimmer))
                Text(
                    "AGENT 042",
                    color = textDimmer,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    timeLabel,
                    color = textDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                HomeSignalBars(tint = textDim)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, textDimmer, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${batteryPercent ?: 28}%",
                        color = textDim,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSignalBars(tint: Color) {
    Row(
        modifier = Modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(7.dp, 11.dp, 15.dp, 19.dp).forEach { height ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
private fun HomeHeroHeader(running: Boolean) {
    val cyan = Color(0xFF74E6D8)
    val borderColor = if (running) cyan.copy(alpha = 0.35f) else Color(0xFFFFB454).copy(alpha = 0.35f)
    val pillText = if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 0.dp, start = 10.dp, end = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Bingwa Mobile",
                color = Color(0xFFEEF2F1),
                fontSize = 25.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "USSD Automation Platform",
                color = Color(0xFF8A9396),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(cyan)
                    )
                    Text(
                        pillText,
                        color = cyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.0.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            RelayStatusPill()
        }
    }
}

@Composable
private fun HomeSplitBalanceCard(
    airBal: String,
    tokenValue: String,
    tokenHint: String,
    sentCount: Int,
    pendingCount: Int,
    failedCount: Int,
    completedCount: Int,
    isRefreshing: Boolean,
    spin: Float,
    onRefresh: () -> Unit
) {
    val amber = Color(0xFFFFB454)
    val cyan = Color(0xFF74E6D8)
    val mint = Color(0xFF62E2AE)
    val coral = Color(0xFFFF8A8A)
    val completedAccent = Color(0xFFB79BFF)
    val text = Color(0xFFEEF2F1)
    val textDim = Color(0xFF8A9396)
    val textDimmer = Color(0xFF5B6366)
    val cardBg = Color(0xFF1C2123)
    val line = Color(0xFF333B3E)
    val lineSoft = Color(0xFF262D2F)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        amber.copy(alpha = 0.10f),
                        cyan.copy(alpha = 0.07f),
                        cardBg
                    )
                )
            )
            .border(1.dp, lineSoft, RoundedCornerShape(18.dp))
            .clickable(onClick = onRefresh)
            .padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        val compact = maxWidth < 380.dp
        val airtimeFontSize = balanceValueFontSize(
            airBal,
            if (compact) 20.sp else 22.sp,
            if (compact) 17.sp else 19.sp,
            if (compact) 14.sp else 16.sp,
            if (compact) 12.sp else 14.sp
        )
        val tokenValueFontSize = balanceValueFontSize(
            tokenValue,
            if (compact) 22.sp else 24.sp,
            if (compact) 18.sp else 20.sp,
            if (compact) 16.sp else 18.sp,
            if (compact) 14.sp else 16.sp
        )
        val tokenHintFontSize = balanceCaptionFontSize(
            tokenHint,
            if (compact) 9.sp else 9.5.sp,
            if (compact) 8.5.sp else 9.sp,
            if (compact) 8.sp else 8.5.sp
        )
        val statsSpacing = if (compact) 5.dp else 7.dp

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(amber))
                        Text(
                            "AIRTIME BALANCE",
                            color = textDimmer,
                            fontSize = 9.5.sp,
                            letterSpacing = 1.2.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF2E3437),
                            border = BorderStroke(1.dp, lineSoft)
                        ) {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    null,
                                    tint = textDim,
                                    modifier = Modifier
                                        .size(12.dp)
                                        .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                                )
                            }
                        }
                    }
                    Text(
                        airBal,
                        color = text,
                        fontSize = airtimeFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        if (isRefreshing) "checking balance" else "tap card to refresh",
                        color = textDimmer,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = if (compact) 10.dp else 12.dp)
                        .width(1.dp)
                        .height(if (compact) 62.dp else 68.dp)
                        .background(line)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "TOKENS",
                        color = textDimmer,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Text(
                        tokenValue,
                        color = text,
                        fontSize = tokenValueFontSize,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        tokenHint,
                        color = textDimmer,
                        fontSize = tokenHintFontSize,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    HomeSparkLine(compact = compact)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(line)
            )
            HomeStatsRow(
                sent = sentCount,
                pending = pendingCount,
                failed = failedCount,
                completed = completedCount,
                compact = compact,
                spacing = statsSpacing,
                sentAccent = mint,
                pendingAccent = amber,
                failedAccent = coral,
                completedAccent = completedAccent
            )
        }
    }
}

@Composable
private fun HomeSparkLine(compact: Boolean) {
    Canvas(modifier = Modifier.size(width = if (compact) 52.dp else 56.dp, height = if (compact) 14.dp else 16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.72f)
            cubicTo(
                size.width * 0.12f,
                size.height * 0.86f,
                size.width * 0.24f,
                size.height * 0.10f,
                size.width * 0.36f,
                size.height * 0.33f
            )
            cubicTo(
                size.width * 0.48f,
                size.height * 0.52f,
                size.width * 0.62f,
                size.height * 0.84f,
                size.width * 0.76f,
                size.height * 0.38f
            )
            cubicTo(
                size.width * 0.84f,
                size.height * 0.12f,
                size.width * 0.91f,
                size.height * 0.03f,
                size.width * 0.98f,
                size.height * 0.12f
            )
        }
        drawPath(
            path = path,
            color = Color(0xFF74E6D8),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(
            color = Color(0xFF74E6D8),
            radius = 2.dp.toPx(),
            center = Offset(size.width * 0.98f, size.height * 0.12f)
        )
    }
}

@Composable
private fun HomeActivityHeading(automatedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFFFB454), Color(0xFF74E6D8))
                        )
                    )
            )
            Text(
                "Recent Activity",
                color = Color(0xFFEEF2F1),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xFF1C2123),
            border = BorderStroke(1.dp, Color(0xFF333B3E))
        ) {
            Text(
                "$automatedCount automated",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color(0xFF8A9396),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HomeActivityPanel(
    transactions: List<Transaction>,
    onOpenTransaction: (Transaction) -> Unit,
    onDeleteTransaction: (Transaction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transactions.isEmpty()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF1B2225),
                            Color(0xFF141A1C)
                        )
                    )
                )
                .border(1.dp, Color(0xFF2A3235).copy(alpha = 0.92f), RoundedCornerShape(26.dp))
                .heightIn(min = 198.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            HomeScanningEmptyState(Modifier.fillMaxSize())
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            transactions.forEach { tx ->
                HomeDispatchRow(
                    tx = tx,
                    onOpen = { onOpenTransaction(tx) },
                    onDelete = { onDeleteTransaction(tx) }
                )
            }
        }
    }
}

@Composable
private fun HomeScanningEmptyState(modifier: Modifier = Modifier) {
    val anim = rememberInfiniteTransition(label = "scan_panel")
    val ring1 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "scan_ring_1"
    )
    val ring2 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            initialStartOffset = StartOffset(1000)
        ),
        label = "scan_ring_2"
    )
    val ring3 by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3000, easing = LinearEasing),
            initialStartOffset = StartOffset(2000)
        ),
        label = "scan_ring_3"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(188.dp, 104.dp),
                contentAlignment = Alignment.Center
            ) {
                listOf(
                    Triple((-84).dp, 26.dp, 5.dp),
                    Triple((-58).dp, 62.dp, 3.dp),
                    Triple((54).dp, 60.dp, 3.dp),
                    Triple((-28).dp, 92.dp, 4.dp),
                    Triple((82).dp, 18.dp, 3.dp)
                ).forEach { (x, y, s) ->
                    Box(
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .size(s)
                            .clip(CircleShape)
                            .background(Color(0xFF74E6D8).copy(alpha = 0.22f))
                    )
                }

                Box(
                    modifier = Modifier
                        .offset(y = (-6).dp)
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF74E6D8).copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                HomeSonarRing(progress = ring1)
                HomeSonarRing(progress = ring2)
                HomeSonarRing(progress = ring3)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF74E6D8))
                        .shadow(10.dp, CircleShape)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFF74E6D8).copy(alpha = 0.30f),
                            Color.Transparent
                        )
                    )
                )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Scanning for activity",
            color = Color(0xFFEEF2F1),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "Transactions appear here after automation starts",
            color = Color(0xFF5B6366),
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HomeSonarRing(progress: Float) {
    val minSize = 24.dp
    val maxSize = 140.dp
    val minScale = minSize.value / maxSize.value
    val scale = minScale + ((1f - minScale) * progress)
    Box(
        modifier = Modifier
            .size(maxSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .border(
                width = if (progress < 0.08f) 2.dp else 1.dp,
                color = Color(0xFF74E6D8).copy(alpha = (0.72f - (progress * 0.72f)).coerceAtLeast(0f)),
                shape = CircleShape
            )
    )
}

@Composable
private fun HomeDispatchRow(
    tx: Transaction,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = transactionStatusColor(tx)
    val titleColor = Color(0xFFF2F6F7)
    val phoneColor = Color(0xFF8B979B)
    val offerColor = Color(0xFF9FD8FF)
    val timeColor = Color(0xFFC9D4DB)
    val dividerColor = Color(0xFF11181B)
    val metaDotColor = Color(0xFF647279)
    val title = tx.clientName.ifBlank { tx.description.ifBlank { "Recent automation" } }
    val phone = tx.phoneNumber.ifBlank { "Phone not available" }
    val serviceLabel = tx.description.ifBlank { "Offer not captured" }
    val avatarLabel = recentActivityInitials(title)
    val amountLabel = recentActivityAmountLabel(tx.amount)
    val timeLabel = recentActivityTimeLabel(tx)
    val relativeLabel = recentActivityRelativeLabel(tx)
    val serviceIcon = recentActivityServiceIcon(serviceLabel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(statusColor.copy(alpha = 0.82f))
        )
        Surface(
            color = Color(0xFF090B0C),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF152024).copy(alpha = 0.62f)),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0D1113))
                                .border(1.dp, statusColor.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                avatarLabel,
                                color = Color(0xFFB8C0C3),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                title,
                                color = titleColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                phone,
                                color = phoneColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            amountLabel,
                            color = Color(0xFFE8ECEE),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            transactionStatusLabel(tx),
                            color = statusColor.copy(alpha = 0.96f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(dividerColor)
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                serviceIcon,
                                null,
                                tint = offerColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                serviceLabel,
                                color = offerColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Schedule,
                                null,
                                tint = timeColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                timeLabel,
                                color = timeColor,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            if (relativeLabel.isNotBlank()) {
                                Text(
                                    "•",
                                    color = metaDotColor,
                                    fontSize = 11.sp
                                )
                                Text(
                                    relativeLabel,
                                    color = statusColor.copy(alpha = 0.96f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            null,
                            tint = Color(0xFF667074),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun recentActivityInitials(title: String): String {
    val parts = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        parts.isNotEmpty() -> parts[0].take(2).uppercase(Locale.getDefault())
        else -> "TX"
    }
}

private fun recentActivityAmountLabel(amount: String): String {
    val normalized = amount.trim().removePrefix("+").removePrefix("-").trim()
    if (normalized.isBlank()) return "KSh -"
    return when {
        normalized.startsWith("ksh", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.startsWith("kes", ignoreCase = true) -> "KSh " + normalized.substring(3).trim()
        normalized.firstOrNull()?.isDigit() == true -> "KSh $normalized"
        else -> normalized
    }
}

private fun recentActivityTimeLabel(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return tx.date.ifBlank { "--:--" }
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
}

private fun recentActivityRelativeLabel(tx: Transaction): String {
    val timestamp = transactionTimestamp(tx)
    if (timestamp <= 0L) return ""
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = delta / 60000L
    val hours = delta / 3600000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> ""
    }
}

private fun recentActivityServiceIcon(serviceLabel: String): ImageVector =
    if (serviceLabel.contains("sms", ignoreCase = true) || serviceLabel.contains("message", ignoreCase = true)) {
        Icons.Outlined.Sms
    } else {
        Icons.Outlined.PhoneAndroid
    }

@Composable
private fun HomeDashboardHeader(running: Boolean) {
    val statusColor = if (running) C.green else C.red
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val headerMaxWidth = maxWidth
        val compact = headerMaxWidth < 380.dp
        val titleSize = if (compact) 30.sp else 40.sp
        val titleLineHeight = if (compact) 34.sp else 42.sp
        val subtitleSize = if (compact) 14.sp else 16.sp
        val subtitleLineHeight = if (compact) 18.sp else 20.sp
        val subtitleMaxWidth = if (headerMaxWidth > 520.dp) 360.dp else headerMaxWidth
        val badgeAnim = rememberInfiniteTransition(label = "header_badge")
        val shimmerProgress by badgeAnim.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "header_badge_shimmer"
        )
        val breathe by badgeAnim.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "header_badge_breathe"
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Bingwa Mobile",
                    color = C.t1,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Black,
                    lineHeight = titleLineHeight,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Text(
                    "USSD Automation Platform",
                    modifier = Modifier.widthIn(max = subtitleMaxWidth),
                    color = C.t2,
                    fontSize = subtitleSize,
                    lineHeight = subtitleLineHeight,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = C.surface.copy(alpha = 0.54f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.34f)),
                modifier = Modifier
                    .drawBehind {
                        val shimmerWidth = size.width * 0.34f
                        val startX = (size.width * shimmerProgress) - shimmerWidth
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.05f),
                                    statusColor.copy(alpha = 0.16f),
                                    Color.Transparent
                                ),
                                startX = startX,
                                endX = startX + shimmerWidth
                            ),
                            cornerRadius = CornerRadius(size.height, size.height)
                        )
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    scaleX = if (running) breathe else 1f
                                    scaleY = if (running) breathe else 1f
                                }
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = 0.18f))
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                    }
                    Text(
                        if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE",
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.1.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            RelayStatusPill()
        }
    }
}

@Composable
private fun RecentActivityHeader(automatedCount: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val compact = maxWidth < 400.dp
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(if (compact) 26.dp else 30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(C.orange, C.blue.copy(alpha = 0.92f))
                            )
                        )
                )
                Text(
                    "Recent Activity",
                    modifier = Modifier.weight(1f),
                    color = C.t1,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 18.sp else 20.sp
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = C.surface.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.84f))
                ) {
                    Text(
                        text = if (automatedCount == 0) "0 automated" else "$automatedCount automated",
                        color = C.t2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            RecentActivityMotionRail(compact = compact)
        }
    }
}

@Composable
private fun RecentActivityMotionRail(compact: Boolean) {
    val railAnim = rememberInfiniteTransition(label = "recent_activity_rail")
    val beaconPulse by railAnim.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "recent_activity_rail_beacon_pulse"
    )
    val beaconAlpha by railAnim.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "recent_activity_rail_beacon_alpha"
    )
    val railHeight = if (compact) 8.dp else 10.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(railHeight)
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        C.surface.copy(alpha = 0.82f),
                        C.cardHi.copy(alpha = 0.76f),
                        C.surface.copy(alpha = 0.82f)
                    )
                )
            )
            .border(1.dp, C.border.copy(alpha = 0.48f), RoundedCornerShape(999.dp))
            .drawBehind {
                val centerY = size.height / 2f
                val startX = size.width * 0.08f
                val endX = size.width * 0.92f
                val centerX = size.width / 2f

                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            C.green.copy(alpha = 0.20f),
                            C.blue.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        startX = startX,
                        endX = endX
                    ),
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = size.height * 0.28f
                )

                drawCircle(
                    color = C.green,
                    radius = (size.height * 0.24f) * beaconPulse,
                    center = Offset(centerX, centerY)
                )
                drawCircle(
                    color = C.green.copy(alpha = beaconAlpha),
                    radius = (size.height * 0.62f) * beaconPulse,
                    center = Offset(centerX, centerY)
                )
            }
    )
}

@Composable
private fun GithubOverviewCard(
    airBal: String,
    tokenBal: Int,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    sent: Int,
    pending: Int,
    failed: Int,
    rate: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    spin: Float
) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                GithubMetricTile(
                    title = "Airtime balance",
                    value = airBal.ifBlank { "Ksh 0.00" },
                    caption = "Current balance",
                    accent = C.blue,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                GithubMetricTile(
                    title = if (unlimitedLabel != null) "Unlimited plan" else "Tokens",
                    value = unlimitedLabel ?: tokenBal.toString(),
                    caption = unlimitedRemaining ?: "Available tokens",
                    accent = if (unlimitedLabel != null) C.green else C.amber,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, C.border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = C.surface,
                    contentColor = C.t1
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    null,
                    modifier = Modifier
                        .size(16.dp)
                        .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isRefreshing) "Refreshing" else "Refresh", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Divider(color = C.w08)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GithubStatPill("Sent", sent.toString(), C.green, Modifier.weight(1f))
                GithubStatPill("Pending", pending.toString(), C.amber, Modifier.weight(1f))
                GithubStatPill("Failed", failed.toString(), C.red, Modifier.weight(1f))
                GithubStatPill("Rate", "$rate%", if (rate >= 70) C.green else C.amber, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GithubMetricTile(
    title: String,
    value: String,
    caption: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val valueFontSize = balanceValueFontSize(value, 19.sp, 17.sp, 15.sp, 13.sp)
    val captionFontSize = balanceCaptionFontSize(caption, 11.sp, 10.sp, 9.sp)
    Surface(
        color = C.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Text(title, color = C.t2, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                value,
                color = C.t1,
                fontSize = valueFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            Text(
                caption,
                color = C.t3,
                fontSize = captionFontSize,
                lineHeight = captionFontSize * 1.1f,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun GithubStatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = C.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GithubEmptyActivityCard() {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(C.greenDim)
                    .border(1.dp, C.green.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.History, null, tint = C.green, modifier = Modifier.size(22.dp))
            }
            Text(
                "Scanning for activity",
                color = C.t1,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Transactions appear here after you start automation.",
                color = C.t2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class TransactionRetryResult(
    val success: Boolean,
    val message: String,
    val newTxId: Int = -1
)

private fun transactionStatusColor(tx: Transaction): Color = when (tx.statusEnum) {
    TransactionStatus.SUCCESS -> C.green
    TransactionStatus.FAILED, TransactionStatus.CANCELLED -> C.red
    TransactionStatus.PROCESSING, TransactionStatus.PENDING, TransactionStatus.RETRYING -> C.amber
}

private fun transactionTypeLabel(tx: Transaction): String = when (tx.source) {
    TX_SOURCE_AUTOMATED -> "Automated"
    TX_SOURCE_MANUAL -> "Manual"
    TX_SOURCE_SMS_COMMAND -> "SMS Command"
    TX_SOURCE_AIRTIME -> "Airtime"
    else -> "Activity"
}

private fun transactionTypeColor(tx: Transaction): Color = when (tx.source) {
    TX_SOURCE_AUTOMATED -> C.green
    TX_SOURCE_MANUAL -> C.purple
    TX_SOURCE_SMS_COMMAND -> C.blue
    TX_SOURCE_AIRTIME -> C.orange
    else -> C.cyan
}

private fun transactionSummaryTime(tx: Transaction): String =
    if (tx.timestamp > 0L) SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    else tx.date.ifBlank { "Recent" }

private fun transactionExecutionTime(tx: Transaction): String =
    if (tx.timestamp > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
    else tx.date.ifBlank { "Not recorded" }

private fun transactionCompletionTime(tx: Transaction): String =
    if (tx.completedAt > 0L) SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(tx.completedAt))
    else when (tx.statusEnum) {
        TransactionStatus.SUCCESS,
        TransactionStatus.FAILED,
        TransactionStatus.CANCELLED -> "Completed time not recorded"
        else -> "In progress"
    }

private fun transactionExecutionDuration(tx: Transaction): String {
    val durationMs = when {
        tx.executionDurationMs > 0L -> tx.executionDurationMs
        tx.completedAt > 0L && tx.timestamp > 0L -> (tx.completedAt - tx.timestamp).coerceAtLeast(0L)
        else -> 0L
    }
    if (durationMs <= 0L) {
        return when (tx.statusEnum) {
            TransactionStatus.SUCCESS,
            TransactionStatus.FAILED,
            TransactionStatus.CANCELLED -> "Not recorded"
            else -> "Still running"
        }
    }
    return formatExecutionMs(durationMs).ifBlank { "${durationMs}ms" }
}

private fun resolveOfferForRetry(context: Context, tx: Transaction): OfferItem? {
    if (tx.offerId >= 0) {
        OfferRepository.findById(context, tx.offerId)?.let { return it }
    }
    OfferRepository.findByName(context, tx.description)?.let { return it }
    if (tx.amountValue > 0.0) {
        OfferRepository.findByPrice(context, tx.amountValue.toInt())?.let { return it }
    }
    val normalizedCode = UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
    return OfferRepository.load(context).firstOrNull { offer ->
        offer.enabled && UssdHelper.normalizeUssdCode(offer.ussdCode, tx.phoneNumber) == normalizedCode
    }
}

private fun retryRecentTransaction(context: Context, tx: Transaction): TransactionRetryResult {
    val phone = SmsCommandHandler.normalizePhone(tx.phoneNumber).ifBlank { tx.phoneNumber.trim() }
    if (phone.isBlank()) {
        return TransactionRetryResult(false, "Phone number is missing for this transaction.")
    }

    val matchedOffer = resolveOfferForRetry(context, tx)
    val finalCode = when {
        matchedOffer != null -> UssdHelper.normalizeUssdCode(matchedOffer.ussdCode, phone)
        tx.ussdCode.isNotBlank() -> UssdHelper.normalizeUssdCode(tx.ussdCode, phone)
        else -> ""
    }
    if (finalCode.isBlank()) {
        return TransactionRetryResult(false, "USSD code is missing, so this transaction cannot be retried.")
    }

    val newTxId = createPendingTransaction(
        context,
        description = matchedOffer?.name ?: tx.description,
        amount = tx.amount,
        phone = phone,
        ussd = finalCode,
        clientName = tx.clientName,
        status = TransactionStatus.PROCESSING.value,
        source = tx.source,
        showInRecent = true,
        offerId = matchedOffer?.id ?: tx.offerId
    )
    if (newTxId < 0) {
        return TransactionRetryResult(false, "Retry could not be queued. Please try again.")
    }

    context.startOfferAutomation(
        offer = matchedOffer,
        phoneNumber = phone,
        txId = newTxId,
        finalCode = finalCode,
        mode = matchedOffer?.executionMode ?: "ADVANCED"
    )
    return TransactionRetryResult(
        success = true,
        message = "Retry started for ${matchedOffer?.name ?: tx.description.ifBlank { "this transaction" }}.",
        newTxId = newTxId
    )
}

@Composable
private fun GithubActivityCard(
    tx: Transaction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = transactionStatusColor(tx)
    val typeColor = transactionTypeColor(tx)
    val showLiveAnimation = tx.statusEnum == TransactionStatus.PENDING ||
        tx.statusEnum == TransactionStatus.PROCESSING ||
        tx.statusEnum == TransactionStatus.RETRYING
    val initials = remember(tx.clientName, tx.phoneNumber) {
        tx.clientName
            .ifBlank { tx.phoneNumber }
            .split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
            .ifBlank { "TX" }
    }
    val responsePreview = tx.ussdResponse
        .ifBlank { tx.ussdTranscript.lineSequence().firstOrNull().orEmpty() }
        .ifBlank { if (tx.statusEnum == TransactionStatus.FAILED) "Tap to view full failure details." else "" }
    val cardAnim = rememberInfiniteTransition(label = "recent_activity_card")
    val shimmer by cardAnim.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "recent_activity_shimmer"
    )
    val avatarPulse by cardAnim.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(if (showLiveAnimation) 1800 else 2800, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "recent_activity_pulse"
    )

    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .drawBehind {
                    val shimmerWidth = size.width * 0.30f
                    val startX = (size.width * shimmer) - shimmerWidth
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                statusColor.copy(alpha = if (showLiveAnimation) 0.08f else 0.04f),
                                Color.Transparent
                            ),
                            startX = startX,
                            endX = startX + shimmerWidth
                        ),
                        cornerRadius = CornerRadius(30f, 30f)
                    )
                }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            statusColor.copy(alpha = 0.05f),
                            C.card,
                            C.surface.copy(alpha = 0.78f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = avatarPulse
                                scaleY = avatarPulse
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(statusColor.copy(alpha = 0.12f))
                            .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                    )
                    Text(initials, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        tx.clientName.ifBlank { "Unknown customer" },
                        color = C.t1,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                    Text(
                        "Bought ${tx.description.ifBlank { "Offer not captured" }}",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.width(86.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        tx.amount.ifBlank { "-" },
                        color = C.t1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = statusColor.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.28f))
                    ) {
                        Text(
                            tx.status,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecentSummaryChip(
                    text = tx.phoneNumber.ifBlank { "Phone unavailable" },
                    color = C.cyan,
                    modifier = Modifier.weight(1f)
                )
                RecentSummaryChip(
                    text = transactionSummaryTime(tx),
                    color = typeColor,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecentSummaryChip(text = transactionTypeLabel(tx), color = typeColor)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showLiveAnimation) "Execution in progress" else "Tap for full execution details",
                    color = C.t3,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (showLiveAnimation) {
                    Icon(Icons.Outlined.Sync, null, tint = statusColor, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.KeyboardArrowRight, null, tint = C.t3, modifier = Modifier.size(18.dp))
                }
            }
            if (responsePreview.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = C.surface.copy(alpha = 0.80f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
                ) {
                    Text(
                        responsePreview,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        color = C.t2,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Delete", color = C.red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun RecentSummaryChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RecentTransactionDetailsDialog(
    tx: Transaction,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (Transaction) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val statusColor = transactionStatusColor(tx)
    val sourceColor = transactionTypeColor(tx)
    val transcriptText = tx.ussdTranscript.ifBlank {
        tx.ussdResponse.ifBlank { "No USSD transcript captured for this transaction." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.card,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tx.clientName.ifBlank { "Transaction details" },
                    color = C.t1,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    tx.description.ifBlank { "Offer details" },
                    color = C.t2,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecentSummaryChip(text = tx.status, color = statusColor)
                    RecentSummaryChip(text = transactionTypeLabel(tx), color = sourceColor)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(tx.phoneNumber))
                            Toast.makeText(context, "Phone number copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.phoneNumber.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Phone")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(tx.ussdCode))
                            Toast.makeText(context, "USSD code copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.ussdCode.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.Dialpad, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy USSD")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(transcriptText))
                            Toast.makeText(context, "USSD transcript copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = tx.ussdTranscript.isNotBlank() || tx.ussdResponse.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Icon(Icons.Outlined.Article, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Transcript")
                    }
                }
                if (tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED) {
                    Button(
                        onClick = { onRetry(tx) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = statusColor)
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Retry Failed Execution")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.surface.copy(alpha = 0.84f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("EXECUTION OVERVIEW", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        TxDetailRow("Transaction ID", "#${tx.id}")
                        TxDetailRow("Customer name", tx.clientName.ifBlank { "Not captured" })
                        TxDetailRow("Phone number", tx.phoneNumber.ifBlank { "Not captured" })
                        TxDetailRow("Offer bought", tx.description.ifBlank { "Not captured" })
                        TxDetailRow("Amount", tx.amount.ifBlank { "Not captured" })
                        TxDetailRow("Time of execution", transactionExecutionTime(tx))
                        TxDetailRow("Execution completed", transactionCompletionTime(tx))
                        TxDetailRow("Time taken to execute", transactionExecutionDuration(tx))
                        TxDetailRow("Source", transactionTypeLabel(tx))
                        TxDetailRow("USSD code", tx.ussdCode.ifBlank { "Not captured" })
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("LAST RESPONSE", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        SelectionContainer {
                            Text(
                                tx.ussdResponse.ifBlank { "No final response captured yet." },
                                color = C.t2,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = C.w04,
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("USSD SESSION", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                        SelectionContainer {
                            Text(
                                transcriptText,
                                color = C.t2,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = C.t1)
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = C.red)
            }
        }
    )
}

@Composable
private fun StartAutomationCTA(running: Boolean, onToggle: () -> Unit) {
    val accent = if (running) C.red else C.blue
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) C.red else C.t3))
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "Automation Running" else "Automation Paused",
                color = if (running) C.red else C.t2,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.55f))
        ) {
            Icon(Icons.Outlined.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(if (running) "STOP AUTOMATION" else "START AUTOMATION", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 1.1.sp)
        }
    }
}

// ─── Balance Card ─────────────────────────────────────────────────────────
@Composable
fun VolcanicBalanceCard(
    airBal: String,
    tokenBal: Int,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    sent: Int,
    pending: Int,
    failed: Int,
    completed: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    spin: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF4B3729).copy(alpha = 0.94f),
                        Color(0xFF2A242B).copy(alpha = 0.98f),
                        Color(0xFF131928).copy(alpha = 0.96f)
                    ),
                    start = Offset.Zero
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
            .animateContentSize(animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing))
            .clickable(onClick = onRefresh)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        val compactTop = maxWidth < 380.dp
        val statSpacing = if (maxWidth < 430.dp) 6.dp else 8.dp
        val headingAccent = Color(0xFFF5AF19)
        val sentAccent = Color(0xFF1ED89A)
        val pendingAccent = Color(0xFFF5AF19)
        val failedAccent = Color(0xFFFF496A)
        val completedAccent = Color(0xFFB79BFF)
        val airtimeValue = airBal.ifBlank { "—" }
        val topTokenValue = unlimitedLabel ?: tokenBal.toString()
        val topTokenCaption = unlimitedRemaining ?: if (unlimitedLabel != null) "Unlimited plan active" else "Available Units"
        val airtimeFontSize = balanceValueFontSize(
            airtimeValue,
            if (compactTop) 34.sp else 40.sp,
            if (compactTop) 30.sp else 34.sp,
            if (compactTop) 26.sp else 30.sp,
            if (compactTop) 22.sp else 26.sp
        )
        val topTokenValueFontSize = balanceValueFontSize(
            topTokenValue,
            if (compactTop) 42.sp else 52.sp,
            if (compactTop) 34.sp else 42.sp,
            if (compactTop) 28.sp else 34.sp,
            if (compactTop) 24.sp else 28.sp
        )
        val topTokenCaptionFontSize = balanceCaptionFontSize(
            topTokenCaption,
            if (compactTop) 10.sp else 11.sp,
            if (compactTop) 9.sp else 10.sp,
            if (compactTop) 8.sp else 9.sp
        )

        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(
                                Color(0x66A46A2B),
                                Color.Transparent,
                                Color(0x2200A5A5),
                                Color(0x33152440)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    )
                    drawCircle(
                        color = headingAccent.copy(alpha = 0.08f),
                        radius = 88.dp.toPx(),
                        center = Offset(size.width * 0.12f, size.height * 0.18f)
                    )
                    drawCircle(
                        color = headingAccent.copy(alpha = 0.06f),
                        radius = 94.dp.toPx(),
                        center = Offset(size.width * 0.76f, size.height * 0.16f)
                    )
                    drawCircle(
                        color = sentAccent,
                        radius = 5.dp.toPx(),
                        center = Offset(size.width * 0.93f, size.height * 0.29f)
                    )
                    drawLine(
                        color = sentAccent.copy(alpha = 0.24f),
                        start = Offset(size.width * 0.94f, size.height * 0.34f),
                        end = Offset(size.width * 0.985f, size.height * 0.27f),
                        strokeWidth = 5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val path = Path().apply {
                        moveTo(size.width * 0.04f, size.height * 0.55f)
                        cubicTo(
                            size.width * 0.20f, size.height * 0.47f,
                            size.width * 0.31f, size.height * 0.70f,
                            size.width * 0.46f, size.height * 0.61f
                        )
                        cubicTo(
                            size.width * 0.56f, size.height * 0.47f,
                            size.width * 0.76f, size.height * 0.63f,
                            size.width * 0.96f, size.height * 0.31f
                        )
                    }
                    drawPath(
                        path = path,
                        brush = Brush.linearGradient(
                            listOf(
                                headingAccent.copy(alpha = 0.18f),
                                Color(0xB8C8A62E),
                                sentAccent.copy(alpha = 0.42f)
                            )
                        ),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricHeadingLabel("AIRTIME BALANCE", accent = headingAccent)
                MetricHeadingLabel("TOKENS", accent = headingAccent, trailingDot = true)
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1.dp)
                        .height(if (compactTop) 122.dp else 132.dp)
                        .background(Color.White.copy(alpha = 0.74f))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (compactTop) 4.dp else 8.dp,
                                end = if (compactTop) 22.dp else 28.dp,
                                top = if (compactTop) 6.dp else 10.dp,
                                bottom = if (compactTop) 8.dp else 12.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = airtimeValue,
                                color = C.t1,
                                fontSize = airtimeFontSize,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                            Icon(
                                Icons.Outlined.Autorenew,
                                null,
                                tint = C.t3,
                                modifier = Modifier
                                    .size(if (compactTop) 24.dp else 28.dp)
                                    .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
                            )
                        }
                        Text(
                            if (isRefreshing) "checking balance..." else "tap card to refresh",
                            color = C.t3,
                            fontSize = if (compactTop) 10.sp else 11.sp,
                            lineHeight = if (compactTop) 13.sp else 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                start = if (compactTop) 22.dp else 28.dp,
                                end = if (compactTop) 6.dp else 10.dp,
                                top = if (compactTop) 6.dp else 10.dp,
                                bottom = if (compactTop) 8.dp else 12.dp
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnimatedContent(
                            targetState = topTokenValue,
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 5 }) togetherWith
                                    (fadeOut(tween(180)) + slideOutVertically(animationSpec = tween(180)) { -it / 5 })
                            },
                            label = "token_balance_value"
                        ) { tokenValue ->
                            Text(
                                tokenValue,
                                color = if (unlimitedLabel != null) sentAccent else C.t1,
                                fontSize = topTokenValueFontSize,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        }
                        Text(
                            topTokenCaption,
                            color = C.t2,
                            fontSize = topTokenCaptionFontSize,
                            lineHeight = topTokenCaptionFontSize * 1.1f,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.78f))
            )
            HomeStatsRow(
                sent = sent,
                pending = pending,
                failed = failed,
                completed = completed,
                compact = true,
                spacing = statSpacing,
                sentAccent = sentAccent,
                pendingAccent = pendingAccent,
                failedAccent = failedAccent,
                completedAccent = completedAccent
            )
        }
    }
}

fun balanceValueFontSize(
    value: String,
    short: TextUnit,
    medium: TextUnit,
    long: TextUnit,
    extraLong: TextUnit
): TextUnit {
    val length = value.trim().length
    return when {
        length <= 8 -> short
        length <= 12 -> medium
        length <= 16 -> long
        else -> extraLong
    }
}

fun balanceCaptionFontSize(
    value: String,
    short: TextUnit,
    medium: TextUnit,
    long: TextUnit
): TextUnit {
    val length = value.trim().length
    return when {
        length <= 20 -> short
        length <= 30 -> medium
        else -> long
    }
}

@Composable
private fun HomeStatsRow(
    sent: Int,
    pending: Int,
    failed: Int,
    completed: Int,
    compact: Boolean,
    spacing: Dp,
    sentAccent: Color,
    pendingAccent: Color,
    failedAccent: Color,
    completedAccent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rate = if (sent > 0) ((completed * 100f) / sent.toFloat()).toInt() else 0
        HomeStatusMetricCard(
            label = "Completed",
            value = completed.toString(),
            accent = sentAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Pending",
            value = pending.toString(),
            accent = pendingAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        HomeStatusMetricCard(
            label = "Failed",
            value = failed.toString(),
            accent = failedAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
        RateStatCard(
            rate = rate,
            accent = completedAccent,
            compact = compact,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HomeStatusMetricCard(
    label: String,
    value: String,
    accent: Color,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0x161D2433),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .height(if (compact) 74.dp else 82.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                value,
                fontSize = if (compact) 18.sp else 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label.uppercase(Locale.getDefault()),
                color = C.t2,
                fontSize = if (compact) 7.sp else 8.sp,
                letterSpacing = if (compact) 0.5.sp else 0.8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RateStatCard(rate: Int, accent: Color, compact: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0x161D2433),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .height(if (compact) 96.dp else 104.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "$rate%",
                color = accent,
                fontSize = if (compact) 24.sp else 28.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "RATE",
                color = C.t2,
                fontSize = if (compact) 8.sp else 9.sp,
                letterSpacing = 1.1.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MetricHeadingLabel(text: String, accent: Color, trailingDot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = C.t1,
            fontSize = if (text.length > 8) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.9.sp
        )
        if (trailingDot) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@Composable
private fun HomeMetricBlock(
    title: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
    valueColor: Color = C.t1,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Text(title, color = C.t2, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
            }
            trailing?.invoke()
        }
        Text(
            value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 32.sp,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            detail,
            color = C.t3,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RefreshGlassButton(
    isRefreshing: Boolean,
    spin: Float,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .shadow(18.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.08f),
                        C.surface.copy(alpha = 0.92f)
                    )
                )
            )
            .border(1.dp, C.borderHi.copy(alpha = 0.95f), CircleShape)
            .clickable { onRefresh() }
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            C.amber.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Refresh,
                null,
                tint = if (isRefreshing) C.amber else C.t1,
                modifier = Modifier
                    .size(18.dp)
                    .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
            )
        }
    }
}

@Composable
fun StatCell(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = C.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
            Text(label, fontSize = 9.sp, color = C.t3, letterSpacing = 0.8.sp, textAlign = TextAlign.Center, lineHeight = 11.sp)
        }
    }
}

@Composable
fun DonutRing(pct: Int, color: Color, size: Dp, stroke: Dp) {
    val sweep = pct * 360f / 100f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = stroke.toPx()
            val r = (size.toPx() - st) / 2f
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            drawCircle(C.w08, radius = r, center = center, style = Stroke(width = st))
            drawArc(color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2f, r * 2f), style = Stroke(width = st, cap = StrokeCap.Round))
        }
        Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
fun VolcanicTxCard(tx: Transaction, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (tx.status) {
        TransactionStatus.SUCCESS.value -> C.green
        TransactionStatus.FAILED.value, TransactionStatus.CANCELLED.value -> C.red
        TransactionStatus.PROCESSING.value, TransactionStatus.PENDING.value, TransactionStatus.RETRYING.value -> C.amber
        else -> C.t2
    }
    val typeLabel = when (tx.source) {
        TX_SOURCE_AUTOMATED -> "Automated"
        TX_SOURCE_MANUAL -> "Manual"
        TX_SOURCE_SMS_COMMAND -> "SMS Command"
        TX_SOURCE_AIRTIME -> "Airtime"
        else -> "Activity"
    }
    val typeColor = when (typeLabel) {
        "Automated" -> C.green
        "Manual" -> C.purple
        "SMS Command" -> C.blue
        "Airtime" -> C.orange
        else -> C.blue
    }
    val processingAnim = rememberInfiniteTransition(label = "processing")
    val processingAlpha by processingAnim.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "processing_alpha"
    )
    val initials = (tx.clientName.ifBlank { tx.phoneNumber }).split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val transcriptText = tx.ussdTranscript.ifBlank {
        tx.ussdResponse.ifBlank {
            "Response not captured yet. This transaction is queued for ${tx.description.ifBlank { "the selected activity" }}."
        }
    }
    val transcriptCards = remember(tx.ussdTranscript, transcriptText) {
        val source = tx.ussdTranscript.takeIf { it.isNotBlank() } ?: transcriptText
        source
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.card)
            .border(
                1.dp,
                when {
                    tx.status == TransactionStatus.PROCESSING.value -> statusColor.copy(alpha = 0.22f + (processingAlpha * 0.22f))
                    expanded -> statusColor.copy(alpha = 0.32f)
                    else -> C.border
                },
                RoundedCornerShape(18.dp)
            )
            .clickable { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(statusColor.copy(alpha = 0.10f))
                    .border(1.dp, statusColor.copy(alpha = 0.26f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { Text(initials.ifBlank { "?" }, color = statusColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
            Column(Modifier.weight(1f)) {
                Text(tx.clientName.ifBlank { "Unknown Customer" }, color = C.t1, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tx.phoneNumber.ifBlank { "Phone not available" }, color = C.t2, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(tx.amount, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = if (tx.amount.startsWith("-")) C.orange else C.t1)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = if (tx.status == TransactionStatus.PROCESSING.value) 0.10f + (processingAlpha * 0.08f) else 0.10f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = if (tx.status == TransactionStatus.PROCESSING.value) 0.16f + (processingAlpha * 0.20f) else 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (tx.status == TransactionStatus.PROCESSING.value) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor.copy(alpha = processingAlpha)))
                        }
                        Text(tx.status, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = C.cyanDim, border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.16f))) {
                    Text(tx.description.ifBlank { "Transaction" }, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = C.cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = typeColor.copy(alpha = 0.12f), border = BorderStroke(1.dp, typeColor.copy(alpha = 0.20f))) {
                    Text(typeLabel, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = typeColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(tx.date, color = C.t3, fontSize = 10.sp, textAlign = TextAlign.End)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.w04)
                    .border(1.dp, C.border, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("TRANSACTION DETAILS", color = C.t3, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                TxDetailRow("Transaction ID", "#${tx.id}")
                TxDetailRow("Customer", tx.clientName.ifBlank { "Not captured" })
                TxDetailRow("Phone", tx.phoneNumber.ifBlank { "Not captured" })
                TxDetailRow("Bundle / Activity", tx.description.ifBlank { "Not captured" })
                TxDetailRow("Amount", tx.amount.ifBlank { "Not captured" })
                TxDetailRow("Status", tx.status)
                TxDetailRow("Source", tx.source.replace('_', ' '))
                TxDetailRow("Date", tx.date.ifBlank { "Not captured" })
                TxDetailRow("USSD Code", tx.ussdCode.ifBlank { "Not captured" })
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LAST USSD RESPONSE", color = C.t3, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                    Text(
                        tx.ussdResponse.ifBlank { "Response not captured yet. This transaction is queued for ${tx.description.ifBlank { "the selected activity" }}." },
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("USSD SESSION TRANSCRIPT", color = C.t3, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(transcriptText))
                                Toast.makeText(context, "USSD transcript copied", Toast.LENGTH_SHORT).show()
                            },
                            enabled = tx.ussdTranscript.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy transcript",
                                tint = if (tx.ussdTranscript.isNotBlank()) C.cyan else C.t3,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Copy", color = if (tx.ussdTranscript.isNotBlank()) C.cyan else C.t3, fontSize = 12.sp)
                        }
                    }
                    transcriptCards.forEachIndexed { index, cardText ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = C.surface.copy(alpha = 0.72f),
                            border = BorderStroke(1.dp, C.border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "POP UP ${index + 1}",
                                    color = C.cyan,
                                    fontSize = 9.sp,
                                    letterSpacing = 1.2.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    cardText,
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
                    Text("Delete", color = C.red, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TxDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = C.t3, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Text(value, color = C.t1, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

// ─── Animated Empty State ─────────────────────────────────────────────────
@Composable
fun AnimatedEmptyState(modifier: Modifier = Modifier) {
    val anim = rememberInfiniteTransition(label = "empty_anim")
    val corePulse by anim.animateFloat(0.96f, 1.05f, infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), label = "core_pulse")
    val ringAlpha by anim.animateFloat(0.10f, 0.22f, infiniteRepeatable(tween(2600, easing = EaseInOutSine), RepeatMode.Reverse), label = "ring_alpha")
    val dotBlink by anim.animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "dot_blink")
    val illustrationHeight = if (LocalConfiguration.current.screenWidthDp < 400) 162.dp else 188.dp

    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        C.cardHi.copy(alpha = 0.98f),
                        C.card.copy(alpha = 0.96f),
                        Color(0xFF131B27).copy(alpha = 0.96f)
                    )
                )
            )
            .border(1.dp, C.border.copy(alpha = 0.90f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(illustrationHeight)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                C.cardHi.copy(alpha = 0.74f),
                                Color(0xFF172233).copy(alpha = 0.88f),
                                Color(0xFF111827).copy(alpha = 0.95f)
                            )
                        )
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                Canvas(
                    Modifier
                        .matchParentSize()
                        .padding(top = 8.dp)
                ) {
                    val center = Offset(size.width * 0.50f, size.height * 0.34f)
                    val lineY = size.height * 0.68f
                    val maxRadius = size.minDimension * 0.20f

                    listOf(0.34f, 0.56f, 0.76f, 0.96f).forEachIndexed { idx, factor ->
                        drawCircle(
                            color = C.green.copy(alpha = (ringAlpha - (idx * 0.028f)).coerceAtLeast(0.04f)),
                            radius = maxRadius * factor,
                            center = center,
                            style = Stroke(width = 1.2.dp.toPx())
                        )
                    }

                    val dots = listOf(
                        Offset(size.width * 0.10f, size.height * 0.30f),
                        Offset(size.width * 0.14f, size.height * 0.56f),
                        Offset(size.width * 0.24f, size.height * 0.42f),
                        Offset(size.width * 0.28f, size.height * 0.24f)
                    )
                    dots.forEachIndexed { idx, dot ->
                        drawCircle(
                            C.green.copy(alpha = (0.16f + (dotBlink * 0.14f) - idx * 0.02f).coerceAtLeast(0.08f)),
                            radius = (2.4f + idx).dp.toPx() / 1.7f,
                            center = dot
                        )
                    }

                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, C.green.copy(alpha = 0.30f), Color.Transparent)
                        ),
                        start = Offset(size.width * 0.14f, lineY),
                        end = Offset(size.width * 0.86f, lineY),
                        strokeWidth = 1.1.dp.toPx()
                    )
                    drawCircle(C.green.copy(alpha = 0.12f), radius = 14.dp.toPx() * corePulse, center = center)
                    drawCircle(C.green.copy(alpha = 0.88f), radius = 5.dp.toPx(), center = center)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Scanning for activity...",
                    color = C.t1,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Transactions appear here after automation starts",
                    color = C.t2,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SubtleRefreshGlyph(
    isRefreshing: Boolean,
    spin: Float,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .clickable(onClick = onRefresh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Refresh,
            null,
            tint = if (isRefreshing) C.t2.copy(alpha = 0.54f) else C.t3.copy(alpha = 0.28f),
            modifier = Modifier
                .size(14.dp)
                .then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier)
        )
    }
}

// ─── Manual Screen ───────────────────────────────────────────────────────
@Composable
fun ManualScreen(allTxns: MutableList<Transaction>) {
    val ctx = LocalContext.current
    var offers by remember { mutableStateOf(OfferRepository.load(ctx).toList()) }
    var phone by remember { mutableStateOf("") }
    var phoneErr by remember { mutableStateOf<String?>(null) }
    var selOffer by remember { mutableStateOf(offers.firstOrNull { it.enabled }) }
    var mode by remember { mutableStateOf("ADVANCED") }
    var manualTab by rememberSaveable { mutableStateOf("DISPATCH") }
    var offerExp by remember { mutableStateOf(false) }
    var bannerState by remember { mutableStateOf<String?>(null) }
    var pendingTxId by remember { mutableIntStateOf(-1) }
    var selectedHistoryTxId by rememberSaveable { mutableIntStateOf(-1) }
    var smsSearchContacts by remember { mutableStateOf<List<SavedContact>>(emptyList()) }
    var smsSearchLoading by remember { mutableStateOf(false) }
    val fallbackResolvedClientName = remember(phone, allTxns.size) { resolveClientNameByPhone(ctx, phone) }
    val enabledOffers by remember {
        derivedStateOf {
            offers.asSequence()
                .filter { it.enabled }
                .sortedByDescending { it.price }
                .toList()
        }
    }
    val manualDirectory by remember(ctx, smsSearchContacts) {
        derivedStateOf { buildManualSearchEntries(ctx, allTxns.toList(), smsSearchContacts) }
    }
    val normalizedPhone = remember(phone) { SmsCommandHandler.normalizePhone(phone) }
    val phoneMatches by remember(phone, manualDirectory) {
        derivedStateOf { autoMatchManualEntries(phone, manualDirectory) }
    }
    val exactPhoneMatch = remember(normalizedPhone, phoneMatches) {
        phoneMatches.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
    }
    val resolvedClientName = exactPhoneMatch?.name?.takeIf { it.isNotBlank() } ?: fallbackResolvedClientName
    val dispatchReady = remember(phone, selOffer) {
        phone.matches(Regex("^[0-9]{10}$")) && selOffer != null
    }
    val manualHistory = allTxns.filter { it.source == TX_SOURCE_MANUAL }.sortedByDescending { it.timestamp }
    val selectedHistoryTx = manualHistory.firstOrNull { it.id == selectedHistoryTxId }
    val inf = rememberInfiniteTransition(label = "console_dispatch")
    val pendingButtonScale by inf.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "console_dispatch_scale"
    )

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val txId = intent.getIntExtra("txId", -1)
                when (intent.action) {
                    ACTION_TX_CREATED -> {
                        val tx = loadTransactionByIdFromPrefs(ctx, txId) ?: return
                        val idx = allTxns.indexOfFirst { it.id == txId }
                        if (idx >= 0) allTxns[idx] = tx else allTxns.add(0, tx)
                    }
                    "com.bingwa.mobile.TX_UPDATED" -> {
                        val idx = allTxns.indexOfFirst { it.id == txId }
                        val updatedTx = loadTransactionByIdFromPrefs(ctx, txId)
                        if (idx >= 0 && updatedTx != null) {
                            allTxns[idx] = updatedTx
                        } else if (updatedTx != null) {
                            allTxns.add(0, updatedTx)
                        }
                        if (txId == pendingTxId && updatedTx != null) bannerState = updatedTx.status.lowercase()
                    }
                    OfferRepository.ACTION_OFFERS_UPDATED -> {
                        offers = OfferRepository.load(ctx).toList()
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.TX_UPDATED")
            addAction(ACTION_TX_CREATED)
            addAction(OfferRepository.ACTION_OFFERS_UPDATED)
        })
        onDispose {
            if (receiverRegistered) {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsSearchContacts = emptyList()
            return@LaunchedEffect
        }
        smsSearchLoading = true
        smsSearchContacts = withContext(Dispatchers.IO) { extractMpesaContacts(ctx, 180) }
        smsSearchLoading = false
    }

    LaunchedEffect(offers) {
        selOffer = when {
            enabledOffers.isEmpty() -> null
            selOffer == null -> enabledOffers.first()
            else -> enabledOffers.firstOrNull { it.id == selOffer?.id } ?: enabledOffers.first()
        }
        if (selOffer == null) mode = "ADVANCED"
    }

    val clockLabel = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    val bannerColor = when (bannerState) {
        "success" -> C.green
        "failed" -> C.red
        "pending" -> C.amber
        "relayed" -> C.blue
        else -> null
    }
    val bannerMessage = when (bannerState) {
        "success" -> "Bundle dispatched successfully"
        "failed" -> "Manual run failed. Check USSD logs."
        "pending" -> "Running manually now. Waiting for USSD response."
        "relayed" -> "Forwarded to Relay phone for execution"
        else -> null
    }
    val commandPhone = if (phone.length == 10) phone else "0712345678"
    val commandCode = selOffer?.ussdCode?.replace("pn", commandPhone, true) ?: "*544*1*1#"
    val executeManualRun = {
        val selectedOffer = selOffer
        phoneErr = when {
            phone.isBlank() -> "Phone number required"
            !phone.matches(Regex("^[0-9]{10}$")) -> "Must be exactly 10 digits"
            selectedOffer == null -> "Choose an offer"
            else -> null
        }
        if (phoneErr == null && selectedOffer != null) {
            vib(ctx, 70L)
            upsertSavedContact(ctx, phone, resolvedClientName)
            if (RelayManager.shouldRelayOffer(ctx, selectedOffer)) {
                bannerState = "pending"
                val finalCode = selectedOffer.ussdCode.replace("pn", phone, true)
                val txId = createPendingTransaction(
                    ctx,
                    selectedOffer.name,
                    "KSh ${selectedOffer.price}",
                    phone,
                    finalCode,
                    clientName = resolvedClientName,
                    status = TransactionStatus.PROCESSING.value,
                    source = TX_SOURCE_MANUAL,
                    showInRecent = false,
                    offerId = selectedOffer.id
                )
                pendingTxId = txId
                saveTransactionOutcome(ctx, txId, TransactionStatus.PROCESSING.value, "Forwarded to Relay phone for execution.")
                broadcastTransactionUpdated(ctx, txId)

                val sent = RelayManager.forwardBuyAmount(ctx, phone, selectedOffer.price)
                if (sent) {
                    bannerState = "relayed"
                } else {
                    bannerState = "failed"
                    saveTransactionOutcome(ctx, txId, TransactionStatus.FAILED.value, "Failed: Relay forwarding failed.")
                    broadcastTransactionUpdated(ctx, txId)
                }
            } else {
                bannerState = "pending"
                val finalCode = selectedOffer.ussdCode.replace("pn", phone, true)
                val txId = createPendingTransaction(
                    ctx,
                    selectedOffer.name,
                    "KSh ${selectedOffer.price}",
                    phone,
                    finalCode,
                    clientName = resolvedClientName,
                    source = TX_SOURCE_MANUAL,
                    showInRecent = false,
                    offerId = selectedOffer.id
                )
                pendingTxId = txId
                ctx.startOfferAutomation(selectedOffer, phone, txId, finalCode, mode)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 430.dp)
                    .padding(bottom = 20.dp),
                shape = RoundedCornerShape(34.dp),
                color = Color(0xFF121618),
                border = BorderStroke(1.dp, Color(0xFF2A302F)),
                shadowElevation = 22.dp
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF171C1E),
                                    Color(0xFF101314)
                                )
                            )
                        )
                        .padding(bottom = 18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .background(Color(0xFF050605))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ManualStatusLed(color = C.cyan, glowing = true)
                            ManualStatusLed(color = C.amber, glowing = true, blinking = true)
                            ManualStatusLed(color = C.t3, glowing = false)
                            Text(
                                "AGENT 042",
                                color = C.t3,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                clockLabel,
                                color = C.t2,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, C.t3.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    if (dispatchReady) "READY" else "IDLE",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    color = if (dispatchReady) C.cyan else C.t3,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "MANUAL TERMINAL",
                            color = C.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            "Manual",
                            color = C.t1,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.4).sp
                        )
                        Text(
                            "Manual execution & history",
                            color = C.t2,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ManualTerminalTabButton(
                            text = "Manual",
                            active = manualTab == "DISPATCH",
                            onClick = { manualTab = "DISPATCH" },
                            modifier = Modifier.weight(1f)
                        )
                        ManualTerminalTabButton(
                            text = "History",
                            active = manualTab == "HISTORY",
                            onClick = { manualTab = "HISTORY" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xFF1B2022),
                        border = BorderStroke(1.dp, Color(0xFF2A3032))
                    ) {
                        if (manualTab == "DISPATCH") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                if (bannerMessage != null && bannerColor != null) {
                                    ManualTerminalBanner(
                                        message = bannerMessage,
                                        color = bannerColor
                                    )
                                }

                                Text(
                                    "TARGET NUMBER",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF121617),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            phoneErr != null -> C.red.copy(alpha = 0.46f)
                                            phone.isNotBlank() -> C.cyan.copy(alpha = 0.28f)
                                            else -> Color(0xFF394144)
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Phone,
                                            null,
                                            tint = C.t2,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        BasicTextField(
                                            value = phone,
                                            onValueChange = {
                                                val digitsOnly = it.filter(Char::isDigit).take(10)
                                                phone = digitsOnly
                                                bannerState = null
                                                phoneErr = when {
                                                    digitsOnly.isBlank() -> null
                                                    digitsOnly.length < 10 -> "Enter all 10 digits"
                                                    else -> null
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = C.t1,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 0.4.sp
                                            ),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                            cursorBrush = SolidColor(C.cyan),
                                            decorationBox = { innerTextField ->
                                                if (phone.isBlank()) {
                                                    Text(
                                                        "0712345678",
                                                        color = C.t3,
                                                        fontSize = 18.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            ManualStatusLed(color = C.cyan, glowing = true, blinking = true)
                                            Text(
                                                "LIVE",
                                                color = C.cyan,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ManualTerminalReadout("${enabledOffers.size} OFFERS")
                                        ManualTerminalReadout(
                                            selOffer?.let { "KES ${it.price}" } ?: "NO OFFER",
                                            accent = C.cyan
                                        )
                                    }
                                    ManualTerminalReadout(mode, accent = C.amber, filled = true)
                                }

                                when {
                                    phoneErr != null -> Text(phoneErr ?: "", color = C.red, fontSize = 12.sp)
                                    exactPhoneMatch != null -> Text("Matched from ${exactPhoneMatch.source}", color = C.green, fontSize = 12.sp)
                                    smsSearchLoading -> Text("Refreshing saved contacts and M-PESA matches...", color = C.t2, fontSize = 12.sp)
                                }

                                AnimatedVisibility(visible = resolvedClientName.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = C.green.copy(alpha = 0.10f),
                                        border = BorderStroke(1.dp, C.green.copy(alpha = 0.24f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Icon(Icons.Outlined.Badge, null, tint = C.green, modifier = Modifier.size(14.dp))
                                            Text(
                                                resolvedClientName,
                                                color = C.t1,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "BUNDLE",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Box {
                                    ManualPaperSlipCard(
                                        name = selOffer?.name ?: "Choose an offer",
                                        meta = selOffer?.let { "KES ${it.price} · ${it.executionMode}" } ?: "Select an enabled offer",
                                        ticketNo = "No.${selOffer?.id?.toString()?.padStart(4, '0') ?: "----"}",
                                        enabled = enabledOffers.isNotEmpty(),
                                        onClick = { offerExp = true }
                                    )
                                    DropdownMenu(
                                        expanded = offerExp,
                                        onDismissRequest = { offerExp = false },
                                        modifier = Modifier
                                            .background(C.cardHi, RoundedCornerShape(14.dp))
                                            .border(1.dp, C.border, RoundedCornerShape(14.dp))
                                    ) {
                                        enabledOffers.forEachIndexed { index, o ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("${index + 1}. ${o.name}", color = C.t1)
                                                        Text(
                                                            "KES ${o.price} · ${o.executionMode}",
                                                            color = C.amber,
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    selOffer = o
                                                    mode = o.executionMode
                                                    offerExp = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Text(
                                    "MODE",
                                    color = C.t3,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ManualModeToggleButton(
                                        label = "SIMPLE",
                                        icon = Icons.Filled.FlashOn,
                                        active = mode == "SIMPLE",
                                        onClick = { mode = "SIMPLE" },
                                        modifier = Modifier.weight(1f)
                                    )
                                    ManualModeToggleButton(
                                        label = "ADVANCED",
                                        icon = Icons.Outlined.AutoMode,
                                        active = mode == "ADVANCED",
                                        onClick = { mode = "ADVANCED" },
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Outlined.Info, null, tint = C.t2, modifier = Modifier.size(14.dp))
                                    Text(
                                        if (mode == "ADVANCED") {
                                            "Auto-navigates the USSD popup. Requires Accessibility access."
                                        } else {
                                            "Uses the simpler manual flow for straightforward requests."
                                        },
                                        color = C.t2,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }

                                ManualTransmitLine(
                                    code = commandCode,
                                    phone = commandPhone
                                )

                                Button(
                                    onClick = executeManualRun,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .graphicsLayer {
                                            val scale = if (bannerState == "pending") pendingButtonScale else 1f
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = C.amber,
                                        contentColor = C.bg
                                    ),
                                    enabled = selOffer != null
                                ) {
                                    Icon(Icons.Filled.Send, null, tint = C.bg, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (bannerState == "pending") "EXECUTING..." else "EXECUTE",
                                        color = C.bg,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        letterSpacing = 0.6.sp
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "HISTORY",
                                            color = C.amber,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.4.sp
                                        )
                                        Text(
                                            "Recent manual dispatches",
                                            color = C.t1,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    ManualTerminalReadout("${manualHistory.size} TOTAL")
                                }

                                if (manualHistory.isEmpty()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color(0xFF121617),
                                        border = BorderStroke(1.dp, Color(0xFF333B3E))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 18.dp, vertical = 22.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Outlined.History, null, tint = C.t2, modifier = Modifier.size(22.dp))
                                            Text(
                                                "No console history yet",
                                                color = C.t1,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "Executed manual dispatches will appear here.",
                                                color = C.t2,
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        manualHistory.forEach { tx ->
                                            ManualTerminalHistoryRow(
                                                tx = tx,
                                                onClick = { selectedHistoryTxId = tx.id }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedHistoryTx != null) {
            RecentTransactionDetailsDialog(
                tx = selectedHistoryTx,
                onDismiss = { selectedHistoryTxId = -1 },
                onDelete = {
                    allTxns.removeAll { it.id == selectedHistoryTx.id }
                    saveTransactions(ctx, allTxns.toList())
                    selectedHistoryTxId = -1
                },
                onRetry = { tx ->
                    val result = retryRecentTransaction(ctx, tx)
                    Toast.makeText(ctx, result.message, if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    if (result.success) {
                        selectedHistoryTxId = if (result.newTxId >= 0) result.newTxId else -1
                        manualTab = "HISTORY"
                    }
                }
            )
        }
    }
}

@Composable
private fun ManualStatusLed(
    color: Color,
    glowing: Boolean,
    blinking: Boolean = false
) {
    val alpha by if (blinking) {
        rememberInfiniteTransition(label = "console_led_blink").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "console_led_alpha"
        )
    } else {
        rememberUpdatedState(1f)
    }
    Box(
        modifier = Modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
            .then(
                if (glowing) {
                    Modifier.border(1.dp, color.copy(alpha = 0.12f), CircleShape)
                } else Modifier
            )
    )
}

@Composable
private fun ManualTerminalTabButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
        color = if (active) Color(0xFF252B2E) else Color(0xFF1A1F21),
        border = BorderStroke(1.dp, if (active) C.amber.copy(alpha = 0.26f) else Color(0xFF2B3234))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text.uppercase(Locale.getDefault()),
                color = if (active) C.t1 else C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(C.amber)
                )
            }
        }
    }
}

@Composable
private fun ManualTerminalBanner(message: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Info, null, tint = color, modifier = Modifier.size(14.dp))
            Text(message, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ManualTerminalReadout(
    text: String,
    accent: Color = C.t2,
    filled: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (filled) accent else Color.Transparent,
        border = BorderStroke(1.dp, if (filled) accent else Color(0xFF394144))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = if (filled) C.bg else accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ManualPaperSlipCard(
    name: String,
    meta: String,
    ticketNo: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .shadow(8.dp, RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFFE8E0CD)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val holeRadius = 3.5.dp.toPx()
                    val spacing = 14.dp.toPx()
                    var x = 14.dp.toPx()
                    while (x < size.width - 10.dp.toPx()) {
                        drawCircle(Color(0xFF1B2022), holeRadius, Offset(x, 0f))
                        drawCircle(Color(0xFF1B2022), holeRadius, Offset(x, size.height))
                        x += spacing
                    }
                }
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(
                ticketNo,
                modifier = Modifier.align(Alignment.TopEnd),
                color = Color(0xFFB8AB8C),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    C.amber,
                                    Color(0xFFCF8B2E)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.FlashOn, null, tint = Color(0xFF241804), modifier = Modifier.size(20.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        name,
                        color = Color(0xFF2A2420),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        meta,
                        color = Color(0xFFB06E16),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color(0xFFB8AB8C))
            }
        }
    }
}

@Composable
private fun ManualModeToggleButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 5.dp, bottomEnd = 5.dp),
        color = if (active) C.amber else Color(0xFF121617),
        border = BorderStroke(1.dp, if (active) Color(0xFF8A5D18) else Color(0xFF394144))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = if (active) Color(0xFF241804) else C.t2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                color = if (active) Color(0xFF241804) else C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
private fun ManualTransmitLine(
    code: String,
    phone: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0D1011),
        border = BorderStroke(1.dp, Color(0xFF394144))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(">", color = C.amber, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("DIAL", color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(code, color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text("TO", color = C.t3, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(phone, color = C.cyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .height(14.dp)
                    .background(C.cyan)
            )
        }
    }
}

@Composable
private fun ManualTerminalHistoryRow(
    tx: Transaction,
    onClick: () -> Unit
) {
    val statusColor = transactionStatusColor(tx)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF121617),
        border = BorderStroke(1.dp, Color(0xFF394144))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.amber.copy(alpha = 0.12f))
                    .border(1.dp, C.amber.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.History, null, tint = C.amber, modifier = Modifier.size(18.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    tx.description.ifBlank { "Manual dispatch" },
                    color = C.t1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${tx.clientName.ifBlank { tx.phoneNumber }} · ${transactionSummaryTime(tx)}",
                    color = C.t2,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.20f))
            ) {
                Text(
                    tx.status.uppercase(Locale.getDefault()),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = statusColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─── Tokens Screen ──────────────────────────────────────────────────────
@Composable
fun TokensScreen() {
    val ctx = LocalContext.current
    val tm = remember { TokenManager(ctx) }
    var bal by remember { mutableIntStateOf(tm.getBalance()) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    val packs = remember {
        listOf(
            TokenTopUp(tokens = 105, ksh = 15),
            TokenTopUp(tokens = 255, ksh = 25, popular = true),
            TokenTopUp(tokens = 605, ksh = 55),
            TokenTopUp(tokens = 1200, ksh = 100)
        )
    }

    val um = remember { UnlimitedManager(ctx) }
    var remMs by remember { mutableLongStateOf(um.remainingMs()) }
    val activePlan = um.getActivePlan()?.takeIf { remMs > 0L }

    LaunchedEffect(Unit) {
        while (true) {
            remMs = um.remainingMs()
            delay(nextCountdownRefreshDelay(remMs))
        }
    }
    DisposableEffect(Unit) {
        TokenManager.tokenBalanceListener = {
            bal = it
            remMs = um.remainingMs()
        }
        onDispose { TokenManager.tokenBalanceListener = null }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            Modifier
                .size(300.dp)
                .offset((-110).dp, 10.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(90.dp, 90.dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.07f), Color.Transparent)), CircleShape)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .widthIn(max = 430.dp)
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TokensHeroCard(balance = bal, activePlan = activePlan, remainingMs = remMs)

                SectionHeader(
                    title = "TOP UP",
                    subtitle = "",
                    accent = C.amber
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    packs.forEach { p ->
                        TokenTopUpCard(p) { confirm = p.ksh }
                    }
                }

                SectionHeader(
                    title = "UNLIMITED",
                    subtitle = "",
                    accent = C.amber
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnlimitedManager.PLANS.forEach { plan ->
                        UnlimitedPlanCard(plan) { confirm = plan.ksh }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    confirm?.let { amount ->
        val plan = UnlimitedManager.planForAmount(amount)
        val tokensToAdd = if (plan == null) TokenManager.convertAmountToTokens(amount) else 0
        val confirmAccent = C.amber
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm Purchase", color = C.t1) },
            text = {
                Text(
                    if (plan != null) "Use KSh $amount airtime to activate unlimited ${plan.label.lowercase()} access?"
                    else "Use KSh $amount airtime to receive $tokensToAdd tokens?",
                    color = C.t2
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        MpesaReceiver.buyTokensWithAirtime(ctx, amount) { ok, info ->
                            if (ok) {
                                remMs = um.remainingMs()
                                if (plan != null) Toast.makeText(ctx, "Unlimited ${plan.label} activated. ${formatRemainingTimeDetailed(remMs)}.", Toast.LENGTH_SHORT).show()
                                else Toast.makeText(ctx, "Airtime used successfully. $tokensToAdd tokens were added.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, info ?: "Token purchase failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Confirm", color = confirmAccent)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = C.t2) } }
        )
    }
}

private data class TokenTopUp(val tokens: Int, val ksh: Int, val popular: Boolean = false)

@Composable
private fun TokenTopUpCard(p: TokenTopUp, onBuy: () -> Unit) {
    val accent = C.amber
    Surface(
        color = Color(0xFF14181A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF21211C),
                            Color(0xFF14181A),
                            Color(0xFF0F1418)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (p.popular) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                    ) {
                        Text(
                            "POPULAR",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
                            color = accent,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        p.tokens.toString(),
                        color = C.t1,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "tokens",
                        color = C.t2,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 3.dp),
                        maxLines = 1
                    )
                }
                Text("Ksh ${p.ksh}", color = C.t2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onBuy,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = accent),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun UnlimitedPlanCard(plan: UnlimitedManager.Plan, onBuy: () -> Unit) {
    val accent = C.amber
    Surface(
        color = Color(0xFF14181A),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF21211C),
                            Color(0xFF14181A),
                            Color(0xFF0F1418)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unlimited", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
                    ) {
                        Text(
                            plan.label.uppercase(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
                Text("KSh ${plan.ksh}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Button(
                onClick = onBuy,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = accent),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// ─── Contacts Screen ─────────────────────────────────────────────────────
@Composable
fun ContactsScreen(onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE)
    var contacts by remember { mutableStateOf(loadContacts(prefs)) }
    var query by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var showAddDlg by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    val filtered = contacts.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query) }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = C.cardHi.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, null, tint = C.t1)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Contacts", color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${contacts.size} customers saved", color = C.t2, fontSize = 12.sp)
                }
                ContactHeaderAction(Icons.Filled.CloudDownload, C.cyan) { showImport = true }
                ContactHeaderAction(Icons.Filled.PersonAdd, C.purple) {
                    showAddDlg = true
                    newName = ""
                    newPhone = ""
                }
            }
        } else {
            PageHeader("Contacts", "${contacts.size} customers saved")
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(Modifier.weight(1f), "Import M-PESA", Icons.Filled.CloudDownload, C.cyan) { showImport = true }
                ActionButton(Modifier.weight(1f), "Add Manually", Icons.Filled.PersonAdd, C.purple) {
                    showAddDlg = true
                    newName = ""
                    newPhone = ""
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            color = C.cardHi.copy(alpha = 0.94f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.88f))
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Directory", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (filtered.isEmpty()) "No matches" else "${filtered.size} visible",
                        color = C.t3,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name or number…", color = C.t3, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.t2, modifier = Modifier.size(17.dp)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, null, tint = C.t2, modifier = Modifier.size(15.dp))
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    singleLine = true
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.card,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(C.cyanDim),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.People, null, tint = C.cyan, modifier = Modifier.size(28.dp))
                        }
                        Text(
                            if (query.isBlank()) "No contacts saved yet" else "No matching contacts",
                            color = C.t1,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (query.isBlank()) "Import M-PESA contacts or add a number manually to build your customer directory." else "Try another name or phone number.",
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.phone }) { c ->
                    ContactCard(
                        c = c,
                        onDelete = {
                            contacts = contacts.filter { it.phone != c.phone }
                            saveContacts(prefs, contacts)
                        },
                        onCall = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")))
                            }
                        },
                        onMessage = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${c.phone}")))
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        if (showImport) {
            MpesaImportDialog(onDismiss = { showImport = false }) { imported ->
                val merged = (contacts + imported).distinctBy { it.phone }.sortedBy { it.name }
                contacts = merged; saveContacts(prefs, merged); vib(ctx)
                Toast.makeText(ctx, "${imported.size} contacts imported", Toast.LENGTH_LONG).show()
                showImport = false
            }
        }
        if (showAddDlg) {
            AlertDialog(
                containerColor = C.card, shape = RoundedCornerShape(18.dp), onDismissRequest = { showAddDlg = false },
                title = { Text("Add Contact", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name (optional)") }, singleLine = true, colors = dialogFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, label = { Text("Phone number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), colors = dialogFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    Button(onClick = { if (newPhone.isNotBlank()) { contacts = contacts + SavedContact(newName.trim(), newPhone.trim()); saveContacts(prefs, contacts); showAddDlg = false; vib(ctx) } }, colors = ButtonDefaults.buttonColors(containerColor = C.cyan)) {
                        Text("Save", color = C.bg, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = { TextButton(onClick = { showAddDlg = false }) { Text("Cancel", color = C.t2) } }
            )
        }
    }
}

@Composable
private fun ContactHeaderAction(icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ContactCard(c: SavedContact, onDelete: () -> Unit, onCall: () -> Unit, onMessage: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val accent = remember(c.phone, c.name) {
        listOf(C.cyan, C.green, C.blue, C.orange, C.purple)[kotlin.math.abs((c.phone + c.name).hashCode()) % 5]
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = C.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                        .border(1.dp, accent.copy(alpha = 0.24f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (c.name.ifBlank { c.phone }).take(2).uppercase(),
                        color = accent,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (c.name.isNotBlank()) {
                        Text(c.name, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.Phone, null, tint = C.t3, modifier = Modifier.size(12.dp))
                        Text(
                            c.phone,
                            color = if (c.name.isBlank()) C.t1 else C.t2,
                            fontSize = if (c.name.isBlank()) 14.sp else 12.sp
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, null, tint = C.t3, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        modifier = Modifier
                            .background(C.cardHi)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.border, RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = C.red, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = C.red, modifier = Modifier.size(16.dp)) },
                            onClick = { onDelete(); menu = false }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "Call",
                    icon = Icons.Outlined.Call,
                    color = C.green,
                    onClick = onCall
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    label = "SMS",
                    icon = Icons.Outlined.Sms,
                    color = C.blue,
                    onClick = onMessage
                )
            }
        }
    }
}

@Composable
fun ActionButton(modifier: Modifier, label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = color.copy(alpha = 0.07f))
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    var showOffers by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf((prefs.safeGetString("theme_mode", AppTheme.mode.name) ?: AppTheme.mode.name).uppercase()) }
    var themeExp by remember { mutableStateOf(false) }

    if (showOffers) { OffersScreen(onBack = { showOffers = false }); return }

    var autoClear by remember { mutableStateOf(prefs.safeGetString("auto_clear", "Never") ?: "Never") }
    var clearExpanded by remember { mutableStateOf(false) }
    val clearOptions = listOf("Daily", "Weekly", "Monthly", "Yearly", "Never")
    var overviewData by remember { mutableStateOf(calculateOverview(ctx)) }
    var autoEnabled by remember { mutableStateOf(prefs.safeGetBoolean("automation_enabled", true)) }
    var autoRetry by remember { mutableStateOf(prefs.safeGetBoolean("auto_retry", false)) }
    var autoContacts by remember { mutableStateOf(prefs.safeGetBoolean("auto_save_contacts", true)) }
    val sims = getAvailableSims(ctx)
    var simId by remember { mutableIntStateOf(prefs.safeGetInt("selected_sim_id", -1)) }
    var notifySuccess by remember { mutableStateOf(prefs.safeGetBoolean("notify_success", true)) }
    var notifyFailed by remember { mutableStateOf(prefs.safeGetBoolean("notify_failed", true)) }
    var notifySimId by remember { mutableIntStateOf(prefs.safeGetInt("notify_sim_id", -1)) }
    var tplSuccess by remember { mutableStateOf(prefs.safeGetString("sms_tpl_success", DEFAULT_TPL_SUCCESS) ?: DEFAULT_TPL_SUCCESS) }
    var tplFailed by remember { mutableStateOf(prefs.safeGetString("sms_tpl_failed", DEFAULT_TPL_FAILED) ?: DEFAULT_TPL_FAILED) }
    var tplPending by remember { mutableStateOf(prefs.safeGetString("sms_tpl_pending", DEFAULT_TPL_PENDING) ?: DEFAULT_TPL_PENDING) }
    var vibToggle by remember { mutableStateOf(prefs.safeGetBoolean("vibration_on_toggle", true)) }
    var vibExecute by remember { mutableStateOf(prefs.safeGetBoolean("vibration_on_execute", true)) }
    var remoteEnabled by remember { mutableStateOf(prefs.safeGetBoolean("remote_enabled", false)) }
    var adminPhone by remember { mutableStateOf(prefs.safeGetString("admin_phone", "") ?: "") }
    var adminPrefix by remember { mutableStateOf(prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA") }
    var adminPin by remember { mutableStateOf(prefs.safeGetString("sms_pin", "") ?: "") }
    var pinVisible by remember { mutableStateOf(false) }
    var adminSmsSimId by remember { mutableIntStateOf(prefs.safeGetInt("admin_sms_sim_id", -1)) }
    var alertLowBalance by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_balance", false)) }
    var alertLowTokens by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_tokens", false)) }
    var alertFailedTx by remember { mutableStateOf(prefs.safeGetBoolean("alert_failed_tx", false)) }
    var lowBalanceLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_balance_limit", 50)) }
    var lowTokenLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_token_limit", 5)) }
    var alertLowBattery by remember { mutableStateOf(prefs.safeGetBoolean("alert_low_battery", false)) }
    var lowBatteryLimit by remember { mutableIntStateOf(prefs.safeGetInt("low_battery_limit", 20)) }
    var twoPhoneEnabled by remember { mutableStateOf(prefs.safeGetBoolean("two_phone_enabled", false)) }
    var twoPhoneRole by remember { mutableStateOf(prefs.safeGetString("two_phone_role", "PRIMARY") ?: "PRIMARY") }
    var relayMethod by remember { mutableStateOf(prefs.safeGetString("relay_method", "SMS") ?: "SMS") }
    var pairedPhone by remember { mutableStateOf(prefs.safeGetString("paired_phone", "") ?: "") }
    var relayIp by remember { mutableStateOf(prefs.safeGetString("relay_ip", "") ?: "") }
    var relayIpAuto by remember { mutableStateOf(prefs.safeGetBoolean("relay_ip_auto", false)) }
    var relayPrefix by remember { mutableStateOf(prefs.safeGetString("relay_prefix", prefs.safeGetString("sms_prefix", "BINGWA")) ?: (prefs.safeGetString("sms_prefix", "BINGWA") ?: "BINGWA")) }
    var relayPin by remember { mutableStateOf(prefs.safeGetString("relay_pin", prefs.safeGetString("sms_pin", "")) ?: (prefs.safeGetString("sms_pin", "") ?: "")) }
    var relaySendResults by remember { mutableStateOf(prefs.safeGetBoolean("relay_send_results", true)) }
    var roleExp by remember { mutableStateOf(false) }
    var methodExp by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState())) {
        PageHeader("Settings", "App preferences & configuration")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsOverviewCard(
                themeMode = themeMode,
                autoEnabled = autoEnabled,
                remoteEnabled = remoteEnabled,
                twoPhoneEnabled = twoPhoneEnabled
            )

            SettingsGroup("Appearance") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.DarkMode)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("System, Dark, or Light", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(onClick = { themeExp = true }) { Text(themeMode, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(expanded = themeExp, onDismissRequest = { themeExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                            listOf("SYSTEM", "DARK", "LIGHT").forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt, color = if (opt == themeMode) C.cyan else C.t1) },
                                    onClick = {
                                        themeMode = opt
                                        prefs.edit().putString("theme_mode", opt).apply()
                                        AppTheme.mode = runCatching { ThemeMode.valueOf(opt) }.getOrDefault(ThemeMode.SYSTEM)
                                        themeExp = false
                                    }
                                )
                            }
                        }
                    }
                }
                GroupDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.Palette)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App Style", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Fixed Bybit-style yellow selection with green, pending, and failed status colors", color = C.t2, fontSize = 11.sp)
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = C.amberDim,
                        border = BorderStroke(1.dp, C.amber.copy(alpha = 0.28f))
                    ) {
                        Text(
                            "BYBIT",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = C.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            SettingsGroup("Automation") {
                ToggleRow(Icons.Rounded.SmartToy, "Enable Automation", "Auto-run bundles on payment", autoEnabled) { autoEnabled = it; prefs.edit().putBoolean("automation_enabled", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.Autorenew, "Auto-Retry on Failure", "Retry failed USSD up to 3 times", autoRetry) { autoRetry = it; prefs.edit().putBoolean("auto_retry", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.PersonAdd, "Auto-Save Contacts", "Save payer numbers from M-PESA SMS", autoContacts) { autoContacts = it; prefs.edit().putBoolean("auto_save_contacts", it).apply() }
                GroupDivider()
                SimPickerRow("USSD SIM Card", "SIM used for USSD calls", sims, simId) { simId = it; prefs.edit().putInt("selected_sim_id", it).apply() }
            }

            SettingsGroup("Bundle Offers") {
                LinkRow(Icons.Rounded.DataObject, "Manage Offers & USSD Codes", "Add, edit, remove bundles", C.cyan) { showOffers = true }
            }

            SettingsGroup("Two-Phone Mode") {
                ToggleRow(Icons.Rounded.SyncAlt, "Enable Two‑Phone Mode", "Forward selected offers to the Relay phone", twoPhoneEnabled) {
                    twoPhoneEnabled = it
                    prefs.edit().putBoolean("two_phone_enabled", it).apply()
                    if (!it) RelayManager.stopRelayHotspotService(ctx)
                    val cfg = RelayManager.load(ctx)
                    if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") RelayManager.startRelayHotspotService(ctx)
                }
                AnimatedVisibility(visible = twoPhoneEnabled) {
                    Column {
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Devices)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("This Phone Role", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("PRIMARY receives M‑PESA; RELAY executes selected offers", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { roleExp = true }) { Text(twoPhoneRole.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = roleExp, onDismissRequest = { roleExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("PRIMARY", "RELAY").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == twoPhoneRole.uppercase()) C.cyan else C.t1) },
                                            onClick = { twoPhoneRole = opt; prefs.edit().putString("two_phone_role", opt).apply(); roleExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsRowIcon(Icons.Rounded.Router)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Relay Method", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("SMS works anywhere; Hotspot works offline on local network", color = C.t2, fontSize = 11.sp)
                            }
                            Box {
                                TextButton(onClick = { methodExp = true }) { Text(relayMethod.uppercase(), color = C.cyan, fontSize = 12.sp) }
                                DropdownMenu(expanded = methodExp, onDismissRequest = { methodExp = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                                    listOf("SMS", "HOTSPOT").forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = if (opt == relayMethod.uppercase()) C.cyan else C.t1) },
                                            onClick = { relayMethod = opt; prefs.edit().putString("relay_method", opt).apply(); methodExp = false }
                                        )
                                    }
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Paired Phone Number", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("PRIMARY → RELAY number, and RELAY → PRIMARY number", color = C.t2, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = pairedPhone,
                                onValueChange = { pairedPhone = it.trim() },
                                placeholder = { Text("e.g. 0712345678", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                        }
                        AnimatedVisibility(visible = relayMethod.uppercase() == "HOTSPOT") {
                            Column {
                                GroupDivider()
                                ToggleRow(Icons.Rounded.AutoFixHigh, "Auto‑Detect Relay IP", "Connect using current Wi‑Fi gateway (hotspot)", relayIpAuto) {
                                    relayIpAuto = it
                                    prefs.edit().putBoolean("relay_ip_auto", it).apply()
                                }
                                GroupDivider()
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SettingsRowIcon(Icons.Rounded.WifiTethering); Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text("Relay Hotspot IP", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            Text("IP address of the RELAY phone on the hotspot network", color = C.t2, fontSize = 11.sp)
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = relayIp,
                                        onValueChange = { relayIp = it.trim() },
                                        placeholder = { Text(if (relayIpAuto) "Auto-detected" else "e.g. 192.168.43.1", color = C.t3) },
                                        leadingIcon = { Icon(Icons.Rounded.Router, null, tint = C.t2) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = fieldColors(),
                                        enabled = !relayIpAuto,
                                        singleLine = true
                                    )
                                }
                            }
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Prefix used when PRIMARY forwards via SMS", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = relayPrefix, onValueChange = { relayPrefix = it.trim().uppercase() }, placeholder = { Text("BINGWA", color = C.t3) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true)
                        }
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                                Column { Text("Relay PIN (optional)", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Must match on both phones (recommended)", color = C.t2, fontSize = 11.sp) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = relayPin,
                                onValueChange = { relayPin = it.filter { ch -> ch.isDigit() }.take(6) },
                                placeholder = { Text("4–6 digits", color = C.t3) },
                                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                            )
                        }
                        GroupDivider()
                        ToggleRow(Icons.Rounded.Sms, "Send Relay Results by SMS", "RELAY sends execution result back to PRIMARY", relaySendResults) {
                            relaySendResults = it
                            prefs.edit().putBoolean("relay_send_results", it).apply()
                        }
                        GroupDivider()
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Button(
                                onClick = {
                                    prefs.edit()
                                        .putString("two_phone_role", twoPhoneRole.trim().uppercase())
                                        .putString("relay_method", relayMethod.trim().uppercase())
                                        .putString("paired_phone", pairedPhone.trim())
                                        .putString("relay_ip", relayIp.trim())
                                        .putBoolean("relay_ip_auto", relayIpAuto)
                                        .putString("relay_prefix", relayPrefix.trim().uppercase())
                                        .putString("relay_pin", relayPin.trim())
                                        .apply()
                                    RelayManager.stopRelayHotspotService(ctx)
                                    val cfg = RelayManager.load(ctx)
                                    if (cfg.enabled && cfg.role == "RELAY" && cfg.method == "HOTSPOT") RelayManager.startRelayHotspotService(ctx)
                                    vib(ctx)
                                    Toast.makeText(ctx, "Two‑Phone settings saved", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                                Text("Save Two‑Phone Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            SettingsGroup("Remote Admin") {
                ToggleRow(Icons.Rounded.PhoneAndroid, "Enable Remote Control", "Allow SMS commands from admin phone", remoteEnabled) { remoteEnabled = it; prefs.edit().putBoolean("remote_enabled", it).apply() }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val pinHint = if (adminPin.trim().isNotEmpty()) "*".repeat((adminPin.trim().length - 2).coerceAtLeast(0)) + adminPin.trim().takeLast(2) else ""
                    Surface(shape = RoundedCornerShape(14.dp), color = C.w04, border = BorderStroke(1.dp, C.border)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(C.cyanDim)
                                    .border(1.dp, C.cyan.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Info, null, tint = C.cyan, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Command Format", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text("${adminPrefix.trim().uppercase()} ${if (pinHint.isNotEmpty()) "$pinHint " else ""}STATUS", color = C.t2, fontSize = 11.sp)
                            }
                        }
                    }
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Phone); Spacer(Modifier.width(12.dp))
                        Column { Text("Admin Phone", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Number that can send remote commands", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = adminPhone, onValueChange = { adminPhone = it.trim() }, placeholder = { Text("e.g. 0712345678", color = C.t3) }, leadingIcon = { Icon(Icons.Rounded.Phone, null, tint = C.t2) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Tag); Spacer(Modifier.width(12.dp))
                        Column { Text("Command Prefix", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("Word that must start every SMS command", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = adminPrefix, onValueChange = { adminPrefix = it.trim().uppercase() }, placeholder = { Text("BINGWA", color = C.t3) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors(), singleLine = true)
                }
                GroupDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsRowIcon(Icons.Rounded.Lock); Spacer(Modifier.width(12.dp))
                        Column { Text("Security PIN", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("4–6 digit PIN to authorise commands (optional)", color = C.t2, fontSize = 11.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = adminPin,
                        onValueChange = { adminPin = it.filter { ch -> ch.isDigit() }.take(6) },
                        placeholder = { Text("4–6 digits", color = C.t3) },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = C.t2) },
                        trailingIcon = {
                            IconButton(onClick = { pinVisible = !pinVisible }) {
                                Icon(if (pinVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = C.t2)
                            }
                        },
                        visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                GroupDivider()
                SimPickerRow("Admin SMS SIM", "SIM used for admin notifications", sims, adminSmsSimId) { adminSmsSimId = it; prefs.edit().putInt("admin_sms_sim_id", it).apply() }
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Button(
                        onClick = { prefs.edit().putString("admin_phone", adminPhone.trim()).putString("sms_prefix", adminPrefix.trim().uppercase()).putString("sms_pin", adminPin.trim()).apply(); vib(ctx); Toast.makeText(ctx, "Admin settings saved", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                    ) {
                        Icon(Icons.Filled.Save, null, tint = C.bg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                        Text("Save Admin Settings", color = C.bg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }

            SettingsGroup("Admin Alerts") {
                ToggleRow(Icons.Rounded.Warning, "Low Airtime Alert", "SMS when balance drops below limit", alertLowBalance) { alertLowBalance = it; prefs.edit().putBoolean("alert_low_balance", it).apply() }
                AnimatedVisibility(visible = alertLowBalance) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (KES)", lowBalanceLimit) { v -> lowBalanceLimit = v; prefs.edit().putInt("low_balance_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Circle, "Low Tokens Alert", "SMS when tokens fall below limit", alertLowTokens) { alertLowTokens = it; prefs.edit().putBoolean("alert_low_tokens", it).apply() }
                AnimatedVisibility(visible = alertLowTokens) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit", lowTokenLimit) { v -> lowTokenLimit = v; prefs.edit().putInt("low_token_limit", v).apply() }
                    }
                }
                GroupDivider()
                ToggleRow(Icons.Rounded.Error, "Failed Transaction Alert", "SMS when a transaction fails", alertFailedTx) { alertFailedTx = it; prefs.edit().putBoolean("alert_failed_tx", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.BatteryAlert, "Low Battery Alert", "SMS when battery drops below limit", alertLowBattery) { alertLowBattery = it; prefs.edit().putBoolean("alert_low_battery", it).apply() }
                AnimatedVisibility(visible = alertLowBattery) {
                    Column {
                        GroupDivider()
                        LimitRow("Limit (%)", lowBatteryLimit) { v ->
                            val vv = v.coerceIn(1, 100)
                            lowBatteryLimit = vv
                            prefs.edit().putInt("low_battery_limit", vv).apply()
                        }
                    }
                }
            }

            SettingsGroup("Success Notification") {
                ToggleRow(Icons.Rounded.CheckCircle, "Send on Success", "SMS customer when bundle is delivered", notifySuccess) { notifySuccess = it; prefs.edit().putBoolean("notify_success", it).apply() }
                AnimatedVisibility(visible = notifySuccess) {
                    Column {
                        GroupDivider()
                        SimPickerRow("Send via SIM", "SIM used to send this SMS", sims, notifySimId) { notifySimId = it; prefs.edit().putInt("notify_sim_id", it).apply() }
                        GroupDivider()
                        TemplateEditor("Success Message Template", tplSuccess, C.green, SMS_TAGS) { tplSuccess = it; prefs.edit().putString("sms_tpl_success", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplSuccess, C.green)
                    }
                }
            }

            SettingsGroup("Daily Limit / Pending Notification") {
                var notifyPending by remember { mutableStateOf(prefs.safeGetBoolean("notify_pending", true)) }
                ToggleRow(Icons.Rounded.Schedule, "Send on Daily Limit", "SMS customer when offer already used today", notifyPending) { notifyPending = it; prefs.edit().putBoolean("notify_pending", it).apply() }
                AnimatedVisibility(visible = notifyPending) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Pending / Daily Limit Template", tplPending, C.amber, SMS_TAGS) { tplPending = it; prefs.edit().putString("sms_tpl_pending", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplPending, C.amber)
                    }
                }
            }

            SettingsGroup("Failure Notification") {
                ToggleRow(Icons.Rounded.Warning, "Send on Failure", "SMS customer when bundle fails", notifyFailed) { notifyFailed = it; prefs.edit().putBoolean("notify_failed", it).apply() }
                AnimatedVisibility(visible = notifyFailed) {
                    Column {
                        GroupDivider()
                        TemplateEditor("Failure Message Template", tplFailed, C.red, SMS_TAGS) { tplFailed = it; prefs.edit().putString("sms_tpl_failed", it).apply() }
                        Spacer(Modifier.height(6.dp))
                        TemplatePreview(tplFailed, C.red)
                    }
                }
            }

            SettingsGroup("Transactions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsRowIcon(Icons.Rounded.AutoDelete); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Clear Transactions", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Automatically delete old records", color = C.t2, fontSize = 11.sp)
                    }
                    Box {
                        TextButton(onClick = { clearExpanded = true }) { Text(autoClear, color = C.cyan, fontSize = 12.sp) }
                        DropdownMenu(expanded = clearExpanded, onDismissRequest = { clearExpanded = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                            clearOptions.forEach { opt -> DropdownMenuItem(text = { Text(opt, color = if (opt == autoClear) C.cyan else C.t1) }, onClick = { autoClear = opt; prefs.edit().putString("auto_clear", opt).apply(); clearExpanded = false }) }
                        }
                    }
                }
                GroupDivider()
                LinkRow(Icons.Rounded.DeleteSweep, "Clear All Transactions", "Wipe entire transaction history", C.red) {
                    ctx.getSharedPreferences("transactions", Context.MODE_PRIVATE).edit().remove("list").apply()
                    Toast.makeText(ctx, "Transactions cleared", Toast.LENGTH_SHORT).show()
                }
            }

            SettingsGroup("Bundle Overview") {
                if (overviewData.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Text("No transactions yet", color = C.t3, fontSize = 13.sp) }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        overviewData.take(5).forEachIndexed { idx, row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${idx + 1}. ${row.first}", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text("${row.second} sold", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (idx < overviewData.size - 1) Divider(color = C.border.copy(.5f), thickness = 0.5.dp)
                        }
                    }
                    GroupDivider()
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { ctx.getSharedPreferences("transactions", Context.MODE_PRIVATE).edit().remove("list").apply(); overviewData = emptyList() }) {
                            Text("Clear Overview Data", color = C.amber, fontSize = 12.sp)
                        }
                    }
                }
            }

            SettingsGroup("Haptics") {
                ToggleRow(Icons.Rounded.Vibration, "Vibrate on Toggle", "Haptic feedback on start/stop", vibToggle) { vibToggle = it; prefs.edit().putBoolean("vibration_on_toggle", it).apply() }
                GroupDivider()
                ToggleRow(Icons.Rounded.PlayArrow, "Vibrate on Manual", "Haptic feedback on manual send", vibExecute) { vibExecute = it; prefs.edit().putBoolean("vibration_on_execute", it).apply() }
            }

            SettingsGroup("System Permissions") {
                LinkRow(Icons.Rounded.Accessibility, "Accessibility Service", "Required for ADVANCED popup navigation", C.purple) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                GroupDivider()
                LinkRow(Icons.Rounded.Sms, "Default SMS App", "Required to read M-PESA messages", C.blue) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.w04).border(1.dp, C.border, RoundedCornerShape(16.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(C.cyanDim).border(1.dp, C.cyan.copy(alpha = 0.2f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                        Text("B", color = C.cyan, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Bingwa Mobile", color = C.t1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Version 3.0.41 · by Victor Ngetich", color = C.t2, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─── Offers Screen ───────────────────────────────────────────────────────
@Composable
fun OffersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var offers by remember {
        mutableStateOf(OfferRepository.load(ctx).toList())
    }
    var showDlg by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<OfferItem?>(null) }
    var showRepairDialog by remember { mutableStateOf(false) }
    var pendingApprovalOfferId by remember { mutableStateOf<Int?>(null) }

    fun persist(list: List<OfferItem>) {
        offers = list
        OfferRepository.save(ctx, list)
    }

    fun launchSignatureLearning(offer: OfferItem) {
        val cleanOffer = offer.clearPendingSignatureReview()
        val list = offers.toMutableList()
        val index = list.indexOfFirst { it.id == cleanOffer.id }
        if (index >= 0) list[index] = cleanOffer else list.add(cleanOffer)
        persist(list)
        Toast.makeText(ctx, "Learning USSD signature for ${cleanOffer.name}", Toast.LENGTH_SHORT).show()
        val learnPhone = "0700000000"
        val learnCode = cleanOffer.ussdCode.replace("pn", learnPhone, ignoreCase = true)
        ctx.startOfferAutomation(
            offer = cleanOffer,
            phoneNumber = learnPhone,
            txId = -1,
            finalCode = learnCode,
            mode = cleanOffer.executionMode,
            signatureLearning = true
        )
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                offers = OfferRepository.load(ctx).toList()
                if (intent.action == "com.bingwa.mobile.OFFER_SIGNATURE_LEARNED") {
                    val offerId = intent.getIntExtra("offerId", -1)
                    val pendingOffer = offers.firstOrNull { it.id == offerId }
                    if (offerId >= 0 && pendingOffer != null &&
                        (pendingOffer.pendingLearnedSignature.isNotEmpty() || pendingOffer.pendingSignatureLearningCaptures.isNotEmpty())
                    ) {
                        pendingApprovalOfferId = offerId
                    }
                }
            }
        }
        val receiverRegistered = registerAppReceiver(ctx, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
            addAction(OfferRepository.ACTION_OFFERS_UPDATED)
        })
        onDispose {
            if (receiverRegistered) {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }
    }

    Scaffold(
        containerColor = C.bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Bundle Offers", color = C.t1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null, tint = C.t1) } },
                actions = {
                    IconButton({ showRepairDialog = true }) { Icon(Icons.Outlined.Refresh, null, tint = C.green) }
                    IconButton({ editItem = null; showDlg = true }) { Icon(Icons.Filled.Add, null, tint = C.cyan) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
            )
        }
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = pad.calculateTopPadding() + 10.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (offers.isEmpty()) item { AnimatedEmptyState() }
            else itemsIndexed(offers) { idx, o ->
                OfferCard(
                    number = idx + 1,
                    o = o,
                    onEdit = { editItem = o; showDlg = true },
                    onToggle = { persist(offers.toMutableList().also { it[idx] = o.copy(enabled = !o.enabled) }) },
                    onDelete = { persist(offers.toMutableList().also { it.removeAt(idx) }) }
                )
            }
        }
        if (showDlg) {
            OfferDialog(
                existing = editItem,
                onDismiss = { showDlg = false },
                onSave = { updated ->
                    val list = offers.toMutableList()
                    val i = list.indexOfFirst { it.id == updated.id }
                    if (i >= 0) list[i] = updated else list.add(updated)
                    persist(list)
                    showDlg = false
                },
                onSaveAndLearn = { updated ->
                    showDlg = false
                    launchSignatureLearning(updated)
                },
                onApprovePending = { offer ->
                    OfferRepository.approveStagedSignature(ctx, offer.id)
                    offers = OfferRepository.load(ctx).toList()
                    pendingApprovalOfferId = null
                    Toast.makeText(ctx, "Approved learned signature for ${offer.name}", Toast.LENGTH_SHORT).show()
                    showDlg = false
                },
                onRelearnSignature = { offer ->
                    pendingApprovalOfferId = null
                    showDlg = false
                    launchSignatureLearning(offer)
                }
            )
        }
        if (showRepairDialog) {
            AlertDialog(
                containerColor = C.card,
                shape = RoundedCornerShape(20.dp),
                onDismissRequest = { showRepairDialog = false },
                title = { Text("Repair Bundle Catalog", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Restore missing built-in bundles, remove broken entries, and keep your custom offers. Use this if the console has no bundles or offer data became corrupted.",
                        color = C.t2,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val result = OfferRepository.repair(ctx)
                        offers = result.offers
                        showRepairDialog = false
                        Toast.makeText(
                            ctx,
                            "Catalog repaired. Restored ${result.restoredDefaultOffers} default offers and removed ${result.removedBrokenOffers} broken entries.",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text("Repair Now", color = C.cyan, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRepairDialog = false }) {
                        Text("Cancel", color = C.t2)
                    }
                }
            )
        }
    }

    offers.firstOrNull { it.id == pendingApprovalOfferId }?.let { offer ->
        SignatureApprovalDialog(
            offer = offer,
            onApprove = {
                OfferRepository.approveStagedSignature(ctx, offer.id)
                offers = OfferRepository.load(ctx).toList()
                pendingApprovalOfferId = null
                Toast.makeText(ctx, "Approved learned signature for ${offer.name}", Toast.LENGTH_SHORT).show()
            },
            onRelearn = {
                pendingApprovalOfferId = null
                launchSignatureLearning(offer)
            },
            onDismiss = { pendingApprovalOfferId = null }
        )
    }
}

@Composable
fun OfferCard(number: Int, o: OfferItem, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val hasPendingSignature = o.pendingLearnedSignature.isNotEmpty() || o.pendingSignatureLearningCaptures.isNotEmpty()
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.card)
            .border(1.dp, if (o.enabled) C.border else C.border.copy(0.4f), RoundedCornerShape(18.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (o.enabled) C.cyanDim else C.w04)
                        .border(1.dp, if (o.enabled) C.cyan.copy(0.16f) else C.border, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Wifi, null, tint = if (o.enabled) C.cyan else C.t3, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = C.surface.copy(alpha = 0.92f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
                        ) {
                            Text(
                                number.toString(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = C.cyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            o.name,
                            color = if (o.enabled) C.t1 else C.t2,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniTag(if (o.enabled) "ACTIVE" else "DISABLED", if (o.enabled) C.green else C.red)
                        MiniTag(o.category.uppercase(), C.orange)
                    }
                }
                Box {
                    IconButton({ menu = true }, Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, null, tint = C.t2, modifier = Modifier.size(16.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(C.cardHi, RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
                        DropdownMenuItem(text = { Text("Edit", color = C.t1) }, leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = C.t1) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(if (o.enabled) "Disable" else "Enable", color = if (o.enabled) C.red else C.green) }, leadingIcon = { Icon(if (o.enabled) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = if (o.enabled) C.red else C.green) }, onClick = { menu = false; onToggle() })
                        DropdownMenuItem(text = { Text("Delete", color = C.red) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = C.red) }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            Divider(color = C.border)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OfferInfoTile("Price", "KES ${o.price}", C.cyan, Modifier.weight(1f))
                OfferInfoTile("Mode", o.executionMode, C.purple, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OfferInfoTile("Device", o.targetDevice, C.blue, Modifier.weight(1f))
                OfferInfoTile(
                    "Protection",
                    when {
                        o.signatureDetectionEnabled && o.signatureAction == "ADJUST" -> "Guard + Adjust"
                        o.signatureDetectionEnabled -> "Guard + Stop"
                        else -> "Off"
                    },
                    if (o.signatureDetectionEnabled) C.green else C.t3,
                    Modifier.weight(1f)
                )
            }

            OfferCodeBlock("USSD Code", o.ussdCode)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (o.signatureDetectionEnabled) MiniTag("GUARD ON", C.green)
                if (o.learnedSignature.isNotEmpty()) MiniTag("STEPS ${o.learnedSignature.size}", C.green)
                if (o.signatureLearningCaptures.isNotEmpty()) MiniTag("POPUPS ${o.signatureLearningCaptures.size}", C.amber)
                if (hasPendingSignature) MiniTag("PENDING REVIEW", C.orange)
            }
        }
    }
}

@Composable
private fun SignatureApprovalDialog(
    offer: OfferItem,
    onApprove: () -> Unit,
    onRelearn: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    val pendingSteps = offer.pendingLearnedSignature
    val pendingCaptures = offer.pendingSignatureLearningCaptures
    val preview = pendingCaptures.lastOrNull()?.popupText
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(180)
        .orEmpty()

    AlertDialog(
        containerColor = C.card,
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Verify Learned Signature", color = C.t1, fontWeight = FontWeight.Bold)
                Text(
                    offer.name,
                    color = C.t2,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The app learned ${pendingSteps.size} menu step(s) and captured ${pendingCaptures.size} popup(s). Verify it if every step and selection is correct, or relearn it if anything looks wrong. Each saved step keeps the recorded text and the option that was chosen.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Text(
                    "Learned on ${formatSignatureLearnedAt(offer.pendingSignatureLearnedAt)}",
                    color = C.t3,
                    fontSize = 11.sp
                )
                if (preview.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = C.w04,
                        border = BorderStroke(1.dp, C.border)
                    ) {
                        Text(
                            "Last popup: $preview",
                            modifier = Modifier.padding(10.dp),
                            color = C.t1,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                if (pendingSteps.isNotEmpty() || pendingCaptures.isNotEmpty()) {
                    TextButton(
                        onClick = { showDetails = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Review Captured Steps", color = C.cyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRelearn,
                    border = BorderStroke(1.dp, C.amber.copy(alpha = 0.5f))
                ) {
                    Text("Relearn", color = C.amber, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = C.green)
                ) {
                    Text("Verify", color = C.bg, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later", color = C.t2) }
        }
    )

    if (showDetails) {
        SignatureLearningDetailsDialog(
            learnedSteps = pendingSteps,
            learningCaptures = pendingCaptures,
            learnedAt = offer.pendingSignatureLearnedAt,
            onDismiss = { showDetails = false }
        )
    }
}

@Composable
private fun OfferInfoTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), color = C.t3, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(value, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OfferCodeBlock(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = C.w04,
        border = BorderStroke(1.dp, C.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label.uppercase(), color = C.t3, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(value, color = C.t1, fontSize = 13.sp, lineHeight = 18.sp)
            Text("Use `pn` as the recipient number placeholder.", color = C.t3, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SignatureLearningDetailsDialog(
    learnedSteps: List<UssdSignatureStep>,
    learningCaptures: List<UssdLearningCapture>,
    learnedAt: Long,
    onDismiss: () -> Unit
) {
    val details = remember(learnedSteps, learningCaptures) {
        buildLearnedStepDetails(learnedSteps, learningCaptures)
    }

    AlertDialog(
        containerColor = C.card,
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Learned Steps", color = C.t1, fontWeight = FontWeight.Bold)
                Text(
                    "Saved on ${formatSignatureLearnedAt(learnedAt)}",
                    color = C.t2,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            if (details.isEmpty()) {
                Text("No learned steps are available yet.", color = C.t2, fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    details.forEach { detail ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = C.cardHi,
                            border = BorderStroke(1.dp, C.border)
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (detail.stepIndex >= 0) "Step ${detail.stepIndex + 1}" else "Final Popup",
                                    color = C.cyan,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                if (detail.menuTitle.isNotBlank()) {
                                    Text(
                                        "Menu: ${detail.menuTitle}",
                                        color = C.t2,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Text(
                                    "Chosen option: ${detail.selectedOptionLabel.ifBlank { "Not captured" }}",
                                    color = C.t1,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                                if (detail.enteredInput.isNotBlank()) {
                                    Text(
                                        "Sent input: ${detail.enteredInput}",
                                        color = C.t2,
                                        fontSize = 11.sp
                                    )
                                }
                                if (detail.menuOptionsSnapshot.isNotEmpty()) {
                                    Text(
                                        "Visible options: ${detail.menuOptionsSnapshot.joinToString(" | ")}",
                                        color = C.t3,
                                        fontSize = 10.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                                if (detail.recordedTexts.isNotEmpty()) {
                                    detail.recordedTexts.forEach { popupText ->
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            color = C.w04,
                                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f))
                                        ) {
                                            Text(
                                                popupText,
                                                modifier = Modifier.padding(10.dp),
                                                color = C.t1,
                                                fontSize = 11.sp,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "No recorded text was saved for this step.",
                                        color = C.t3,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = C.cyan) }
        }
    )
}

@Composable
private fun CompactDialogSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedTrackColor: Color,
    uncheckedThumbColor: Color = C.red
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 3.dp,
        animationSpec = tween(180),
        label = "compact_dialog_switch"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor.copy(alpha = 0.92f) else C.surface,
        label = "compact_dialog_switch_track"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor.copy(alpha = 0.34f) else uncheckedThumbColor.copy(alpha = 0.62f),
        label = "compact_dialog_switch_border"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) C.bg else uncheckedThumbColor,
        label = "compact_dialog_switch_thumb"
    )
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
private fun CompactDialogToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    checkedAccent: Color,
    uncheckedAccent: Color = C.red,
    badgeText: String? = null
) {
    val accent = if (checked) checkedAccent else uncheckedAccent
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (checked) checkedAccent.copy(alpha = 0.12f) else uncheckedAccent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                badgeText?.let {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = it.uppercase(Locale.getDefault()),
                            color = accent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Text(description, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
            }
            CompactDialogSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                checkedTrackColor = checkedAccent,
                uncheckedThumbColor = uncheckedAccent
            )
        }
    }
}

@Composable
private fun OfferDialogSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.cardHi.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
    ) {
        Column(
            Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(C.cardHi.copy(alpha = 0.98f), C.card.copy(alpha = 0.90f))
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(C.cyan)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            Divider(color = C.w08)
            content()
        }
    }
}

@Composable
private fun OfferStatusCard(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val badgeText = if (enabled) "Live" else "Paused"
    val description = if (enabled) {
        "Visible in console and available for matching."
    } else {
        "Hidden from matching until you turn it back on."
    }
    CompactDialogToggleCard(
        title = "Bundle status",
        description = description,
        checked = enabled,
        onCheckedChange = onCheckedChange,
        icon = if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,
        checkedAccent = C.green,
        uncheckedAccent = C.red,
        badgeText = badgeText
    )
}

@Composable
fun OfferDialog(
    existing: OfferItem?,
    onDismiss: () -> Unit,
    onSave: (OfferItem) -> Unit,
    onSaveAndLearn: (OfferItem) -> Unit,
    onApprovePending: (OfferItem) -> Unit,
    onRelearnSignature: (OfferItem) -> Unit
) {
    val configuration = LocalConfiguration.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var code by remember { mutableStateOf(existing?.ussdCode ?: "") }
    var price by remember { mutableStateOf(existing?.price?.toString() ?: "") }
    var mode by remember { mutableStateOf(existing?.executionMode ?: "ADVANCED") }
    var cat by remember { mutableStateOf(existing?.category ?: "Data") }
    var device by remember { mutableStateOf(existing?.targetDevice ?: "PRIMARY") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var signatureEnabled by remember { mutableStateOf(existing?.signatureDetectionEnabled ?: false) }
    var signatureAction by remember { mutableStateOf(existing?.signatureAction ?: "STOP") }
    var modeExp by remember { mutableStateOf(false) }
    var catExp by remember { mutableStateOf(false) }
    var devExp by remember { mutableStateOf(false) }
    var signatureExp by remember { mutableStateOf(false) }
    var showSignatureDetails by remember { mutableStateOf(false) }
    var showPendingSignatureDetails by remember { mutableStateOf(false) }
    val learnedSteps = existing?.learnedSignature.orEmpty()
    val learningCaptures = existing?.signatureLearningCaptures.orEmpty()
    val learnedAt = existing?.signatureLearnedAt ?: 0L
    val pendingLearnedSteps = existing?.pendingLearnedSignature.orEmpty()
    val pendingLearningCaptures = existing?.pendingSignatureLearningCaptures.orEmpty()
    val pendingLearnedAt = existing?.pendingSignatureLearnedAt ?: 0L
    val hasLearnedSignature = learnedSteps.isNotEmpty() || learningCaptures.isNotEmpty()
    val hasPendingSignature = pendingLearnedSteps.isNotEmpty() || pendingLearningCaptures.isNotEmpty()
    val canSave = remember(name, price, code) {
        name.isNotBlank() && code.isNotBlank() && (price.toIntOrNull() ?: 0) > 0
    }
    val maxDialogBodyHeight = (configuration.screenHeightDp.dp * 0.50f).coerceAtLeast(320.dp)

    fun buildOffer(): OfferItem? {
        val p = price.toIntOrNull() ?: 0
        if (name.isBlank() || p <= 0 || code.isBlank()) return null
        val codeChanged = existing?.ussdCode?.trim()?.equals(code.trim(), ignoreCase = true) == false
        return OfferItem(
            id = existing?.id ?: (System.currentTimeMillis() % 100000).toInt(),
            name = name.trim(),
            price = p,
            ussdCode = code.trim(),
            enabled = enabled,
            executionMode = mode,
            category = cat,
            targetDevice = device,
            signatureDetectionEnabled = signatureEnabled && mode == "ADVANCED",
            signatureAction = signatureAction,
            learnedSignature = if (codeChanged) emptyList() else existing?.learnedSignature.orEmpty(),
            signatureLearnedAt = if (codeChanged) 0L else (existing?.signatureLearnedAt ?: 0L),
            signatureLearningCaptures = if (codeChanged) emptyList() else existing?.signatureLearningCaptures.orEmpty(),
            pendingLearnedSignature = if (codeChanged) emptyList() else existing?.pendingLearnedSignature.orEmpty(),
            pendingSignatureLearnedAt = if (codeChanged) 0L else (existing?.pendingSignatureLearnedAt ?: 0L),
            pendingSignatureLearningCaptures = if (codeChanged) emptyList() else existing?.pendingSignatureLearningCaptures.orEmpty()
        )
    }

    AlertDialog(
        containerColor = C.surface,
        shape = RoundedCornerShape(28.dp),
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        if (existing != null) "BUNDLE SETTINGS" else "CREATE OFFER",
                        color = C.cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        if (existing != null) "Edit Bundle" else "New Bundle",
                        color = C.t1,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        lineHeight = 28.sp
                    )
                    Text(
                        "Update pricing, routing, and automation behavior for this bundle.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, null, tint = C.t2)
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogBodyHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OfferDialogSection(
                    title = "Bundle Status",
                    subtitle = "Control whether this bundle is active and available for automation."
                ) {
                    OfferStatusCard(enabled = enabled, onCheckedChange = { enabled = it })
                }

                OfferDialogSection(
                    title = "Bundle Details",
                    subtitle = "Enter the bundle name, customer price, and USSD code."
                ) {
                    DialogField("Bundle Name", name, { name = it }, "e.g. 1GB Daily Bundle")
                    DialogField("Price (KES)", price, { price = it }, "Amount customer pays", KeyboardType.Number)
                    DialogField("USSD Code", code, { code = it }, "Use 'pn' as phone placeholder")
                }

                OfferDialogSection(
                    title = "Execution Setup",
                    subtitle = "Choose how the bundle runs and which device handles it."
                ) {
                    DialogDropdown("Mode", mode, listOf("SIMPLE", "ADVANCED"), modeExp, { modeExp = !modeExp }) { mode = it; modeExp = false }
                    DialogDropdown("Category", cat, listOf("Data", "Minutes", "SMS", "Night", "Social"), catExp, { catExp = !catExp }) { cat = it; catExp = false }
                    DialogDropdown("Execute On", device, listOf("PRIMARY", "RELAY"), devExp, { devExp = !devExp }) { device = it; devExp = false }
                }

                OfferDialogSection(
                    title = "Bundle Preview",
                    subtitle = "Quick summary of how this bundle will be stored."
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compactPreview = maxWidth < 420.dp
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (compactPreview) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OfferInfoTile("Price", if (price.isBlank()) "Not set" else "KES $price", C.cyan, Modifier.weight(1f))
                                        OfferInfoTile("Mode", mode, C.purple, Modifier.weight(1f))
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OfferInfoTile("Category", cat, C.orange, Modifier.weight(1f))
                                        OfferInfoTile("Device", device, C.blue, Modifier.weight(1f))
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OfferInfoTile("Price", if (price.isBlank()) "Not set" else "KES $price", C.cyan, Modifier.weight(1f))
                                    OfferInfoTile("Mode", mode, C.purple, Modifier.weight(1f))
                                    OfferInfoTile("Category", cat, C.orange, Modifier.weight(1f))
                                    OfferInfoTile("Device", device, C.blue, Modifier.weight(1f))
                                }
                            }
                            OfferCodeBlock("USSD Preview", if (code.isBlank()) "No code entered yet" else code)
                        }
                    }
                }

                if (mode == "ADVANCED") {
                    OfferDialogSection(
                        title = "USSD Protection",
                        subtitle = "Advanced mode can learn live menu labels and safely react when network menus change."
                    ) {
                        CompactDialogToggleCard(
                            title = "Detect USSD code changes",
                            description = if (signatureEnabled) {
                                "Protection is on and this bundle can detect menu changes before executing."
                            } else {
                                "Turn this on to learn live menu labels and keep a reviewable step-by-step record."
                            },
                            checked = signatureEnabled,
                            onCheckedChange = { signatureEnabled = it },
                            icon = Icons.Outlined.Security,
                            checkedAccent = C.green,
                            uncheckedAccent = C.red,
                            badgeText = if (signatureEnabled) "Guard on" else "Guard off"
                        )
                        if (signatureEnabled) {
                            DialogDropdown(
                                "When codes change",
                                if (signatureAction == "ADJUST") "ADJUST" else "STOP",
                                listOf("STOP", "ADJUST"),
                                signatureExp,
                                { signatureExp = !signatureExp }
                            ) {
                                signatureAction = it
                                signatureExp = false
                            }
                            Text(
                                if (signatureAction == "ADJUST")
                                    "ADJUST only auto-fixes exact same-label moves. If the network changes wording, the app stops instead of guessing."
                                else
                                    "STOP is the recommended production setting. It prevents the app from choosing the wrong bundle when the menu looks different.",
                                color = C.t2,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                            if (hasPendingSignature) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = C.orangeDim,
                                    border = BorderStroke(1.dp, C.orange.copy(alpha = 0.24f))
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Approval Needed", color = C.orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            "A new learning run is waiting for approval. Review it before it replaces the saved signature.",
                                            color = C.t2,
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            MiniTag("NEW STEPS ${pendingLearnedSteps.size}", C.orange)
                                            MiniTag("NEW POPUPS ${pendingLearningCaptures.size}", C.amber)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { existing?.let(onRelearnSignature) },
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.5f))
                                            ) {
                                                Text("Relearn", color = C.amber, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = { existing?.let(onApprovePending) },
                                                shape = RoundedCornerShape(14.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = C.green)
                                            ) {
                                                Text("Approve", color = C.bg, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        TextButton(
                                            onClick = { showPendingSignatureDetails = true },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Review Pending Steps", color = C.cyan, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (hasLearnedSignature) Modifier.clickable { showSignatureDetails = true } else Modifier),
                                shape = RoundedCornerShape(16.dp),
                                color = C.w04,
                                border = BorderStroke(1.dp, C.border)
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Signature Learning", color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        if (learnedSteps.isNotEmpty() || learningCaptures.isNotEmpty())
                                            when {
                                                learnedSteps.isNotEmpty() ->
                                                    "Learned ${learnedSteps.size} menu step(s) and captured ${learningCaptures.size} popup(s) on ${formatSignatureLearnedAt(learnedAt)}."
                                                else ->
                                                    "Captured ${learningCaptures.size} popup(s) on ${formatSignatureLearnedAt(learnedAt)}."
                                            }
                                        else
                                            "No signature learned yet. Save this offer, then use Save & Learn to scan the live USSD menus with test number 0700000000.",
                                        color = C.t2,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MiniTag("STEPS ${learnedSteps.size}", C.green)
                                        MiniTag("POPUPS ${learningCaptures.size}", C.amber)
                                        MiniTag(if (signatureAction == "ADJUST") "AUTO ADJUST" else "STOP ONLY", if (signatureAction == "ADJUST") C.green else C.amber)
                                    }
                                    existing?.signatureLearningCaptures?.lastOrNull()?.let { capture ->
                                        val preview = capture.popupText.replace(Regex("\\s+"), " ").trim().take(140)
                                        if (preview.isNotBlank()) {
                                            Text(
                                                "Last popup: $preview",
                                                color = C.t3,
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                    if (hasLearnedSignature) {
                                        Text(
                                            "Tap to view every learned step, recorded text, and chosen option.",
                                            color = C.cyan,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp
                                        )
                                        TextButton(
                                            onClick = { showSignatureDetails = true },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("View Learned Steps", color = C.cyan, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactActions = maxWidth < 360.dp
                val showSaveAndLearn = mode == "ADVANCED" && signatureEnabled

                if (compactActions && showSaveAndLearn) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Cancel", color = C.t2, fontWeight = FontWeight.Medium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { buildOffer()?.let(onSaveAndLearn) },
                                enabled = canSave,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, C.green.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Icon(Icons.Outlined.AutoFixHigh, null, tint = C.green, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Save & Learn", color = C.green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Button(
                                onClick = { buildOffer()?.let(onSave) },
                                enabled = canSave,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = C.bg, modifier = Modifier.size(17.dp))
                                    Text("Save", color = C.bg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Cancel", color = C.t2, fontWeight = FontWeight.Medium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (showSaveAndLearn) {
                                OutlinedButton(
                                    onClick = { buildOffer()?.let(onSaveAndLearn) },
                                    enabled = canSave,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, C.green.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
                                ) {
                                    Icon(Icons.Outlined.AutoFixHigh, null, tint = C.green, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("Save & Learn", color = C.green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            Button(
                                onClick = { buildOffer()?.let(onSave) },
                                enabled = canSave,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = C.bg, modifier = Modifier.size(17.dp))
                                    Text("Save", color = C.bg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {}
    )

    if (showSignatureDetails) {
        SignatureLearningDetailsDialog(
            learnedSteps = learnedSteps,
            learningCaptures = learningCaptures,
            learnedAt = learnedAt,
            onDismiss = { showSignatureDetails = false }
        )
    }
    if (showPendingSignatureDetails) {
        SignatureLearningDetailsDialog(
            learnedSteps = pendingLearnedSteps,
            learningCaptures = pendingLearningCaptures,
            learnedAt = pendingLearnedAt,
            onDismiss = { showPendingSignatureDetails = false }
        )
    }
}

@Composable
fun PatternSettingsScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = C.bg,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("USSD Response Patterns") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Pattern Settings", color = C.t1)
        }
    }
}
