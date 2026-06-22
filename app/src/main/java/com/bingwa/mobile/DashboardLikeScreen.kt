package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun DashboardLikeScreen(
    airBal: String,
    tokenBal: Int,
    sent: Int,
    pending: Int,
    failed: Int,
    rate: Int,
    recentTransactions: List<Transaction>,
    running: Boolean,
    isRefreshing: Boolean,
    unlimitedLabel: String?,
    unlimitedRemaining: String?,
    onRefresh: () -> Unit
) {
    val (airtimeCurrency, airtimeAmount) = splitAirtimeBalance(airBal)

    Column(
        Modifier.background(C.bg)
            .verticalScroll(rememberScrollState())
            .padding(UiDimens.ScreenPadding)
    ) {
        DashboardHeader(
            running = running,
            automatedCount = recentTransactions.size,
            tokenBal = tokenBal
        )

        Spacer(Modifier.height(UiDimens.SpacingXl))
        BalanceOverviewCard(
            airtimeCurrency = airtimeCurrency,
            airtimeAmount = airtimeAmount,
            tokenBal = tokenBal,
            sent = sent,
            pending = pending,
            failed = failed,
            rate = rate,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh
        )

        if (!unlimitedLabel.isNullOrBlank()) {
            Spacer(Modifier.height(UiDimens.SpacingLg))
            HighlightCard(
                title = unlimitedLabel,
                detail = unlimitedRemaining ?: "Unlimited plan active"
            )
        }

        Spacer(Modifier.height(UiDimens.CardPadding))
        RecentActivitySection(recentTransactions = recentTransactions)
        Spacer(Modifier.height(UiDimens.SpacingMd))
    }
}

@Composable
private fun DashboardHeader(running: Boolean, automatedCount: Int, tokenBal: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBrandMark()
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Bingwa Mobile", color = C.t1, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text("USSD Automation Platform", color = C.t2, fontSize = 15.sp)
            }
            NotificationButton()
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(
                text = if (running) "AUTOMATION LIVE" else "AUTOMATION IDLE",
                healthy = running,
                modifier = Modifier.weight(1f)
            )
            HeaderCountChip(text = "$automatedCount automated")
            HeaderCountChip(text = "$tokenBal tokens")
        }
    }
}

