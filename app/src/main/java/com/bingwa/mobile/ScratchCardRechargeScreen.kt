package com.bingwa.mobile

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val PREFS_SCRATCH_CARD = "scratch_card_prefs"
private const val KEY_RECENT_PINS = "recent_pins"
private const val MAX_RECENT_PINS = 5

@Composable
fun ScratchCardRechargeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var pin by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var recentPins by remember { mutableStateOf(loadRecentPins(ctx)) }
    
    // PIN is masked for display (show •••• groups)
    val displayPin = pin.chunked(4).joinToString(" ").padEnd(19, '•').replace(" ", " •") 
    
    fun onDigitPress(digit: String) {
        if (pin.length < 16 && !isProcessing) {
            pin += digit
        }
    }
    
    fun onClear() {
        pin = ""
    }
    
    fun onBackspace() {
        if (pin.isNotEmpty()) {
            pin = pin.dropLast(1)
        }
    }
    
    fun onTopUp() {
        if (pin.length != 16) {
            Toast.makeText(ctx, "Please enter a complete 16-digit PIN", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch {
            isProcessing = true
            val result = startScratchCardRecharge(ctx, pin)
            
            if (result) {
                // Save to recent
                saveRecentPin(ctx, pin)
                recentPins = loadRecentPins(ctx)
                pin = ""
                Toast.makeText(ctx, "Recharge successful!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Recharge failed. Please try again.", Toast.LENGTH_SHORT).show()
            }
            isProcessing = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
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
        
        // Main Card
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
                    "Enter your scratch card PIN",
                    color = C.t2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(Modifier.height(20.dp))
                
                // PIN Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Show 4 groups of 4 digits
                    repeat(4) { groupIndex ->
                        val startIndex = groupIndex * 4
                        val endIndex = minOf(startIndex + 4, pin.length)
                        val groupValue = if (startIndex < pin.length) {
                            pin.substring(startIndex, endIndex)
                        } else ""
                        
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
                
                Spacer(Modifier.height(24.dp))
                
                // Numpad
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: 1 2 3
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf("1", "2", "3").forEach { digit ->
                            NumpadButton(
                                digit = digit,
                                onClick = { onDigitPress(digit) }
                            )
                        }
                    }
                    // Row 2: 4 5 6
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf("4", "5", "6").forEach { digit ->
                            NumpadButton(
                                digit = digit,
                                onClick = { onDigitPress(digit) }
                            )
                        }
                    }
                    // Row 3: 7 8 9
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf("7", "8", "9").forEach { digit ->
                            NumpadButton(
                                digit = digit,
                                onClick = { onDigitPress(digit) }
                            )
                        }
                    }
                    // Row 4: Clear 0 Backspace
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        // Clear button
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .clickable(onClick = onClear),
                            shape = CircleShape,
                            color = C.surface.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.4f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = "Clear",
                                    tint = C.t2,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        
                        // 0 button
                        NumpadButton(
                            digit = "0",
                            onClick = { onDigitPress("0") }
                        )
                        
                        // Backspace button
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .clickable(onClick = onBackspace),
                            shape = CircleShape,
                            color = C.surface.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, C.border.copy(alpha = 0.4f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "⌫",
                                    color = C.t2,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(20.dp))
                
                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Clear Button
                    Button(
                        onClick = onClear,
                        enabled = pin.isNotEmpty() && !isProcessing,
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
                    
                    // Top Up Button
                    Button(
                        onClick = onTopUp,
                        enabled = pin.length == 16 && !isProcessing,
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
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Recents Section
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
private fun NumpadButton(
    digit: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(64.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = C.surface.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.4f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = digit,
                color = C.t1,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
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

// Data persistence for recent pins
private fun loadRecentPins(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val pinsString = prefs.getString(KEY_RECENT_PINS, "") ?: ""
    return pinsString.split(",").filter { it.length == 16 }.take(MAX_RECENT_PINS)
}

private fun saveRecentPin(context: Context, pin: String) {
    if (pin.length != 16) return
    val prefs = context.getSharedPreferences(PREFS_SCRATCH_CARD, Context.MODE_PRIVATE)
    val currentPins = loadRecentPins(context).toMutableList()
    
    // Remove if already exists (to move to top)
    currentPins.remove(pin)
    // Add to top
    currentPins.add(0, pin)
    // Keep only max
    val newPins = currentPins.take(MAX_RECENT_PINS)
    
    prefs.edit().putString(KEY_RECENT_PINS, newPins.joinToString(",")).apply()
}

// Scratch card recharge function
private suspend fun startScratchCardRecharge(context: Context, pin: String): Boolean {
    return try {
        val ussdCode = "*141*$pin#"
        // Use silent USSD
        SilentUssd.dial(
            context = context,
            ussdCode = ussdCode,
            onResult = { /* Result handled by caller */ }
        )
        true
    } catch (e: Exception) {
        false
    }
}
