package com.bingwa.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.ui.graphics.vector.ImageVector

internal sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSel: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    data object Console : Screen("console", "Dispatch", Icons.Outlined.Terminal, Icons.Filled.Terminal)
    data object Tokens : Screen("tokens", "Tokens", Icons.Outlined.Toll, Icons.Filled.Toll)
    data object Contacts : Screen("contacts", "Contacts", Icons.Outlined.Contacts, Icons.Filled.Contacts)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

internal val NAV_ITEMS = listOf(Screen.Home, Screen.Console, Screen.Tokens, Screen.Settings)
