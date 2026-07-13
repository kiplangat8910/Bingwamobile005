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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val PREFS_SCRATCH_CARD = "scratch_card_prefs"
private const val KEY_RECENT_PINS = "recent_pins"
private const val MAX_RECENT_PINS = 5

private sealed interface ScratchCardRechargeResult {
    data class Completed(val response: String) : ScratchCardRechargeResult
    data class Failed(val message: String) : ScratchCardRechargeResult
}

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isScanningImage by remember { mutableStateOf(false) }
    var recentPins by remember { mutableStateOf(loadRecentPins(ctx)) }
    var detectedPins by remember { mutableStateOf<List<String>>(emptyList()) }
    var finalResponse by remember { mutableStateOf("") }
    var responseMessage by remember { mutableStateOf("No final USSD response captured yet.") }
    var responseIsError by remember { mutableStateOf(false) }
    var scanMessage by remember {
        mutableStateOf("Upload one scratch-card image and Bingwa will extract the 16-digit PIN automatically.")
    }

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
                    recentPins = loadRecentPins(ctx)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = C.t1
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Scratch Card Recharge",
                color = C.t1,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = C.cardHi,
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Upload an image or select a scratch card PIN",
                    color = C.t2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !isProcessing && !isScanningImage,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C.cyan,
                        contentColor = Color.White,
                        disabledContainerColor = C.cyan.copy(alpha = 0.3f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isScanningImage) "Scanning image..." else "Upload Scratch Card Image",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = scanMessage,
                    color = if (detectedPins.isEmpty()) C.t3 else C.green,
                    fontSize = 13.sp,
                    fontWeight = if (detectedPins.isEmpty()) FontWeight.Normal else FontWeight.Medium
                )

                if (detectedPins.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        detectedPins.forEach { detectedPin ->
                            RecentPinItem(
                                pin = detectedPin,
                                onClick = { pin = detectedPin }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = if (pin.length == 16) "Selected PIN" else "No PIN selected yet",
                    color = C.t2,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    repeat(4) { groupIndex ->
                        val startIndex = groupIndex * 4
                        val endIndex = minOf(startIndex + 4, pin.length)
                        val groupValue = if (startIndex < pin.length) {
                            pin.substring(startIndex, endIndex)
                        } else {
                            ""
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = C.surface.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.4f)),
                            modifier = Modifier.width(60.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = groupValue.padEnd(4, '•').replace(Regex("."), "•"),
                                    color = C.t1,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onClear() },
                        enabled = pin.isNotEmpty() && !isProcessing && !isScanningImage,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C.surface,
                            contentColor = C.t1,
                            disabledContainerColor = C.surface.copy(alpha = 0.3f),
                            disabledContentColor = C.t3
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Clear", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { startRecharge(pin, "Manual") },
                        enabled = pin.length == 16 && !isProcessing && !isScanningImage,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C.green,
                            contentColor = Color.White,
                            disabledContainerColor = C.green.copy(alpha = 0.3f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.Phone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isProcessing) "Processing..." else "Top up",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (responseIsError) C.surface.copy(alpha = 0.72f) else C.greenDim,
                    border = BorderStroke(
                        1.dp,
                        if (responseIsError) C.border.copy(alpha = 0.5f) else C.green.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Final USSD Response",
                            color = C.t1,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            responseMessage,
                            color = if (responseIsError) C.t2 else C.green,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (finalResponse.isBlank()) "No final USSD response captured yet." else finalResponse,
                            color = C.t2,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = C.cardHi,
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        tint = C.t2,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Recents",
                        color = C.t1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (recentPins.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No recent recharges",
                            color = C.t3,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentPins.forEach { recentPin ->
                            RecentPinItem(
                                pin = recentPin,
                                onClick = { pin = recentPin }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
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
        shape = RoundedCornerShape(12.dp),
        color = C.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatPinForDisplay(pin),
                color = C.t1,
                fontSize = 16.sp,
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
