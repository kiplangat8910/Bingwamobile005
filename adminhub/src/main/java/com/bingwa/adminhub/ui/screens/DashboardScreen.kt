package com.bingwa.adminhub.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.adminhub.data.models.PurchaseSms
import com.bingwa.adminhub.data.models.AdminUser
import com.bingwa.adminhub.data.repositories.PurchaseRepository
import com.bingwa.adminhub.data.repositories.UserRepository
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary

@Composable
fun DashboardScreen(
    purchaseRepository: PurchaseRepository,
    userRepository: UserRepository
) {
    val purchases by purchaseRepository.purchases.collectAsState(initial = emptyList())
    val users by userRepository.users.collectAsState(initial = emptyList())
    val recentPurchases = purchases.take(10)
    val totalBalance = purchases.firstOrNull()?.balance ?: 0.0
    val totalToday = purchases.filter { 
        val day = System.currentTimeMillis() / 86400000
        it.timestamp / 86400000 == day
    }.sumOf { it.amount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AdminTextPrimary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "Airtime Balance",
                    value = "%.2f KSH".format(totalBalance),
                    icon = Icons.Filled.AccountBalanceWallet,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Today's Purchases",
                    value = "%.2f KSH".format(totalToday),
                    icon = Icons.Filled.AttachMoney,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "Total Users",
                    value = users.size.toString(),
                    icon = Icons.Filled.Group,
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Total Purchases",
                    value = purchases.size.toString(),
                    icon = Icons.Filled.ShoppingCart,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(
                text = "Recent Purchases",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = AdminTextPrimary
            )
        }

        if (recentPurchases.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No purchases yet. SMS purchases will appear here automatically.",
                            color = AdminTextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else {
            items(recentPurchases) { purchase ->
                PurchaseCard(purchase = purchase)
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AdminAmber,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    color = AdminTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = value,
                color = AdminTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PurchaseCard(purchase: PurchaseSms) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AdminAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = purchase.phone,
                        fontWeight = FontWeight.SemiBold,
                        color = AdminTextPrimary,
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = "%.2f KSH".format(purchase.amount),
                    fontWeight = FontWeight.Bold,
                    color = AdminAmber,
                    fontSize = 14.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Balance: %.2f KSH".format(purchase.balance), color = AdminTextSecondary, fontSize = 12.sp)
                Text(text = "Exp: ${purchase.expirationDate}", color = AdminTextSecondary, fontSize = 12.sp)
            }
        }
    }
}
