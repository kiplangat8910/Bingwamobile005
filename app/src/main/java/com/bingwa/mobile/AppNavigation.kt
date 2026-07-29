package com.bingwa.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

internal sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSel: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    data object Manual : Screen("manual", "Manual", Icons.Outlined.Help, Icons.Filled.Help)
    data object Tokens : Screen("tokens", "Tokens", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet)
    data object Contacts : Screen("contacts", "Contacts", Icons.Outlined.Contacts, Icons.Filled.Contacts)
    data object Blacklist : Screen("blacklist", "Blacklist", Icons.Outlined.Block, Icons.Filled.Block)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

internal val NAV_ITEMS = listOf(Screen.Home, Screen.Manual, Screen.Tokens, Screen.Settings)
