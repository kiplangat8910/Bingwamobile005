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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.bingwa.adminhub.data.models.ScheduledAction
import com.bingwa.adminhub.data.models.ScheduledTask
import com.bingwa.adminhub.data.repositories.ScheduleRepository
import com.bingwa.adminhub.data.repositories.UserRepository
import com.bingwa.adminhub.service.SmsSender
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary
import com.bingwa.adminhub.util.Scheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(scheduleRepository: ScheduleRepository, userRepository: UserRepository) {
    val tasks by scheduleRepository.tasks.collectAsState(initial = emptyList())
    val users by userRepository.users.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Schedule",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AdminTextPrimary
            )
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(4.dp))
                Text("New Task")
            }
        }

        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No scheduled tasks. Create one to automate token actions.",
                    color = AdminTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks) { task ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    ScheduledTaskCard(
                        task = task,
                        onDelete = {
                            scope.launch {
                                scheduleRepository.deleteTask(task.id)
                                Scheduler.cancelTask(
                                    context = context,
                                    taskId = task.id
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AddScheduleDialog(
            users = users,
            onDismiss = { showAddDialog = false },
            onConfirm = { userId, action, scheduledAt, repeat, code, message ->
                scope.launch {
                    val task = com.bingwa.adminhub.data.models.ScheduledTask(
                        id = "task_${System.currentTimeMillis()}",
                        userId = userId,
                        action = action,
                        scheduledAt = scheduledAt,
                        repeat = repeat,
                        code = code,
                        message = message
                    )
                    scheduleRepository.addTask(task)
                    Scheduler.scheduleTask(
                        context = context,
                        taskId = task.id,
                        triggerAt = scheduledAt,
                        repeatInterval = Scheduler.getRepeatInterval(repeat.name)
                    )
                    showAddDialog = false
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScheduleDialog(
    users: List<AdminUser>,
    onDismiss: () -> Unit,
    onConfirm: (String, ScheduledAction, Long, com.bingwa.adminhub.data.models.RepeatMode, String, String) -> Unit
) {
    var selectedUser by remember { mutableStateOf<AdminUser?>(null) }
    var selectedAction by remember { mutableStateOf<ScheduledAction?>(null) }
    var scheduledAt by remember { mutableStateOf("") }
    var repeatMode by remember { mutableStateOf(com.bingwa.adminhub.data.models.RepeatMode.ONCE) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Scheduled Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (users.isNotEmpty()) {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = selectedUser?.let { "${it.name} (${it.phone})" } ?: "Select user",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("User") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            users.forEach { user ->
                                DropdownMenuItem(
                                    text = { Text("${user.name} (${user.phone})") },
                                    onClick = {
                                        selectedUser = user
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text("No users available. Add users first.", color = AdminTextSecondary, fontSize = 14.sp)
                }

                OutlinedTextField(
                    value = scheduledAt,
                    onValueChange = { scheduledAt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Trigger time (epoch millis)") },
                    placeholder = { Text("e.g. ${System.currentTimeMillis() + 86400000}") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Code (optional)") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message (optional)") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedUser != null && scheduledAt.isNotBlank()) {
                        val action = selectedAction ?: ScheduledAction.ACTIVATE
                        onConfirm(selectedUser!!.id, action, scheduledAt.toLongOrNull() ?: System.currentTimeMillis(), repeatMode, code, message)
                    }
                }
            ) {
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
fun ScheduledTaskCard(task: ScheduledTask, onDelete: () -> Unit) {
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
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = AdminAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = task.action.name.replace("_", " "),
                        fontWeight = FontWeight.SemiBold,
                        color = AdminTextPrimary,
                        fontSize = 14.sp
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDelete() }
                )
            }
            Text(
                text = "Repeat: ${task.repeat.name}",
                color = AdminTextSecondary,
                fontSize = 12.sp
            )
            if (task.message.isNotBlank()) {
                Text(
                    text = task.message,
                    color = AdminTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2
                )
            }
        }
    }
}
