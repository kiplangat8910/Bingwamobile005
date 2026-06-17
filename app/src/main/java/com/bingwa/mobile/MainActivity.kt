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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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

private data class ConsoleSearchEntry(
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
            .background(Color(0xFF17181C))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Bingwa Mobile", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "Startup compatibility mode is active on this phone.",
                color = Color(0xFFD0D5DD),
                textAlign = TextAlign.Center
            )
            Text(
                "Detected issue: $errorLabel",
                color = Color(0xFF98A2B3),
                textAlign = TextAlign.Center,
                fontSize = 13.sp
            )
            Button(onClick = onRetry) { Text("Retry") }
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

private fun mergeConsoleSearchEntry(existing: ConsoleSearchEntry?, incoming: ConsoleSearchEntry): ConsoleSearchEntry {
    if (existing == null) return incoming
    val preferredName = choosePreferredClientName(existing.name, incoming.name)
    val preferredSource = when {
        existing.source == "Saved" || incoming.source != "Saved" -> existing.source
        else -> incoming.source
    }
    return ConsoleSearchEntry(
        name = preferredName,
        phone = existing.phone,
        source = preferredSource,
        lastSeen = maxOf(existing.lastSeen, incoming.lastSeen)
    )
}

private fun buildConsoleSearchEntries(
    context: Context,
    allTxns: List<Transaction>,
    smsContacts: List<SavedContact>
): List<ConsoleSearchEntry> {
    val merged = linkedMapOf<String, ConsoleSearchEntry>()

    fun addEntry(name: String, phone: String, source: String, lastSeen: Long = 0L) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return
        val incoming = ConsoleSearchEntry(
            name = formatClientName(name),
            phone = normalizedPhone,
            source = source,
            lastSeen = lastSeen
        )
        merged[normalizedPhone] = mergeConsoleSearchEntry(merged[normalizedPhone], incoming)
    }

    loadContacts(context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE))
        .forEach { addEntry(it.name, it.phone, "Saved") }
    smsContacts.forEach { addEntry(it.name, it.phone, "M-PESA SMS") }
    allTxns.forEach { tx ->
        addEntry(tx.clientName, tx.phoneNumber, "History", tx.timestamp)
    }

    return merged.values.sortedWith(
        compareByDescending<ConsoleSearchEntry> { it.lastSeen }
            .thenBy { it.name.ifBlank { it.phone }.lowercase(Locale.getDefault()) }
    )
}

private fun rankConsoleSearchEntries(query: String, entries: List<ConsoleSearchEntry>): List<ConsoleSearchEntry> {
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
            compareByDescending<Pair<ConsoleSearchEntry, Int>> { it.second }
                .thenByDescending { it.first.lastSeen }
                .thenBy { it.first.name.ifBlank { it.first.phone }.lowercase(Locale.getDefault()) }
        )
        .map { it.first }
        .take(6)
        .toList()
}

private fun autoMatchConsoleEntries(phone: String, entries: List<ConsoleSearchEntry>): List<ConsoleSearchEntry> {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (normalized.length < 3) return emptyList()

    return rankConsoleSearchEntries(phone, entries).sortedWith(
        compareByDescending<ConsoleSearchEntry> { SmsCommandHandler.normalizePhone(it.phone) == normalized }
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

private fun pluralize(value: Long, unit: String): String =
    if (value == 1L) "$value $unit" else "$value ${unit}s"

fun formatRemainingTimeHome(ms: Long): String {
    if (ms <= 0L) return "Expired"
    val totalMinutes = ms / 60_000L
    val days = totalMinutes / (24L * 60L)
    val hours = (totalMinutes % (24L * 60L)) / 60L
    val minutes = totalMinutes % 60L
    return "${pluralize(days, "day")} ${pluralize(hours, "hour")} ${pluralize(minutes, "minute")} left"
}

fun formatRemainingTimeDetailed(ms: Long): String {
    if (ms <= 0L) return "Expired"
    val totalSeconds = ms / 1_000L
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "${pluralize(days, "day")} ${pluralize(hours, "hour")} ${pluralize(minutes, "minute")} ${pluralize(seconds, "second")} remaining"
}

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
    val popupTexts: List<String>
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
            popupTexts = captures
                .map { it.popupText.trim() }
                .filter { it.isNotBlank() }
                .distinct()
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
    focusedBorderColor = C.cyan, unfocusedBorderColor = C.border,
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan,
    focusedContainerColor = C.cardHi.copy(alpha = 0.92f),
    unfocusedContainerColor = C.card.copy(alpha = 0.86f)
)

@Composable
fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.cyan, unfocusedBorderColor = C.border,
    focusedTextColor = C.t1, unfocusedTextColor = C.t1,
    cursorColor = C.cyan, focusedContainerColor = C.cardHi, unfocusedContainerColor = C.cardHi
)

