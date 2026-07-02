package com.bingwa.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts as ContactsFilled
import androidx.compose.material.icons.filled.Home as HomeFilled
import androidx.compose.material.icons.filled.Settings as SettingsFilled
import androidx.compose.material.icons.filled.Terminal as TerminalFilled
import androidx.compose.material.icons.filled.Toll as TollFilled
import androidx.compose.material.icons.outlined.Contacts as ContactsOutlined
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material.icons.outlined.Settings as SettingsOutlined
import androidx.compose.material.icons.outlined.Terminal as TerminalOutlined
import androidx.compose.material.icons.outlined.Toll as TollOutlined
import androidx.compose.ui.graphics.vector.ImageVector

internal sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSel: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Outlined.HomeOutlined, Icons.Filled.HomeFilled)
    data object Manual : Screen("manual", "Manual", Icons.Outlined.TerminalOutlined, Icons.Filled.TerminalFilled)
    data object Tokens : Screen("tokens", "Tokens", Icons.Outlined.TollOutlined, Icons.Filled.TollFilled)
    data object Contacts : Screen("contacts", "Contacts", Icons.Outlined.ContactsOutlined, Icons.Filled.ContactsFilled)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.SettingsOutlined, Icons.Filled.SettingsFilled)
}

internal val NAV_ITEMS = listOf(Screen.Home, Screen.Manual, Screen.Tokens, Screen.Settings)
