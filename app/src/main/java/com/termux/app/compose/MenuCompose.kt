package com.termux.app.compose

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Abc
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.activities.SettingsActivity
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

val TermuxTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace),
    displayMedium = TextStyle(fontFamily = FontFamily.Monospace),
    displaySmall = TextStyle(fontFamily = FontFamily.Monospace),
    headlineLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
)

@Composable
fun TermuxTheme(
    content: @Composable () -> Unit
) {
    // j-code look: all compose UI parts (drawer, panels, menus, settings) derive their palette from
    // the terminal's own color scheme and use the terminal's monospace typeface, so the chrome
    // matches the terminal instead of Material / termux-kotlin's own styling.
    val bgInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND]
    val fgInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND]
    val primaryInt = com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR]

    val bg = Color(bgInt)
    val fg = Color(fgInt)
    val primary = if (primaryInt == 0xffffffff.toInt() || primaryInt == 0xff000000.toInt()) {
        Color(com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors[4]) // dim blue (color 4: 0xff6495ed)
    } else {
        Color(primaryInt)
    }

    val isDark = com.termux.terminal.TerminalColors.getPerceivedBrightnessOfColor(bgInt) < 130

    // Flat j-code look: all container levels are the same solid terminal background with only a thin
    // hairline divider between regions — no Material tonal elevations or rounded elevated cards.
    val dimBg = Color(android.graphics.Color.rgb(
        (android.graphics.Color.red(bgInt) + 18).coerceAtMost(255),
        (android.graphics.Color.green(bgInt) + 18).coerceAtMost(255),
        (android.graphics.Color.blue(bgInt) + 18).coerceAtMost(255)
    ))
    val hairline = fg.copy(alpha = 0.25f)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = bg,
            primaryContainer = dimBg,
            onPrimaryContainer = fg,
            surface = bg,
            onSurface = fg,
            background = bg,
            onBackground = fg,
            surfaceContainer = bg,
            surfaceContainerLow = bg,
            surfaceContainerHigh = dimBg,
            surfaceContainerHighest = dimBg,
            onSurfaceVariant = fg.copy(alpha = 0.7f),
            outline = hairline,
            secondaryContainer = dimBg,
            onSecondaryContainer = fg,
            error = Color(0xffff6b6b.toInt())
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = bg,
            primaryContainer = dimBg,
            onPrimaryContainer = fg,
            surface = bg,
            onSurface = fg,
            background = bg,
            onBackground = fg,
            surfaceContainer = bg,
            surfaceContainerLow = bg,
            surfaceContainerHigh = dimBg,
            surfaceContainerHighest = dimBg,
            onSurfaceVariant = fg.copy(alpha = 0.7f),
            outline = hairline,
            secondaryContainer = dimBg,
            onSecondaryContainer = fg,
            error = Color(0xffff6b6b.toInt())
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TermuxTypography,
        content = content
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun TermuxDrawerContent(
    activity: Activity,
    sessions: List<TermuxSession>,
    currentSession: TermuxSession?,
    onSessionSelected: (TermuxSession) -> Unit,
    onSessionRename: (TermuxSession) -> Unit,
    onSessionKill: (TermuxSession) -> Unit,
    onNewSession: () -> Unit,
    onToggleToolbar: () -> Unit,
    onShowTerminalActions: () -> Unit,
    onShowTaskManager: () -> Unit,
    onShowLetterPanel: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TermuxSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    TermuxTheme {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sessions",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sessions) { index, session ->
                        val isSelected = session == currentSession
                        val backgroundColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "backgroundColor"
                        )
                        val contentColor by animateColorAsState(
                            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "contentColor"
                        )

                        var showContextMenu by remember { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(0.dp))
                                    .background(backgroundColor)
                                    .combinedClickable(
                                        onClick = { onSessionSelected(session) },
                                        onLongClick = { showContextMenu = true }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(0.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    val sessionName = session.terminalSession?.mSessionName
                                    val title = session.terminalSession?.title ?: "Terminal"
                                    if (sessionName?.isNotEmpty() == true) {
                                        Text(
                                            text = sessionName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = contentColor
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.8f)
                                        )
                                    } else {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = contentColor
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showContextMenu,
                                onDismissRequest = { showContextMenu = false },
                                shape = RoundedCornerShape(0.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Kill") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = Color.Red
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        onSessionKill(session)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        renameTarget = session
                                        renameText = session.terminalSession?.mSessionName ?: ""
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Switch to") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.PushPin,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showContextMenu = false
                                        onSessionSelected(session)
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Flat terminal-style buttons: solid highlight block, monospace label.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClick = onNewSession),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "New Session",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }

                // Terminal actions (the panel formerly opened by double-tapping the terminal).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onShowTerminalActions),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Terminal Actions",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Task manager (j-code Tasks style: memory, sessions, processes).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onShowTaskManager),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Task Manager",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Letter panel configuration (custom letters / vim keystrokes row above the keyboard).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onShowLetterPanel),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Abc,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Letters",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(0.dp),
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Session name") },
                    shape = RoundedCornerShape(0.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget!!.terminalSession?.mSessionName = renameText
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}