@Composable
fun PageHeader(title: String, subtitle: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-12).dp)
                .size(92.dp)
                .background(
                    Brush.radialGradient(
                        listOf(C.cyan.copy(alpha = 0.18f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = C.cardHi.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                C.cyan.copy(alpha = 0.10f),
                                C.cardHi.copy(alpha = 0.98f),
                                C.blue.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = C.cyan.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.18f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(C.cyan)
                            )
                            Text(
                                "BINGWA MOBILE",
                                color = C.cyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }
                    Text(
                        title,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.8).sp,
                        color = C.t1
                    )
                    Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(C.surface.copy(alpha = 0.92f))
                        .border(1.dp, C.cyan.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AutoMode, null, tint = C.cyan, modifier = Modifier.size(24.dp))
                }
            }
        }
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
            Text(subtitle, color = C.t2, fontSize = 12.sp, lineHeight = 17.sp)
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
            Text(label, color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TokensHeroCard(
    balance: Int,
    activePlan: UnlimitedManager.Plan?,
    remainingMs: Long
) {
    val accent = if (activePlan != null) C.green else C.cyan
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
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.12f),
                            C.cardHi.copy(alpha = 0.98f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = accent.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
            ) {
                Text(
                    if (activePlan != null) "UNLIMITED ACCESS" else "TOKEN BALANCE",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp
                )
            }
            Text(
                if (activePlan != null) "Unlimited" else balance.toString(),
                fontSize = if (activePlan != null) 48.sp else 72.sp,
                fontWeight = FontWeight.Black,
                lineHeight = if (activePlan != null) 52.sp else 72.sp,
                color = if (activePlan != null) C.green else C.t1
            )
            Text(
                if (activePlan != null) "${activePlan.label} plan is active" else "Ready for your next USSD automation runs",
                color = C.t2,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (activePlan != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = C.blue.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, C.blue.copy(alpha = 0.22f))
                ) {
                    Text(
                        formatRemainingTimeDetailed(remainingMs),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = C.blue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PurchasePerkChip(Icons.Outlined.Bolt, "1 token = 1 USSD call", C.cyan)
                PurchasePerkChip(Icons.Outlined.Shield, "Balance never expires", C.green)
                PurchasePerkChip(Icons.Outlined.Refresh, "Airtime top-up flow", C.blue)
            }
        }
    }
}

@Composable
fun FieldLabel(text: String) = Text(
    text, color = C.t2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 6.dp)
)

