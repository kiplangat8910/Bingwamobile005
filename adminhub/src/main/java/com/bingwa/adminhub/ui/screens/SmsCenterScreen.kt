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
import androidx.compose.material.icons.filled.TextFields
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
import com.bingwa.adminhub.data.models.SmsTemplate
import com.bingwa.adminhub.data.models.TemplateCategory
import com.bingwa.adminhub.data.repositories.SmsTemplateRepository
import com.bingwa.adminhub.ui.theme.AdminAmber
import com.bingwa.adminhub.ui.theme.AdminTextPrimary
import com.bingwa.adminhub.ui.theme.AdminTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsCenterScreen(templateRepository: SmsTemplateRepository) {
    val templates by templateRepository.templates.collectAsState(initial = emptyList())
    var selectedTemplate by remember { mutableStateOf<SmsTemplate?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
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
                text = "SMS Center",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AdminTextPrimary
            )
            Button(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(4.dp))
                Text("New Template")
            }
        }

        Text(
            text = "Templates",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = AdminTextPrimary
        )

        if (templates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No SMS templates yet. Create one to send messages quickly.",
                    color = AdminTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(templates) { template ->
                    TemplateCard(
                        template = template,
                        onClick = { selectedTemplate = template }
                    )
                }
            }
        }
    }

    if (selectedTemplate != null) {
        TemplateDialog(
            template = selectedTemplate,
            onDismiss = { selectedTemplate = null },
            onSave = { name, body, category ->
                scope.launch {
                    val updated = selectedTemplate!!.copy(name = name, body = body, category = category)
                    templateRepository.updateTemplate(updated)
                    selectedTemplate = null
                }
            },
            onDelete = {
                scope.launch {
                    templateRepository.deleteTemplate(selectedTemplate!!.id)
                    selectedTemplate = null
                }
            }
        )
    }
    if (showCreateDialog) {
        TemplateDialog(
            template = null,
            onDismiss = { showCreateDialog = false },
            onSave = { name, body, category ->
                scope.launch {
                    val newTemplate = SmsTemplate(
                        id = "template_${System.currentTimeMillis()}",
                        name = name,
                        body = body,
                        category = category
                    )
                    templateRepository.addTemplate(newTemplate)
                    showCreateDialog = false
                }
            },
            onDelete = null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDialog(
    template: SmsTemplate?,
    onDismiss: () -> Unit,
    onSave: (String, String, TemplateCategory) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(template?.name ?: "") }
    var body by remember { mutableStateOf(template?.body ?: "") }
    var category by remember { mutableStateOf(template?.category ?: TemplateCategory.CUSTOM) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (template == null) "New Template" else "Edit Template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = category.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        TemplateCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    category = cat
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Body") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && body.isNotBlank()) {
                    onSave(name, body, category)
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun TemplateCard(template: SmsTemplate, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.TextFields,
                    contentDescription = null,
                    tint = AdminAmber,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = template.name,
                    fontWeight = FontWeight.SemiBold,
                    color = AdminTextPrimary,
                    fontSize = 14.sp
                )
            }
            Text(
                text = template.body,
                color = AdminTextSecondary,
                fontSize = 12.sp,
                maxLines = 2
            )
            Text(
                text = template.category.name,
                color = AdminAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
