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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.adminhub.data.models.AdminUser
import com.bingwa.adminhub.data.models.PurchaseSms
import com.bingwa.adminhub.data.repositories.PurchaseRepository
import com.bingwa.adminhub.data.repositories.UserRepository
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary

@Composable
fun UsersScreen(userRepository: UserRepository, purchaseRepository: PurchaseRepository) {
    val users by userRepository.users.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<AdminUser?>(null) }
    var selectedUser by remember { mutableStateOf<AdminUser?>(null) }
    val scope = rememberCoroutineScope()

    val filteredUsers = remember(searchQuery, users) {
        if (searchQuery.isBlank()) users
        else users.filter {
            it.phone.contains(searchQuery, ignoreCase = true) ||
            it.name.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Users",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AdminTextPrimary
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search users...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )

        if (filteredUsers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No users found. Add your first user to get started.",
                    color = AdminTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredUsers) { user ->
                    UserCard(
                        user = user,
                        onEdit = { editingUser = user },
                        onDelete = {
                            scope.launch {
                                userRepository.deleteUser(user.id)
                            }
                        },
                        onClick = { selectedUser = user }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        UserDialog(
            user = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, phone, category, notes ->
                scope.launch {
                    val newUser = AdminUser(
                        id = "user_${System.currentTimeMillis()}",
                        phone = phone,
                        name = name,
                        category = category,
                        notes = notes
                    )
                    userRepository.addUser(newUser)
                    showAddDialog = false
                }
            }
        )
    }
    if (editingUser != null) {
        UserDialog(
            user = editingUser,
            onDismiss = { editingUser = null },
            onSave = { name, phone, category, notes ->
                scope.launch {
                    val updated = editingUser!!.copy(name = name, phone = phone, category = category, notes = notes)
                    userRepository.updateUser(updated)
                    editingUser = null
                }
            }
        )
    }
    if (selectedUser != null) {
        UserHistoryDialog(
            user = selectedUser!!,
            purchaseRepository = purchaseRepository,
            onDismiss = { selectedUser = null }
        )
    }
}

@Composable
fun UserDialog(user: AdminUser?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(user?.name ?: "") }
    var phone by remember { mutableStateOf(user?.phone ?: "") }
    var category by remember { mutableStateOf(user?.category ?: "") }
    var notes by remember { mutableStateOf(user?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (user == null) "Add User" else "Edit User") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(value = category, onValueChange = { category = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Category") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && phone.isNotBlank()) {
                    onSave(name, phone, category, notes)
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun UserCard(user: AdminUser, onEdit: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = user.name,
                    fontWeight = FontWeight.SemiBold,
                    color = AdminTextPrimary,
                    fontSize = 15.sp
                )
                Text(
                    text = user.phone,
                    color = AdminTextSecondary,
                    fontSize = 13.sp
                )
                if (user.category.isNotBlank()) {
                    Text(
                        text = user.category,
                        color = AdminAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = AdminTextSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onEdit() }
                )
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDelete() }
                )
            }
        }
    }
}

@Composable
fun UserHistoryDialog(user: AdminUser, purchaseRepository: PurchaseRepository, onDismiss: () -> Unit) {
    val purchases by purchaseRepository.purchases.collectAsState(initial = emptyList())
    val userPurchases = remember(purchases, user.phone) {
        purchases.filter { it.phone == user.phone }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${user.name} - Purchase History") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Phone: ${user.phone}", color = AdminTextSecondary, fontSize = 14.sp)
                if (user.category.isNotBlank()) {
                    Text("Category: ${user.category}", color = AdminTextSecondary, fontSize = 14.sp)
                }
                if (user.notes.isNotBlank()) {
                    Text("Notes: ${user.notes}", color = AdminTextSecondary, fontSize = 14.sp)
                }
                Text(
                    text = "Total Purchases: ${userPurchases.size}",
                    fontWeight = FontWeight.SemiBold,
                    color = AdminTextPrimary,
                    fontSize = 14.sp
                )
                if (userPurchases.isEmpty()) {
                    Text("No purchases recorded yet.", color = AdminTextSecondary, fontSize = 14.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(userPurchases) { purchase ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${purchase.amount} KSH", fontWeight = FontWeight.Bold, color = AdminAmber, fontSize = 14.sp)
                                    Text("Balance: ${purchase.balance} KSH", color = AdminTextSecondary, fontSize = 12.sp)
                                    Text("Exp: ${purchase.expirationDate}", color = AdminTextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