@Composable
private fun ConsoleSectionCard(
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
        shape = RoundedCornerShape(20.dp),
        color = C.cardHi.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                        Text(title, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
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
private fun ConsoleHeroCard(
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
    Surface(
        color = C.cardHi.copy(alpha = 0.9f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.18f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    Text("Ready to dispatch", color = C.t1, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Simple manual dispatch with quick customer matching and clear status feedback.",
                        color = C.t2,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .border(1.dp, statusColor.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
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
                ConsoleQuickStat("Enabled offers", enabledOfferCount.toString(), C.cyan)
                ConsoleQuickStat("Directory", directoryCount.toString(), C.green)
                ConsoleQuickStat("History", historyCount.toString(), C.blue)
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = statusColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.20f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        when {
                            bannerState == "pending" -> "Dispatch in progress"
                            bannerState == "success" -> "Last dispatch completed successfully"
                            bannerState == "failed" -> "Last dispatch failed"
                            bannerState == "relayed" -> "Request forwarded to relay"
                            dispatchReady -> "Ready to execute dispatch"
                            smsSearchLoading -> "Refreshing smart match directory"
                            else -> "Enter customer details to prepare dispatch"
                        },
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleQuickStat(label: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = C.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RowScope.ConsoleTabChip(
    text: String,
    selected: Boolean,
    icon: ImageVector,
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
        if (selected) C.cyan.copy(alpha = 0.22f) else Color.Transparent,
        label = "console_tab_border"
    )
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Text(text, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
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
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        C.cardHi.copy(alpha = 0.96f),
                        C.card.copy(alpha = 0.94f),
                        C.surface.copy(alpha = 0.98f)
                    )
                )
            )
            .border(1.dp, C.border.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                title.uppercase(),
                color = C.cyan.copy(alpha = 0.92f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.18f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(C.cyan, C.blue)))
            )
        }
        Divider(color = C.w08)
        content()
    }
}

@Composable
fun GroupDivider() = Divider(
    color = C.border.copy(alpha = 0.55f),
    thickness = 0.6.dp,
    modifier = Modifier.padding(horizontal = 18.dp)
)

@Composable
fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(sub, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
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
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(sub, color = C.t2, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = C.surface.copy(alpha = 0.82f),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f))
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = C.t2, modifier = Modifier.size(14.dp))
            }
        }
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
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowIcon(Icons.Rounded.SimCard)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                if (current == -1) "Default SIM" else sims.find { it.subscriptionId == current }?.displayName?.toString() ?: "SIM $current",
                color = C.t2, fontSize = 11.sp, lineHeight = 16.sp
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
            shape = RoundedCornerShape(16.dp),
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
                .clip(RoundedCornerShape(16.dp))
                .background(C.cardHi)
                .border(1.dp, if (expanded) C.cyan.copy(alpha = 0.55f) else C.border, RoundedCornerShape(16.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 13.dp)
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
            delay(30_000L)
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
                when (s) {
                    Screen.Home     -> HomeScreenVolcanic(
                        tokenBal = tokenBal,
                        airBal = airBal,
                        isRefreshing = isRefreshing,
                        txns = txns,
                        running = running,
                        unlimitedLabel = unlimitedLabel,
                        unlimitedRemaining = unlimitedLabel?.let { formatRemainingTimeHome(remainingMs) },
                        onRefresh = {
                            if (!isRefreshing) {
                                isRefreshing = true
                                if (!requestBalanceCheckSafely(ctx)) isRefreshing = false
                            }
                        }
                    )
                    Screen.Console  -> ConsoleScreen(txns)
                    Screen.Tokens   -> TokensScreen()
                    Screen.Contacts -> ContactsScreen()
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

// ─── Bottom Navigation Bar ───────────────────────────────────────────────
@Composable
private fun VolcanicNavBar(current: Screen, running: Boolean, onSelect: (Screen) -> Unit, onToggleRunning: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = C.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.86f)),
        shadowElevation = 14.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)
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
                    .height(98.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NAV_ITEMS.take(2).forEach { item ->
                        NavBarItemButton(item, current == item) { onSelect(item) }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(92.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    StartNavButton(running = running, onClick = onToggleRunning)
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NAV_ITEMS.drop(2).forEach { item ->
                        NavBarItemButton(item, current == item) { onSelect(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBarItemButton(item: Screen, selected: Boolean, onClick: () -> Unit) {
    val selectedTint = C.amber
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (selected) C.amber.copy(alpha = 0.14f) else C.surface.copy(alpha = 0.28f),
            border = if (selected) BorderStroke(1.dp, C.amber.copy(alpha = 0.34f)) else BorderStroke(1.dp, Color.Transparent)
        ) {
            Box(
                modifier = Modifier.size(width = 44.dp, height = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (selected) item.iconSel else item.icon,
                    null,
                    tint = if (selected) selectedTint else C.t3,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            item.label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) selectedTint else C.t3,
            maxLines = 1
        )
    }
}

@Composable
private fun StartNavButton(running: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val color = if (running) C.red else C.green
    val holdAction = {
        vib(ctx, 70L)
        Toast.makeText(
            ctx,
            if (running) "Stopping automation..." else "Starting automation...",
            Toast.LENGTH_SHORT
        ).show()
        onClick()
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = C.cardHi,
            border = BorderStroke(2.dp, color.copy(alpha = 0.85f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .size(62.dp)
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
                    Modifier
                        .matchParentSize()
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.14f))
                )
                Icon(
                    Icons.Outlined.PowerSettingsNew,
                    null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = C.surface,
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
        ) {
            Text(
                if (running) "Hold Stop" else "Hold Start",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
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

@Composable
private fun RelayHotspotStatusChip() {
    val ctx = LocalContext.current
    LaunchedEffect(ctx) { RelayManager.load(ctx) }
    val cfg by RelayManager.configState.collectAsState()
    if (!cfg.enabled || cfg.role != "PRIMARY" || cfg.method != "HOTSPOT") return

    val s by RelayManager.hotspotState.collectAsState()
    val (label, color, icon) = when (s) {
        RelayManager.HotspotLinkState.CONNECTED -> Triple("RELAY CONNECTED", C.green, Icons.Rounded.Wifi)
        RelayManager.HotspotLinkState.CHECKING -> Triple("CHECKING RELAY", C.amber, Icons.Rounded.Wifi)
        RelayManager.HotspotLinkState.DISCONNECTED -> Triple("RELAY DISCONNECTED", C.red, Icons.Rounded.WifiOff)
        else -> return
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, letterSpacing = 0.9.sp)
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
    onRefresh: () -> Unit
) {
    val ctx = LocalContext.current
    val automatedTxns = txns.filter { it.showInRecent }.sortedByDescending { it.timestamp }
    var selectedTxId by rememberSaveable { mutableIntStateOf(-1) }
    val selectedTx = automatedTxns.firstOrNull { it.id == selectedTxId }
    val sent = automatedTxns.count { it.status == TransactionStatus.SUCCESS.value }
    val pending = automatedTxns.count {
        it.status == TransactionStatus.PENDING.value || it.status == TransactionStatus.PROCESSING.value
    }
    val failed = automatedTxns.count { it.status == TransactionStatus.FAILED.value }
    val rate = if (automatedTxns.isNotEmpty()) (sent * 100) / automatedTxns.size else 0
    val inf = rememberInfiniteTransition(label = "home_inf")
    val spin by inf.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "spin"
    )
    val pulse by inf.animateFloat(
        1f,
        1.24f,
        infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulse"
    )
    val primaryStatusColor = if (running) C.green else C.t3

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            Modifier
                .size(320.dp)
                .offset((-120).dp, (-60).dp)
                .background(Brush.radialGradient(listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(70.dp, (-20).dp)
                .background(Brush.radialGradient(listOf(C.blue.copy(alpha = 0.07f), Color.Transparent)), CircleShape)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Bingwa Mobile",
                        color = C.t1,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1.0).sp
                    )
                    Text(
                        "USSD Automation Platform",
                        color = C.t2,
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                Brush.horizontalGradient(
                                    if (running) {
                                        listOf(
                                            C.green.copy(alpha = 0.26f),
                                            C.cyan.copy(alpha = 0.18f),
                                            C.surface.copy(alpha = 0.95f)
                                        )
                                    } else {
                                        listOf(
                                            C.surface.copy(alpha = 0.96f),
                                            C.card.copy(alpha = 0.92f)
                                        )
                                    }
                                )
                            )
                            .border(
                                1.dp,
                                if (running) C.green.copy(alpha = 0.45f) else C.border.copy(alpha = 0.85f),
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                                if (running) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .scale(pulse * 1.9f)
                                            .clip(CircleShape)
                                            .background(C.green.copy(alpha = 0.24f))
                                    )
                                }
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) C.green else primaryStatusColor))
                            }
                            Text(
                                if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE",
                                color = if (running) C.green else primaryStatusColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                    RelayHotspotStatusChip()
                }
            }
            item {
                VolcanicBalanceCard(
                    airBal = airBal,
                    tokenBal = tokenBal,
                    unlimitedLabel = unlimitedLabel,
                    unlimitedRemaining = unlimitedRemaining,
                    sent = sent,
                    pending = pending,
                    failed = failed,
                    rate = rate,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    spin = spin
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(28.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Brush.verticalGradient(listOf(C.amber, C.blue)))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Recent Activity", color = C.t1, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                    PillBadge(
                        if (automatedTxns.isEmpty()) "0 automated" else "${automatedTxns.size} automated",
                        C.t2
                    )
                }
            }
            if (automatedTxns.isEmpty()) {
                item { AnimatedEmptyState() }
            } else {
                items(automatedTxns.take(10), key = { it.id }) { tx ->
                    GithubActivityCard(
                        tx = tx,
                        onClick = { selectedTxId = tx.id }
                    ) {
                        txns.remove(tx)
                        saveTransactions(ctx, txns.toList())
                        if (selectedTxId == tx.id) selectedTxId = -1
                    }
                }
            }
        }

        if (selectedTx != null) {
            RecentTransactionDetailsDialog(
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
                    caption = unlimitedRemaining ?: "Available units",
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
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                caption,
                color = C.t3,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
            Text("Scanning for activity", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Transactions will appear here after you start automation from the center button.",
                color = C.t2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
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
    TX_SOURCE_CONSOLE -> "Console"
    TX_SOURCE_SMS_COMMAND -> "SMS Command"
    TX_SOURCE_AIRTIME -> "Airtime"
    else -> "Activity"
}

private fun transactionTypeColor(tx: Transaction): Color = when (tx.source) {
    TX_SOURCE_AUTOMATED -> C.green
    TX_SOURCE_CONSOLE -> C.purple
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
    val totalSeconds = durationMs / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
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
private fun GithubActivityCard(tx: Transaction, onClick: () -> Unit, onDelete: () -> Unit) {
    val statusColor = transactionStatusColor(tx)
    val typeColor = transactionTypeColor(tx)
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

    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            statusColor.copy(alpha = 0.08f),
                            C.card,
                            C.surface.copy(alpha = 0.82f)
                        )
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .border(1.dp, statusColor.copy(alpha = 0.24f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
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
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Bought ${tx.description.ifBlank { "Offer not captured" }}",
                        color = C.t2,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tx.amount.ifBlank { "-" }, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
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
                    "Tap for full execution details",
                    color = C.t3,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Outlined.KeyboardArrowRight, null, tint = C.t3, modifier = Modifier.size(18.dp))
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
    rate: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    spin: Float
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(surfaceGradient())
            .border(1.dp, C.borderHi, RoundedCornerShape(20.dp)).padding(18.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(C.blue))
                        Spacer(Modifier.width(5.dp))
                        Text("AIRTIME BALANCE", color = C.t2, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Text(
                            airBal.ifBlank { "—" },
                            modifier = Modifier.weight(1f),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 28.sp,
                            color = C.t1,
                            maxLines = 2,
                            overflow = TextOverflow.Clip
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Refresh, null, tint = if (isRefreshing) C.blue else C.t3,
                            modifier = Modifier.size(16.dp).then(if (isRefreshing) Modifier.graphicsLayer { rotationZ = spin } else Modifier).clickable { onRefresh() })
                    }
                    Text(if (isRefreshing) "refreshing…" else "tap card to refresh", color = C.t3, fontSize = 10.sp)
                }
                Box(Modifier.width(1.dp).height(56.dp).background(C.w08).padding(horizontal = 14.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TOKENS", color = C.t2, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (unlimitedLabel != null) C.green else C.cyan))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (unlimitedLabel != null) {
                        Text("Unlimited", fontSize = 24.sp, fontWeight = FontWeight.Black, color = C.green, maxLines = 1)
                        Text(unlimitedRemaining ?: "", color = C.t3, fontSize = 10.sp, maxLines = 1)
                    } else {
                        Text("$tokenBal", fontSize = 28.sp, fontWeight = FontWeight.Black, color = C.t1)
                        Text("units", color = C.t3, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Divider(color = C.w08)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                StatCell("$sent", "SENT", C.green)
                Box(Modifier.width(1.dp).height(32.dp).background(C.w08))
                StatCell("$pending", "PENDING", C.amber)
                Box(Modifier.width(1.dp).height(32.dp).background(C.w08))
                StatCell("$failed", "FAILED", C.red)
                Box(Modifier.width(1.dp).height(32.dp).background(C.w08))
                DonutRing(rate, C.green, 48.dp, 5.dp)
            }
        }
    }
}

@Composable
fun StatCell(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 9.sp, color = C.t3, letterSpacing = 0.8.sp)
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
        TX_SOURCE_CONSOLE -> "Console"
        TX_SOURCE_SMS_COMMAND -> "SMS Command"
        TX_SOURCE_AIRTIME -> "Airtime"
        else -> "Activity"
    }
    val typeColor = when (typeLabel) {
        "Automated" -> C.green
        "Console" -> C.purple
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
fun AnimatedEmptyState() {
    val anim = rememberInfiniteTransition(label = "empty_anim")
    val t by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    val t2 by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "t2")
    val t3 by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t3")
    val scan by anim.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "scan")

    val dots = remember {
        listOf(
            Triple(0.12f, 0.20f, 6.dp),
            Triple(0.80f, 0.30f, 4.dp),
            Triple(0.55f, 0.65f, 5.dp),
            Triple(0.25f, 0.70f, 3.dp),
            Triple(0.70f, 0.15f, 4.dp)
        )
    }

    Box(
        Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(C.card)
            .border(1.dp, C.border.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.matchParentSize()) {
            dots.forEachIndexed { idx, d ->
                val phase = when (idx) { 0 -> t; 1 -> t2; 2 -> t3; 3 -> t2; else -> t }
                val yOff = (kotlin.math.sin((phase * 2f * Math.PI).toFloat()) * 10f)
                val a = 0.15f + 0.10f * kotlin.math.abs(kotlin.math.cos((phase * 2f * Math.PI).toFloat()))
                Box(
                    Modifier
                        .offset(x = (d.first * 100).dp, y = (d.second * 100).dp + yOff.dp)
                        .size(d.third)
                        .clip(CircleShape)
                        .background(C.green.copy(alpha = a))
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    fun ring(p: Float, delay: Float) {
                        val x = ((p + delay) % 1f)
                        val scale = 0.7f + 0.8f * x
                        val alpha = (1f - x).coerceIn(0f, 1f) * 0.35f
                        val r = (size.minDimension / 2f) * scale
                        drawCircle(
                            color = C.green.copy(alpha = alpha),
                            radius = r,
                            style = Stroke(width = 2f)
                        )
                    }
                    ring(t, 0f)
                    ring(t, 0.23f)
                    ring(t, 0.46f)
                }
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(C.greenDim)
                        .border(1.5.dp, C.green.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val coreAlpha = 0.55f + 0.35f * kotlin.math.abs(kotlin.math.sin((t * 2f * Math.PI).toFloat()))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.green.copy(alpha = coreAlpha)))
                }
            }

            Box(
                Modifier.fillMaxWidth(0.82f).height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, C.green.copy(alpha = 0.35f), Color.Transparent)))
                    .clip(RoundedCornerShape(1.dp))
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(0.25f)
                        .offset(x = (scan * 100).dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, C.green.copy(alpha = 0.85f), Color.Transparent)))
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Scanning for activity…",
                    color = C.t2,
                    fontSize = 11.sp,
                    letterSpacing = 0.6.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Transactions will appear here",
                    color = C.t3,
                    fontSize = 10.sp,
                    letterSpacing = 0.2.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ─── Console Screen ──────────────────────────────────────────────────────
