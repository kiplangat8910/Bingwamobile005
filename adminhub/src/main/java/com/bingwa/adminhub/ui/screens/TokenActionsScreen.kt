package com.bingwa.adminhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.adminhub.data.models.AdminUser
import com.bingwa.adminhub.data.models.TokenType
import com.bingwa.adminhub.data.repositories.TokenRepository
import com.bingwa.adminhub.data.repositories.UserRepository
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary

@Composable
fun TokenActionsScreen(
    tokenRepository: TokenRepository,
    userRepository: UserRepository
) {
    val users by userRepository.users.collectAsState(initial = emptyList())
    var selectedUser by remember { mutableStateOf<AdminUser?>(null) }
    var selectedAction by remember { mutableStateOf<TokenType?>(null) }
    var amount by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var showSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Token Actions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AdminTextPrimary
        )

        Text("Select User", fontWeight = FontWeight.SemiBold, color = AdminTextSecondary, fontSize = 12.sp)
        LazyColumn(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(users) { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedUser = user },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedUser?.id == user.id)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(
                        text = "${user.name} (${user.phone})",
                        modifier = Modifier.padding(12.dp),
                        color = AdminTextPrimary
                    )
                }
            }
        }

        Text("Action Type", fontWeight = FontWeight.SemiBold, color = AdminTextSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton("Activate", TokenType.ACTIVATE, selectedAction == TokenType.ACTIVATE) { selectedAction = TokenType.ACTIVATE }
            ActionButton("Clear", TokenType.CLEAR, selectedAction == TokenType.CLEAR) { selectedAction = TokenType.CLEAR }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton("Gift", TokenType.GIFT, selectedAction == TokenType.GIFT) { selectedAction = TokenType.GIFT }
            ActionButton("Unlimited", TokenType.UNLIMITED, selectedAction == TokenType.UNLIMITED) { selectedAction = TokenType.UNLIMITED }
        }

        if (selectedAction == TokenType.ACTIVATE || selectedAction == TokenType.GIFT) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Activation Code") },
                placeholder = { Text("e.g. ACT12345") },
                singleLine = true
            )
        }

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message (optional)") },
            placeholder = { Text("Admin has activated your limited usage...") },
            minLines = 2
        )

        Button(
            onClick = {
                if (selectedUser != null && selectedAction != null) {
                    val transaction = com.bingwa.adminhub.data.models.TokenTransaction(
                        id = "tx_${System.currentTimeMillis()}",
                        userId = selectedUser!!.id,
                        type = selectedAction!!,
                        code = code,
                        message = message,
                        status = com.bingwa.adminhub.data.models.TransactionStatus.SENT
                    )
                    scope.launch {
                        tokenRepository.addTransaction(transaction)
                        showSuccess = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedUser != null && selectedAction != null
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Send Activation")
        }

        if (showSuccess) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    text = "Command sent successfully!",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun RowScope.ActionButton(label: String, type: TokenType, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else AdminTextSecondary
        ),
        border = if (!selected) androidx.compose.foundation.BorderStroke(1.dp, AdminAmber.copy(alpha = 0.3f)) else null,
        modifier = Modifier.weight(1f)
    ) {
        Text(label)
    }
}
