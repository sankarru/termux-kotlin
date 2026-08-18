package com.termux.app.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.app.TermuxActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State for the quick-commands floating panel (opened by double-tapping the terminal). */
class QuickCommandsStateHolder {
    var isVisible by mutableStateOf(false)
    var commandsVersion by mutableStateOf(0)

    fun refresh() {
        commandsVersion++
    }
}

/**
 * The quick-commands floating panel: a bottom sheet of compact command chips that flow into as many
 * columns as fit, reusing horizontal space (j-code widget style). Double-tapping the terminal opens
 * it; tapping a chip runs it; long-pressing deletes it. The panel ime-pads above the soft keyboard
 * and the corner plus button saves a new command typed into the inline field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickCommandsOverlay(
    activity: TermuxActivity,
    stateHolder: QuickCommandsStateHolder,
    onShowKeyboard: () -> Unit
) {
    if (!stateHolder.isVisible) return

    // Animate the sheet in and out instead of popping: a slide-up/fade enter mirrors the drawer's
    // pop-in, and on close the exit animation plays before the panel actually dismisses.
    val scope = rememberCoroutineScope()
    var animating by remember { mutableStateOf(false) }

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        if (!animating) {
            animating = true
            scope.launch {
                delay(180)
                activity.closeQuickCommandsPanel()
                if (afterDismiss != null) {
                    // Toggle the keyboard only once the dialog window is fully torn down and the
                    // terminal has focus again; toggling while the dialog owns focus fights the
                    // dialog's own IME state and gets undone by the teardown.
                    delay(200)
                    afterDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = !animating,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(180))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(0.dp)
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Commands",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { dismiss(onShowKeyboard) }) {
                            Icon(
                                Icons.Rounded.Keyboard,
                                contentDescription = "Keyboard",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { dismiss() }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val commands = remember(stateHolder.commandsVersion) { activity.listQuickCommands() }

                if (commands.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No quick commands yet.\nUse + to save one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // j-code widget style: compact chips that flow into multiple columns, sized to
                    // their content, so short commands never waste a full row.
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        commands.forEach { command ->
                            QuickCommandChip(
                                command = command,
                                onRun = {
                                    activity.runQuickCommand(command)
                                    dismiss()
                                },
                                onDelete = {
                                    activity.deleteQuickCommand(command)
                                    stateHolder.refresh()
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                var addText by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Command") },
                    placeholder = { Text("e.g. ls -la") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(0.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (addText.isNotBlank()) {
                            activity.saveQuickCommand(addText)
                            addText = ""
                            stateHolder.refresh()
                        }
                    },
                    enabled = addText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
                }
            }
            }
        }
    }
}

@Composable
private fun QuickCommandChip(
    command: String,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(0.dp))
            .combinedClickable(onClick = onRun, onLongClick = onDelete)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = "Run",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