@Composable
fun ConsoleScreen(allTxns: MutableList<Transaction>) {
    val ctx = LocalContext.current
    var offers by remember { mutableStateOf(OfferRepository.load(ctx).toList()) }
    var phone by remember { mutableStateOf("") }
    var phoneErr by remember { mutableStateOf<String?>(null) }
    var selOffer by remember { mutableStateOf(offers.firstOrNull { it.enabled }) }
    var mode by remember { mutableStateOf("ADVANCED") }
    var offerExp by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var bannerState by remember { mutableStateOf<String?>(null) }
    var pendingTxId by remember { mutableIntStateOf(-1) }
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
    val history by remember {
        derivedStateOf { allTxns.sortedByDescending { it.timestamp } }
    }
    val consoleDirectory by remember(ctx, smsSearchContacts) {
        derivedStateOf { buildConsoleSearchEntries(ctx, allTxns.toList(), smsSearchContacts) }
    }
    val normalizedPhone = remember(phone) { SmsCommandHandler.normalizePhone(phone) }
    val phoneMatches by remember(phone, consoleDirectory) {
        derivedStateOf { autoMatchConsoleEntries(phone, consoleDirectory) }
    }
    val exactPhoneMatch = remember(normalizedPhone, phoneMatches) {
        phoneMatches.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
    }
    val resolvedClientName = exactPhoneMatch?.name?.takeIf { it.isNotBlank() } ?: fallbackResolvedClientName
    val suggestedMatches = remember(phoneMatches, exactPhoneMatch) {
        phoneMatches.filterNot { it.phone == exactPhoneMatch?.phone }.take(3)
    }
    val dispatchReady = remember(phone, selOffer) {
        phone.matches(Regex("^[0-9]{10}$")) && selOffer != null
    }
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

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("Console", "Simple manual dispatch")
            ConsoleHeroCard(
                dispatchReady = dispatchReady,
                bannerState = bannerState,
                enabledOfferCount = enabledOffers.size,
                directoryCount = consoleDirectory.size,
                historyCount = history.size,
                smsSearchLoading = smsSearchLoading
            )
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.card)
                    .border(1.dp, C.border.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(4.dp)
            ) {
                ConsoleTabChip("Dispatch", tab == 0, Icons.Filled.Send) { tab = 0 }
                ConsoleTabChip("History", tab == 1, Icons.Outlined.History) { tab = 1 }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "console_tab_content"
            ) { currentTab ->
                if (currentTab == 0) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (bannerState) {
                            "success" -> FeedbackBanner("✓  Bundle dispatched successfully", C.green)
                            "failed"  -> FeedbackBanner("✗  Dispatch failed — check USSD logs", C.red)
                            "pending" -> FeedbackBanner("…  Dispatching — awaiting USSD response…", C.amber)
                            "relayed" -> FeedbackBanner("→  Forwarded to Relay phone for execution", C.blue)
                        }
                        ConsoleSectionCard(
                            title = "Step 1 · Customer",
                            subtitle = "Enter the phone number and use smart matching to confirm the customer.",
                            accent = if (resolvedClientName.isNotBlank()) C.green else C.cyan,
                            icon = Icons.Outlined.Badge,
                            highlighted = phone.isNotBlank() || resolvedClientName.isNotBlank()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PillBadge(if (phone.isBlank()) "Waiting for phone" else "Phone captured", C.cyan)
                                if (resolvedClientName.isNotBlank()) PillBadge("Matched customer", C.green)
                                if (smsSearchLoading) PillBadge("Refreshing matches", C.blue)
                            }
                            FieldLabel("Customer Phone")
                            OutlinedTextField(
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
                                placeholder = { Text("0712345678", color = C.t3) },
                                leadingIcon = { Icon(Icons.Filled.Phone, null, tint = if (phone.isNotEmpty()) C.cyan else C.t2) },
                                trailingIcon = if (phone.isNotBlank()) ({
                                    IconButton(onClick = { phone = ""; phoneErr = null }) {
                                        Icon(Icons.Filled.Clear, null, tint = C.t2, modifier = Modifier.size(16.dp))
                                    }
                                }) else null,
                                isError = phoneErr != null,
                                supportingText = {
                                    when {
                                        phoneErr != null -> Text(phoneErr ?: "", color = C.red)
                                        exactPhoneMatch != null -> Text("Matched from ${exactPhoneMatch?.source}", color = C.green)
                                        smsSearchLoading -> Text("Checking saved contacts and M-PESA history…", color = C.t2)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                            AnimatedVisibility(visible = resolvedClientName.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = C.greenDim,
                                    border = BorderStroke(1.dp, C.green.copy(alpha = 0.24f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Outlined.Badge, null, tint = C.green, modifier = Modifier.size(16.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("Matched Customer", color = C.green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                                            Text(resolvedClientName, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            if (resolvedClientName.isBlank() && suggestedMatches.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Suggested matches", color = C.t2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        suggestedMatches.forEach { entry ->
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = C.surface,
                                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
                                                modifier = Modifier.clickable {
                                                    phone = entry.phone
                                                    phoneErr = null
                                                    bannerState = null
                                                }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(entry.name.ifBlank { "Unnamed customer" }, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                    Text("${entry.phone} • ${entry.source}", color = C.t3, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ConsoleSectionCard(
                            title = "Step 2 · Bundle",
                            subtitle = "Select the offer and choose how the request should run.",
                            accent = C.cyan,
                            icon = Icons.Filled.Wifi,
                            highlighted = selOffer != null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PillBadge("${enabledOffers.size} enabled offers", C.cyan)
                                selOffer?.let { PillBadge("KES ${it.price}", C.green) }
                                PillBadge(mode, if (mode == "SIMPLE") C.blue else C.cyan)
                            }
                            FieldLabel("Bundle")
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(C.surface)
                                    .border(1.dp, C.border, RoundedCornerShape(16.dp))
                                    .clickable { offerExp = true }
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.cyanDim), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Wifi, null, tint = C.cyan, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(selOffer?.name ?: "Choose a bundle", color = if (selOffer != null) C.t1 else C.t2, fontWeight = FontWeight.SemiBold)
                                        selOffer?.let { selected ->
                                            Text("KES ${selected.price}  ·  ${selected.executionMode}", color = C.cyan, fontSize = 12.sp)
                                        } ?: Text("Select an enabled offer to continue", color = C.t3, fontSize = 12.sp)
                                    }
                                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = C.t2)
                                }
                                DropdownMenu(
                                    expanded = offerExp,
                                    onDismissRequest = { offerExp = false },
                                    modifier = Modifier.background(C.cardHi, RoundedCornerShape(14.dp)).border(1.dp, C.border, RoundedCornerShape(14.dp))
                                ) {
                                    enabledOffers.forEach { o ->
                                        DropdownMenuItem(
                                            text = {
                                                Row {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(o.name, color = C.t1)
                                                        Text("KES ${o.price}", color = C.cyan)
                                                    }
                                                }
                                            },
                                            onClick = { selOffer = o; mode = o.executionMode; offerExp = false }
                                        )
                                    }
                                }
                            }
                            FieldLabel("Execution mode")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(C.surface)
                                    .border(1.dp, C.border, RoundedCornerShape(16.dp))
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("SIMPLE" to Icons.Filled.FlashOn, "ADVANCED" to Icons.Outlined.AutoMode).forEach { (m, ic) ->
                                    val active = mode == m
                                    val modeBg by animateColorAsState(if (active) C.cyan else Color.Transparent, label = "console_mode_bg")
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(modeBg)
                                            .clickable { mode = m }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(ic, null, tint = if (active) C.bg else C.t2, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(m, color = if (active) C.bg else C.t2, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = C.surface,
                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
                            ) {
                                Text(
                                    if (mode == "SIMPLE") "Silent execution with no popup interaction required." else "Automatic popup navigation using Accessibility for stronger delivery.",
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                        ConsoleSectionCard(
                            title = "Step 3 · Dispatch",
                            subtitle = "Review the selection, then send the request.",
                            accent = C.green,
                            icon = Icons.Filled.Send,
                            highlighted = dispatchReady || bannerState == "pending"
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                selOffer?.let { PillBadge("KES ${it.price}", C.green) }
                                if (phone.isNotBlank()) PillBadge(phone, C.cyan)
                                if (resolvedClientName.isNotBlank()) PillBadge(resolvedClientName, C.green)
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = C.surface.copy(alpha = 0.86f),
                                border = BorderStroke(1.dp, C.border.copy(alpha = 0.78f))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(selOffer?.name ?: "No bundle selected", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (resolvedClientName.isNotBlank()) "$resolvedClientName • ${phone.ifBlank { "No phone entered" }}" else phone.ifBlank { "Enter customer phone to continue" },
                                            color = C.t2,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                    selOffer?.let {
                                        PillBadge(mode, if (mode == "SIMPLE") C.blue else C.cyan)
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val selectedOffer = selOffer
                                phoneErr = when {
                                    phone.isBlank() -> "Phone number required"
                                    !phone.matches(Regex("^[0-9]{10}$")) -> "Must be exactly 10 digits"
                                    selectedOffer == null -> "Choose a bundle"
                                    else -> null
                                }
                                if (phoneErr == null && selectedOffer != null) {
                                    vib(ctx, 70L)
                                    if (RelayManager.shouldRelayOffer(ctx, selectedOffer)) {
                                        val sent = RelayManager.forwardBuyAmount(ctx, phone, selectedOffer.price)
                                        bannerState = if (sent) "relayed" else "failed"
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
                                            source = TX_SOURCE_CONSOLE,
                                            showInRecent = false,
                                            offerId = selectedOffer.id
                                        )
                                        pendingTxId = txId
                                        ctx.startOfferAutomation(selectedOffer, phone, txId, finalCode, mode)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .graphicsLayer {
                                    val scale = if (bannerState == "pending") pendingButtonScale else 1f
                                    scaleX = scale
                                    scaleY = scale
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.cyan),
                            enabled = selOffer != null
                        ) {
                            Icon(Icons.Filled.Send, null, tint = C.bg, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (bannerState == "pending") "DISPATCHING…" else "SEND REQUEST",
                                color = C.bg,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                } else {
                    if (history.isEmpty()) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            ConsoleSectionCard(
                                title = "Dispatch History",
                                subtitle = "Executed console transactions will appear here once activity starts.",
                                accent = C.blue,
                                icon = Icons.Outlined.History,
                                highlighted = false
                            ) {
                                AnimatedEmptyState()
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                ConsoleSectionCard(
                                    title = "Dispatch History",
                                    subtitle = "Review recent manual activity and open any transaction for detailed logs.",
                                    accent = C.blue,
                                    icon = Icons.Outlined.History,
                                    highlighted = true
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PillBadge("${history.size} total", C.blue)
                                        PillBadge("${history.count { it.statusEnum == TransactionStatus.SUCCESS }} success", C.green)
                                        PillBadge("${history.count { it.statusEnum == TransactionStatus.FAILED }} failed", C.red)
                                    }
                                }
                            }
                            items(history, key = { it.id }) { tx ->
                                VolcanicTxCard(tx) {
                                    allTxns.remove(tx)
                                    saveTransactions(ctx, allTxns.toList())
                                }
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
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
            delay(if (remMs > 0L) 1_000L else 15_000L)
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
                .background(Brush.radialGradient(listOf(C.cyan.copy(alpha = 0.10f), Color.Transparent)), CircleShape)
        )
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(90.dp, 90.dp)
                .background(Brush.radialGradient(listOf(C.blue.copy(alpha = 0.08f), Color.Transparent)), CircleShape)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeader("Tokens", "Purchase tokens and plans for faster automation")
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TokensHeroCard(balance = bal, activePlan = activePlan, remainingMs = remMs)

                SectionHeader(
                    title = "Top Up",
                    subtitle = "Choose a token pack when you want flexible pay-as-you-go usage.",
                    accent = C.cyan
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    packs.forEach { p ->
                        TokenTopUpCard(p) { confirm = p.ksh }
                    }
                }

                SectionHeader(
                    title = "Unlimited",
                    subtitle = "Best for high-volume usage with active time-based access.",
                    accent = C.blue
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    UnlimitedManager.PLANS.forEach { plan ->
                        UnlimitedPlanCard(plan) { confirm = plan.ksh }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    confirm?.let { amount ->
        val plan = UnlimitedManager.planForAmount(amount)
        val units = if (plan == null) TokenManager.convertAmountToTokens(amount) else 0
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm Purchase", color = C.t1) },
            text = {
                Text(
                    if (plan != null) "Use KSh $amount airtime to activate unlimited ${plan.label.lowercase()} access?"
                    else "Use KSh $amount airtime to receive $units tokens?",
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
                                else Toast.makeText(ctx, "Airtime used successfully. $units tokens were added.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, info ?: "Token purchase failed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Confirm", color = C.cyan)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = C.t2) } }
        )
    }
}

private data class TokenTopUp(val tokens: Int, val ksh: Int, val popular: Boolean = false)

@Composable
private fun TokenTopUpCard(p: TokenTopUp, onBuy: () -> Unit) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (p.popular) C.cyan.copy(alpha = 0.22f) else C.border.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        if (p.popular) {
                            listOf(C.cyan.copy(alpha = 0.10f), C.card, C.surface.copy(alpha = 0.92f))
                        } else {
                            listOf(C.card, C.surface.copy(alpha = 0.92f))
                        }
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (p.popular) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = C.cyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.22f))
                    ) {
                        Text("POPULAR", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = C.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${p.tokens}", color = C.t1, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Text("tokens", color = C.t2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text("KSh ${p.ksh}", color = if (p.popular) C.cyan else C.t3, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTag("Instant top-up", C.green)
                    MiniTag("Flexible usage", C.blue)
                }
            }

            Button(
                onClick = onBuy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (p.popular) C.cyan else C.cardHi,
                    contentColor = if (p.popular) C.bg else C.t1
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Icon(Icons.Outlined.Bolt, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("Buy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun UnlimitedPlanCard(plan: UnlimitedManager.Plan, onBuy: () -> Unit) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, C.blue.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(C.blue.copy(alpha = 0.10f), C.card, C.surface.copy(alpha = 0.92f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unlimited", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Surface(shape = RoundedCornerShape(999.dp), color = C.blue.copy(alpha = 0.14f), border = BorderStroke(1.dp, C.blue.copy(alpha = 0.22f))) {
                        Text(plan.label.uppercase(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = C.blue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
                Text("KSh ${plan.ksh}", color = C.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTag("Heavy usage", C.green)
                    MiniTag("Time-based access", C.blue)
                }
            }

            Button(
                onClick = onBuy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.blue, contentColor = C.bg),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Icon(Icons.Outlined.Shield, null, modifier = Modifier.size(15.dp))
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
                ToggleRow(Icons.Rounded.PlayArrow, "Vibrate on Dispatch", "Haptic feedback on console send", vibExecute) { vibExecute = it; prefs.edit().putBoolean("vibration_on_execute", it).apply() }
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
                OfferCard(o,
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
fun OfferCard(o: OfferItem, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
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
                    Text(o.name, color = if (o.enabled) C.t1 else C.t2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                Text("Approve Learned Signature", color = C.t1, fontWeight = FontWeight.Bold)
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
                    "The app learned ${pendingSteps.size} menu step(s) and captured ${pendingCaptures.size} popup(s). Approve to replace the saved signature, or relearn if anything looks wrong.",
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
                    Text("Approve", color = C.bg, fontWeight = FontWeight.Bold)
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
                                if (detail.popupTexts.isNotEmpty()) {
                                    detail.popupTexts.forEach { popupText ->
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
                                        "No captured text was saved for this step.",
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
private fun OfferDialogSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = C.cardHi.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .width(4.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(C.cyan)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
    val accent = if (enabled) C.cyan else C.t3
    val badgeText = if (enabled) "Live" else "Paused"
    val description = if (enabled) {
        "Visible in console and available for matching."
    } else {
        "Hidden from matching until you turn it back on."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) C.cyanDim.copy(alpha = 0.18f) else C.w04,
        border = BorderStroke(1.dp, if (enabled) C.cyan.copy(alpha = 0.35f) else C.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.PauseCircle,
                    contentDescription = null,
                    tint = accent
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bundle status", color = C.t1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            badgeText.uppercase(Locale.getDefault()),
                            color = accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Text(description, color = C.t2, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = C.cyan,
                    checkedThumbColor = C.bg,
                    uncheckedTrackColor = C.border
                )
            )
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    fontSize = 28.sp,
                    lineHeight = 32.sp
                )
                Text(
                    "Update pricing, routing, and automation behavior for this bundle.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OfferInfoTile("Price", if (price.isBlank()) "Not set" else "KES $price", C.cyan, Modifier.weight(1f))
                        OfferInfoTile("Mode", mode, C.purple, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OfferInfoTile("Category", cat, C.orange, Modifier.weight(1f))
                        OfferInfoTile("Device", device, C.blue, Modifier.weight(1f))
                    }
                    OfferCodeBlock("USSD Preview", if (code.isBlank()) "No code entered yet" else code)
                }

                if (mode == "ADVANCED") {
                    OfferDialogSection(
                        title = "USSD Protection",
                        subtitle = "Advanced mode can learn live menu labels and safely react when network menus change."
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Detect USSD code changes", color = C.t1, fontWeight = FontWeight.Medium)
                                Text("Learn menu labels and protect this offer", color = C.t2, fontSize = 11.sp)
                            }
                            Switch(
                                checked = signatureEnabled,
                                onCheckedChange = { signatureEnabled = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = C.green, uncheckedTrackColor = C.border)
                            )
                        }
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
                                    shape = RoundedCornerShape(14.dp),
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
                                                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.5f))
                                            ) {
                                                Text("Relearn", color = C.amber, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = { existing?.let(onApprovePending) },
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
                                    .then(
                                        if (hasLearnedSignature) Modifier.clickable { showSignatureDetails = true }
                                        else Modifier
                                    ),
                                shape = RoundedCornerShape(14.dp),
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
                                            "Tap to view every learned step, full text, and chosen option.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == "ADVANCED" && signatureEnabled) {
                    OutlinedButton(
                        onClick = { buildOffer()?.let(onSaveAndLearn) },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, C.green.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Save & Learn", color = C.green, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { buildOffer()?.let(onSave) },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.cyan),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = C.bg, modifier = Modifier.size(18.dp))
                        Text("Save", color = C.bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text("Cancel", color = C.t2, fontWeight = FontWeight.Medium)
            }
        }
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
