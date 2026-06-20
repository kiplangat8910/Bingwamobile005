package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = C.cardHi.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.9f))
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                C.cyan.copy(alpha = 0.15f),
                                C.blue.copy(alpha = 0.08f),
                                C.cardHi.copy(alpha = 0.96f)
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Text("Bingwa Mobile", color = C.t1, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(UiDimens.SpacingSm))
                Text("USSD Automation Platform", color = C.t2, fontSize = 14.sp)
                Spacer(Modifier.height(UiDimens.SpacingLg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(UiDimens.SpacingMd)
                ) {
                    StatusChip(
                        text = if (running) "Automation Live" else "Automation Idle",
                        healthy = running
                    )
                    HeaderCountChip(text = "${recentTransactions.size} automated")
                    HeaderCountChip(text = "$tokenBal tokens")
                }
            }
        }

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
        Surface(
            color = C.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(UiDimens.CardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, null, tint = C.t2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(UiDimens.SpacingMd))
                    Text("Recent Activity", color = C.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(UiDimens.SpacingMd))
                Text("${recentTransactions.size} automated", color = C.t3, fontSize = 12.sp)
                if (recentTransactions.isEmpty()) {
                    Spacer(Modifier.height(UiDimens.SpacingLg))
                    Text("Scanning for activity", color = C.t2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(UiDimens.SpacingXs))
                    Text("Use the Start button below to begin seeing results", color = C.t3, fontSize = 12.sp)
                } else {
                    recentTransactions.take(4).forEach { tx ->
                        Spacer(Modifier.height(UiDimens.SpacingLg))
                        ActivityRow(tx = tx)
                    }
                }
            }
        }

        Spacer(Modifier.height(UiDimens.CardPadding))
        AutomationBanner(running = running)
        Spacer(Modifier.height(UiDimens.SpacingMd))
    }
}

@Composable
private fun StatusChip(text: String, healthy: Boolean) {
    val chipColor = if (healthy) C.green else C.orange
    val chipFill = if (healthy) C.greenDim else C.orangeDim
    Surface(
        color = chipFill,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, chipColor.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(chipColor))
            Spacer(Modifier.width(8.dp))
            Text(text, color = chipColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HeaderCountChip(text: String) {
    Surface(
        color = C.orangeDim.copy(alpha = 0.55f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, C.orange.copy(alpha = 0.18f))
    ) {
        Text(
            text = text,
            color = C.blue,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
        color = C.cardHi.copy(alpha = 0.96f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, C.borderHi.copy(alpha = 0.95f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            C.blueDim.copy(alpha = 0.20f),
                            C.cardHi.copy(alpha = 0.98f),
                            C.surface.copy(alpha = 0.96f)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 36.dp)
                    ) {
                        Text(
                            "AIRTIME BALANCE",
                            color = C.blue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(UiDimens.SpacingLg))
                        Text(airtimeCurrency, color = C.t1, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            airtimeAmount,
                            color = C.t1,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 34.sp
                        )
                        Spacer(Modifier.height(UiDimens.SpacingSm))
                        Text(
                            if (isRefreshing) "refreshing balance..." else "tap refresh to update",
                            color = C.t3,
                            fontSize = 12.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(120.dp)
                            .background(C.w08)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "TOKENS",
                            color = C.blue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(30.dp))
                        Text(tokenBal.toString(), color = C.t1, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                        Text("units", color = C.t2, fontSize = 13.sp)
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
                        .size(72.dp)
                ) {
                    Icon(Icons.Outlined.Autorenew, null, tint = C.t2, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(C.w08)
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
                    modifier = Modifier.weight(1f)
                )
                VerticalMetricDivider()
                DashboardMetric(
                    label = "PENDING",
                    value = pending.toString(),
                    valueColor = C.orange,
                    modifier = Modifier.weight(1f)
                )
                VerticalMetricDivider()
                DashboardMetric(
                    label = "FAILED",
                    value = failed.toString(),
                    valueColor = C.red,
                    modifier = Modifier.weight(1f)
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(UiDimens.SpacingXs))
        Text(
            label,
            color = C.t2,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun VerticalMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(56.dp)
            .background(C.w08)
    )
}

@Composable
private fun SuccessRateIndicator(rate: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = rate / 100f,
                modifier = Modifier.size(76.dp),
                color = C.green,
                trackColor = C.w08,
                strokeWidth = 7.dp
            )
            Text(
                text = "${rate}%",
                color = C.green,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(UiDimens.SpacingSm))
        Text(
            "SUCCESS RATE",
            color = C.t2,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
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
private fun ActivityRow(tx: Transaction) {
    val statusColor = when (tx.statusEnum) {
        TransactionStatus.SUCCESS -> C.green
        TransactionStatus.FAILED, TransactionStatus.CANCELLED -> C.red
        else -> C.orange
    }
    Surface(
        color = C.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f)),
        modifier = Modifier.fillMaxWidth()
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
                    tx.description.ifBlank { "Transaction" },
                    color = C.t1,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(tx.date.ifBlank { tx.status }, color = C.t3, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(tx.amount.ifBlank { "-" }, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(tx.status, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun AutomationBanner(running: Boolean) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(C.cyanDim)
                    .border(1.dp, C.cyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Bolt, null, tint = C.cyan, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "Automation Running" else "Automation Ready",
                    color = C.t1,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (running) "Manage advanced tools and doctor checks from Settings." else "Use the center Start button below when you are ready.",
                    color = C.t3,
                    fontSize = 12.sp
                )
            }
        }
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
