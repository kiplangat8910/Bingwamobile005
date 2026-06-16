package com.bingwa.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class RepoAccessMode { ALL, SELECTED }

@Composable
fun GithubInstallLikeScreen() {
    var access by remember { mutableStateOf(RepoAccessMode.SELECTED) }
    val selectedRepos = remember { mutableStateListOf("examsgenerators/Bingwamobile005") }
    var repoMenu by remember { mutableStateOf(false) }
    val availableRepos = remember {
        listOf(
            "examsgenerators/Bingwamobile005",
            "examsgenerators/portal-web",
            "examsgenerators/infra"
        )
    }

    Column(
        Modifier.fillMaxSize()
            .background(C.bg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 22.dp)
    ) {
        TopHeader()

        Spacer(Modifier.height(10.dp))
        SectionLabel("Archives")
        MenuRow(icon = Icons.Outlined.Security, title = "Security log")
        MenuRow(icon = Icons.Outlined.Archive, title = "Sponsorship log")

        Spacer(Modifier.height(14.dp))
        SectionLabel("Developer settings")

        AppCard(
            name = "Trae-AI",
            metaLeft = "Installed 2 days ago",
            metaMid = "Developed by Trae-AI",
            metaRight = "https://www.trae.ai"
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("Permissions")
        PermissionsCard(
            items = listOf(
                "Read access to actions, commit statuses, deployments, metadata, packages, and pages.",
                "Read and write access to checks, code, discussions, issues, pull requests, and workflows."
            )
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("Repository access")
        Surface(
            color = C.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                RepoAccessOption(
                    title = "All repositories",
                    desc = "This applies to all current and future repositories owned by the resource owner. Also includes public repositories (read-only).",
                    selected = access == RepoAccessMode.ALL,
                    onSelect = { access = RepoAccessMode.ALL }
                )

                Spacer(Modifier.height(10.dp))
                Divider(color = C.w08)
                Spacer(Modifier.height(10.dp))

                RepoAccessOption(
                    title = "Only select repositories",
                    desc = "Select at least one repository. Also includes public repositories (read-only).",
                    selected = access == RepoAccessMode.SELECTED,
                    onSelect = { access = RepoAccessMode.SELECTED }
                )

                AnimatedVisibility(visible = access == RepoAccessMode.SELECTED) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { repoMenu = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, C.border),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = C.cardHi),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Folder, null, tint = C.t2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Select repositories", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = C.t2, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(
                                expanded = repoMenu,
                                onDismissRequest = { repoMenu = false },
                                modifier = Modifier.background(C.cardHi).clip(RoundedCornerShape(14.dp)).border(1.dp, C.border, RoundedCornerShape(14.dp))
                            ) {
                                availableRepos.forEach { repo ->
                                    val alreadySelected = repo in selectedRepos
                                    DropdownMenuItem(
                                        text = { Text(repo, color = if (alreadySelected) C.t3 else C.t1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Outlined.Folder, null, tint = if (alreadySelected) C.t3 else C.t2, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            if (!alreadySelected) selectedRepos.add(repo)
                                            repoMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("Selected ${selectedRepos.size} repository", color = C.t3, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))

                        selectedRepos.forEach { repo ->
                            SelectedRepoRow(
                                repo = repo,
                                onRemove = { selectedRepos.remove(repo) }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {},
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.green, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {},
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, C.border),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = C.surface, contentColor = C.t1)
            ) {
                Text("Cancel", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(22.dp))
        DangerZone()
    }
}

@Composable
private fun TopHeader() {
    Surface(color = C.surface, border = BorderStroke(1.dp, C.w04), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Tune, null, tint = C.t2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scheduled reminders", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.MoreHoriz, null, tint = C.t2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = C.t3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Surface(
        color = C.surface,
        border = BorderStroke(1.dp, C.w04),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable {}.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = C.t2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AppCard(name: String, metaLeft: String, metaMid: String, metaRight: String) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(C.surface)
                        .border(1.dp, C.border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Code, null, tint = C.green, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaChip(icon = Icons.Outlined.Shield, text = metaLeft)
                        Spacer(Modifier.width(10.dp))
                        MetaChip(icon = Icons.Outlined.Settings, text = metaMid)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Link, null, tint = C.t2, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(metaRight, color = C.cyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(C.w04).border(1.dp, C.w08, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, tint = C.t2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = C.t2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = C.t1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PermissionsCard(items: List<String>) {
    Surface(
        color = C.card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, C.border.copy(alpha = 0.85f)),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            items.forEachIndexed { idx, s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.TaskAlt, null, tint = C.green, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(s, color = C.t2, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (idx != items.lastIndex) Divider(color = C.w08, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

@Composable
private fun RepoAccessOption(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect() },
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = C.cyan, unselectedColor = C.t3)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(title, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(desc, color = C.t3, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun SelectedRepoRow(repo: String, onRemove: () -> Unit) {
    Surface(
        color = C.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.w08),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Folder, null, tint = C.t2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(repo, color = C.t1, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = C.t3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DangerZone() {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Danger zone", color = C.red, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Surface(
            color = C.card,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, C.red.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Outlined.WarningAmber, null, tint = C.red, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Suspend your installation", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("This will block the app access to your resources.", color = C.t2, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

