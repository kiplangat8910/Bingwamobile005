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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
                StatusChip(text = if (running) "Automation Live" else "Automation Idle", healthy = running)
            }
        }

        Spacer(Modifier.height(UiDimens.SpacingXl))
        StatCard(
            title = "Airtime",
            value = airBal.ifBlank { "Ksh 0.00" },
            right = {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, C.border),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = C.cardHi, contentColor = C.t1),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(Icons.Outlined.Autorenew, null, tint = C.t2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRefreshing) "Refreshing" else "Refresh", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        )

        Spacer(Modifier.height(UiDimens.SpacingLg))
        StatCard(
            title = "Tokens",
            value = tokenBal.toString(),
            subValue = "available",
            right = {}
        )

        if (!unlimitedLabel.isNullOrBlank()) {
            Spacer(Modifier.height(UiDimens.SpacingLg))
            HighlightCard(
                title = unlimitedLabel,
                detail = unlimitedRemaining ?: "Unlimited plan active"
            )
        }

        Spacer(Modifier.height(UiDimens.SpacingLg))
        Surface(
            color = C.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(UiDimens.CardPadding)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${rate}%", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Success rate", color = C.t3, fontSize = 12.sp)
                }
                Spacer(Modifier.height(UiDimens.SpacingMd))
                LinearProgressIndicator(
                    progress = rate / 100f,
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)),
                    color = C.green,
                    trackColor = C.w08
                )
                Spacer(Modifier.height(UiDimens.SpacingLg))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiDimens.SpacingMd)) {
                    MiniCountCard("Sent", sent.toString(), modifier = Modifier.weight(1f))
                    MiniCountCard("Pending", pending.toString(), modifier = Modifier.weight(1f))
                    MiniCountCard("Failed", failed.toString(), modifier = Modifier.weight(1f))
                }
            }
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
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(chipColor))
            Spacer(Modifier.width(8.dp))
            Text(text, color = chipColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, subValue: String? = null, right: @Composable () -> Unit) {
    Surface(
        color = C.cardHi.copy(alpha = 0.95f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.92f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(UiDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.t3, fontSize = 12.sp)
                Spacer(Modifier.height(UiDimens.SpacingSm))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = C.t1, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    if (subValue != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(subValue, color = C.t3, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
            }
            right()
        }
    }
}

@Composable
private fun MiniCountCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = C.surface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.72f)),
        modifier = modifier
    ) {
        Column(Modifier.padding(UiDimens.SpacingLg), horizontalAlignment = Alignment.Start) {
            Text(label, color = C.t3, fontSize = 11.sp)
            Spacer(Modifier.height(UiDimens.SpacingSm))
            Text(value, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
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
                Text(tx.description.ifBlank { "Transaction" }, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
