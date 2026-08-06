package com.bingwa.adminhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.adminhub.data.models.*
import com.bingwa.adminhub.data.repositories.*
import com.bingwa.adminhub.ui.theme.AdminHubTheme
import com.bingwa.adminhub.util.NetworkTimeSync

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdminHubTheme {
                AdminHubApp()
            }
        }
    }
}

@Composable
fun AdminHubApp() {
    val userRepository = remember { UserRepository() }
    val purchaseRepository = remember { PurchaseRepository() }
    val tokenRepository = remember { TokenRepository() }
    val scheduleRepository = remember { ScheduleRepository() }
    val templateRepository = remember { SmsTemplateRepository() }

    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bingwa Admin Hub", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentScreen == Screen.DASHBOARD,
                    onClick = { currentScreen = Screen.DASHBOARD },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.USERS,
                    onClick = { currentScreen = Screen.USERS },
                    icon = { Icon(Icons.Filled.People, contentDescription = "Users") },
                    label = { Text("Users") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.TOKEN_ACTIONS,
                    onClick = { currentScreen = Screen.TOKEN_ACTIONS },
                    icon = { Icon(Icons.Filled.Token, contentDescription = "Tokens") },
                    label = { Text("Tokens") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.SMS_CENTER,
                    onClick = { currentScreen = Screen.SMS_CENTER },
                    icon = { Icon(Icons.Filled.Send, contentDescription = "SMS") },
                    label = { Text("SMS") }
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.SETTINGS,
                    onClick = { currentScreen = Screen.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        },
        floatingActionButton = {
            if (currentScreen == Screen.DASHBOARD || currentScreen == Screen.USERS) {
                FloatingActionButton(onClick = { /* TODO: Add new item */ }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                Screen.DASHBOARD -> DashboardScreen(
                    purchaseRepository = purchaseRepository,
                    userRepository = userRepository
                )
                Screen.USERS -> UsersScreen(userRepository = userRepository)
                Screen.TOKEN_ACTIONS -> TokenActionsScreen(
                    tokenRepository = tokenRepository,
                    userRepository = userRepository
                )
                Screen.SMS_CENTER -> SmsCenterScreen(templateRepository = templateRepository)
                Screen.SCHEDULE -> ScheduleScreen(scheduleRepository = scheduleRepository)
                Screen.SETTINGS -> SettingsScreen()
            }
        }
    }
}

enum class Screen {
    DASHBOARD,
    USERS,
    TOKEN_ACTIONS,
    SMS_CENTER,
    SCHEDULE,
    SETTINGS
}