@Composable
private fun AppBrandMark() {
    Surface(
        color = C.cardHi.copy(alpha = 0.92f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, C.orange.copy(alpha = 0.35f)),
        modifier = Modifier.size(92.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            C.orange.copy(alpha = 0.24f),
                            C.cardHi.copy(alpha = 0.96f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Bolt,
                null,
                tint = C.orange,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}

@Composable
private fun NotificationButton() {
    Surface(
        color = C.surface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.size(84.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.NotificationsNone,
                null,
                tint = C.t1,
                modifier = Modifier.size(34.dp)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 18.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.orange)
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, healthy: Boolean, modifier: Modifier = Modifier) {
    val chipColor = if (healthy) C.green else C.orange
    val chipFill = if (healthy) C.greenDim else C.orangeDim
    Surface(
        color = chipFill,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(chipColor))
            Spacer(Modifier.width(10.dp))
            Text(text, color = chipColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun HeaderCountChip(text: String) {
    Surface(
        color = C.cardHi.copy(alpha = 0.76f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, C.orange.copy(alpha = 0.15f))
    ) {
        Text(
            text = text,
            color = C.blue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun BalanceOverviewCard(
    airtimeCurrency: String,
    airtimeAmount: String,
    tokenBal: Int,
    sent: Int,
    pending: Int,
    failed: Int,
    rate: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Surface(
        color = C.cardHi.copy(alpha = 0.97f),
        shape = RoundedCornerShape(34.dp),
        border = BorderStroke(1.dp, C.borderHi.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF4A3C2C).copy(alpha = 0.72f),
                            C.cardHi.copy(alpha = 0.98f),
                            Color(0xFF101625).copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricHeading(text = "AIRTIME BALANCE", leadingDot = true)
                MetricHeading(text = "TOKENS", trailingDot = true)
            }

            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(1.dp)
                        .height(118.dp)
                        .background(C.w08)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 50.dp)
                    ) {
                        Text(airtimeCurrency, color = C.t1, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            airtimeAmount,
                            color = C.t1,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 54.sp
                        )
                        Spacer(Modifier.height(UiDimens.SpacingSm))
                        Text(
                            if (isRefreshing) "checking balance..." else "tap card to refresh",
                            color = C.t3,
                            fontSize = 13.sp
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(16.dp))
                        Text(tokenBal.toString(), color = C.t1, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
                        Text("units", color = C.t2, fontSize = 15.sp)
                    }
                }

                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, C.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = C.surface,
                        contentColor = C.t1
                    ),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(74.dp)
                ) {
                    Icon(Icons.Outlined.Autorenew, null, tint = C.t2, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            BalanceAccentWave()
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(C.w08.copy(alpha = 0.75f))
            )
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardMetric(
                    label = "SENT",
                    value = sent.toString(),
                    valueColor = C.green,
                    modifier = Modifier.weight(1f),
                    centered = false
                )
                VerticalMetricDivider()
                DashboardMetric(
                    label = "PENDING",
                    value = pending.toString(),
                    valueColor = C.orange,
                    modifier = Modifier.weight(1f),
                    centered = false
                )
                VerticalMetricDivider()
                DashboardMetric(
                    label = "FAILED",
                    value = failed.toString(),
                    valueColor = C.red,
                    modifier = Modifier.weight(1f),
                    centered = false
                )
                SuccessRateIndicator(rate = rate)
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    centered: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(value, color = valueColor, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(UiDimens.SpacingXs))
        Text(
            label,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun VerticalMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(64.dp)
            .background(C.w08)
    )
}

@Composable
private fun SuccessRateIndicator(rate: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 14.dp)) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = rate / 100f,
                modifier = Modifier.size(84.dp),
                color = C.green,
                trackColor = C.w08,
                strokeWidth = 8.dp
            )
            Text(
                text = "${rate}%",
                color = C.green,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(UiDimens.SpacingSm))
        Text(
            "SUCCESS RATE",
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun HighlightCard(title: String, detail: String) {
    Surface(
        color = C.cardHi.copy(alpha = 0.94f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(UiDimens.CardPadding)) {
            Text("Unlimited Plan", color = C.t3, fontSize = 12.sp)
            Spacer(Modifier.height(UiDimens.SpacingSm))
            Text(title, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(UiDimens.SpacingXs))
            Text(detail, color = C.t2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecentActivitySection(recentTransactions: List<Transaction>) {
    var selectedTx by remember { mutableStateOf<Transaction?>(null) }
    var dayKey by remember { mutableIntStateOf(currentDayKey()) }
    LaunchedEffect(dayKey) {
        delay(millisUntilNextMidnight() + 250L)
        dayKey = currentDayKey()
    }
    val todayTransactions = remember(recentTransactions, dayKey) {
        recentTransactions
            .asSequence()
            .filter { transactionDayKey(it) == dayKey }
            .sortedByDescending { transactionTimestamp(it) }
            .toList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(C.orange, C.orange.copy(alpha = 0.18f))
                            )
                        )
                )
                Spacer(Modifier.width(18.dp))
                Text("Recent Activity", color = C.t1, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }

            HeaderCountChip(
                text = "${todayTransactions.size} automated"
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Daily dispatch results (auto-clears at midnight).",
            color = C.t2,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(20.dp))
        if (todayTransactions.isEmpty()) {
            RecentActivityShowcase()
        } else {
            Surface(
                color = C.cardHi.copy(alpha = 0.9f),
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    todayTransactions.forEachIndexed { index, tx ->
                        if (index > 0) {
                            Spacer(Modifier.height(12.dp))
                        }
                        ActivityRow(tx = tx, onClick = { selectedTx = tx })
                    }
                }
            }
        }
    }

    selectedTx?.let { tx ->
        TransactionDetailDialog(
            tx = tx,
            onDismiss = { selectedTx = null }
        )
    }
}

@Composable
private fun ActivityInsightChip(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        color = C.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.7f)),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = C.t3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RecentActivityShowcase() {
    Surface(
        color = C.cardHi.copy(alpha = 0.92f),
        shape = RoundedCornerShape(32.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF4B3A2B).copy(alpha = 0.45f),
                            Color(0xFF2B4331).copy(alpha = 0.75f),
                            Color(0xFF19212F).copy(alpha = 0.92f)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(212.dp)
                    .clip(RoundedCornerShape(42.dp))
                    .background(C.green.copy(alpha = 0.10f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(126.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(C.orange)
                    .border(1.dp, C.orange.copy(alpha = 0.45f), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Bolt, null, tint = C.bg, modifier = Modifier.size(54.dp))
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dotColors = listOf(C.green.copy(alpha = 0.7f), C.orange.copy(alpha = 0.6f), C.blue.copy(alpha = 0.55f))
                val offsets = listOf(
                    Offset(size.width * 0.22f, size.height * 0.28f),
                    Offset(size.width * 0.78f, size.height * 0.31f),
                    Offset(size.width * 0.30f, size.height * 0.64f),
                    Offset(size.width * 0.70f, size.height * 0.67f),
                    Offset(size.width * 0.50f, size.height * 0.14f)
                )
                offsets.forEachIndexed { index, offset ->
                    drawCircle(
                        color = dotColors[index % dotColors.size],
                        radius = if (index == 4) 7f else 5f,
                        center = offset
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Automated activity appears here", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Show customer, bundle, amount, run time, and failure reason so it complements full history in Settings.",
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(tx: Transaction, onClick: () -> Unit) {
    val title = tx.clientName.ifBlank {
        tx.description.ifBlank { "Transaction" }
    }
    val shouldShowReason = tx.statusEnum == TransactionStatus.FAILED ||
        tx.statusEnum == TransactionStatus.CANCELLED ||
        DailyLimitPolicy.isDailyLimitHold(tx)
    val reason = if (shouldShowReason) transactionReasonShort(tx) else ""
    val subtitle = buildList {
        reason.takeIf { it.isNotBlank() }?.let(::add)
        tx.description
            .takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            ?.let(::add)
        tx.phoneNumber
            .takeIf { it.isNotBlank() }
            ?.let { add(maskActivityPhone(it)) }
        if (tx.executionDurationMs > 0L) {
            formatExecutionMs(tx.executionDurationMs).takeIf { it.isNotBlank() }?.let { add("$it run") }
        } else if (tx.date.isNotBlank()) {
            add(tx.date)
        }
    }.take(2).joinToString(" • ").ifBlank { "Latest automated transaction" }
    val statusColor = when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> C.green
        TransactionStatus.FAILED, TransactionStatus.CANCELLED -> C.red
        else -> C.orange
    }
    Surface(
        color = C.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = C.t1,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = C.t3,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(tx.amount.ifBlank { "-" }, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(tx.status)
                        if (tx.date.isNotBlank()) {
                            append(" • ")
                            append(tx.date)
                        }
                    },
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun maskActivityPhone(phone: String): String {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 7) return phone
    return digits.take(4) + "..." + digits.takeLast(2)
}

private fun formatActivityDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m"
        else -> "${seconds / 3600L}h"
    }
}

@Composable
private fun MetricHeading(text: String, leadingDot: Boolean = false, trailingDot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.blue)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            color = C.t2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp
        )
        if (trailingDot) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(C.orange)
            )
        }
    }
}

@Composable
private fun BalanceAccentWave() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.55f)
            cubicTo(
                size.width * 0.18f,
                size.height * 0.18f,
                size.width * 0.27f,
                size.height * 1.02f,
                size.width * 0.42f,
                size.height * 0.60f
            )
            cubicTo(
                size.width * 0.60f,
                size.height * 0.22f,
                size.width * 0.75f,
                size.height * 0.92f,
                size.width,
                size.height * 0.12f
            )
        }
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                listOf(
                    C.orange.copy(alpha = 0.10f),
                    C.orange.copy(alpha = 0.45f),
                    C.green.copy(alpha = 0.35f)
                )
            ),
            style = Stroke(width = 11f)
        )
    }
}

private fun splitAirtimeBalance(value: String): Pair<String, String> {
    val normalized = value.trim().ifBlank { "KSh 0.00" }
    val parts = normalized.split(Regex("\\s+"), limit = 2)
    return if (parts.size == 2 && parts[0].any { it.isLetter() }) {
        parts[0].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } to parts[1]
    } else {
        "KSh" to normalized
    }
}
