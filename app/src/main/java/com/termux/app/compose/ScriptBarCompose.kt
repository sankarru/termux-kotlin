package com.termux.app.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.app.TermuxActivity
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** State for the right-side script bar and its in-bar script editor. */
class ScriptBarStateHolder {
    var isOpen by mutableStateOf(false)
    var showEditor by mutableStateOf(false)
    var scriptsVersion by mutableStateOf(0)

    fun refresh() {
        scriptsVersion++
    }
}

/**
 * The right-side script bar: a slide-in panel listing saved scripts. A left-swipe on the terminal
 * stores the current output as a script and opens this bar; tapping a script runs it in the current
 * session; long-pressing deletes it. The header's plus opens the j-code-style script editor panel.
 */
@Composable
fun TermuxScriptBar(
    activity: TermuxActivity,
    stateHolder: ScriptBarStateHolder,
    fileManagerStateHolder: FileManagerStateHolder,
    modifier: Modifier = Modifier
) {
    if (!stateHolder.isOpen) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val panelWidthPx = with(density) { 320.dp.toPx() }
    // Offset from the open position (0) rightward to fully pushed away (panelWidthPx). Drives the
    // bar to follow the finger like the left drawer, and springs back unless pushed past the
    // dismissal threshold — "pushing away" the panel instead of a detached swipe-close.
    val offsetPx = remember { Animatable(0f) }

    // Pop-in like the left drawer: enter fully pushed away and spring in, then user drags can push
    // it back out. The drag detectors read offsetPx, so the animation must settle before drags.
    LaunchedEffect(Unit) {
        offsetPx.snapTo(panelWidthPx)
        offsetPx.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val target = if (offsetPx.value > panelWidthPx * 0.35f) panelWidthPx else 0f
                            if (target > 0f) {
                                offsetPx.animateTo(panelWidthPx, tween(180))
                                stateHolder.isOpen = false
                            } else {
                                offsetPx.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetPx.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetPx.snapTo((offsetPx.value + dragAmount).coerceIn(0f, panelWidthPx))
                        }
                    }
                )
            }
    ) {
        // Scrim dims with the bar's drag position; tap outside closes it.
        val scrimAlpha = (1f - offsetPx.value / panelWidthPx).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(scrimAlpha * 0.5f)
                .background(Color.Black)
                .clickable { stateHolder.isOpen = false }
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(320.dp)
                .offset { IntOffset(offsetPx.value.roundToInt(), 0) }
        ) {
            var selectedTab by rememberSaveable { mutableStateOf(0) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                // Tab row: scripts vs. file manager.
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Scripts") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Files") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    ScriptsTab(activity = activity, stateHolder = stateHolder)
                } else {
                    FileManagerTab(activity = activity, stateHolder = fileManagerStateHolder)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ScriptsTab(
    activity: TermuxActivity,
    stateHolder: ScriptBarStateHolder
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Scripts",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Plus opens the j-code-style script editor panel.
            IconButton(onClick = {
                stateHolder.showEditor = true
            }) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "New script",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = {
                activity.showToast("Importing scripts is not wired up", true)
            }) {
                Icon(
                    Icons.Rounded.FileUpload,
                    contentDescription = "Import script",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    val scripts = remember(stateHolder.scriptsVersion) { activity.listScripts() }

    if (scripts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No scripts yet.\nSwipe left on the terminal to store its output.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(scripts, key = { it.name }) { script ->
                ScriptRow(
                    script = script,
                    onRun = {
                        activity.runScript(script)
                    },
                    onDelete = {
                        activity.deleteScript(script)
                        stateHolder.refresh()
                    }
                )
            }
        }
    }
}

@Composable
private fun ScriptRow(
    script: File,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val preview = remember(script) {
        script.takeIf { it.exists() }?.readText()?.trim()?.lineSequence()?.firstOrNull() ?: ""
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRun, onLongClick = onDelete)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(0.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Run",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * The j-code-style script editor panel: a full-height, dark, monospace text area where a new script
 * is typed and saved with the plus button. A name field sits above the editor; Save writes the
 * script into the bar's store and closes the panel.
 */
@Composable
fun ScriptEditorPanel(
    activity: TermuxActivity,
    stateHolder: ScriptBarStateHolder
) {
    if (!stateHolder.showEditor) return

    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { stateHolder.showEditor = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(0.dp),
        title = {
            Text(
                text = "New script",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Script name") },
                    placeholder = { Text("my_script") },
                    shape = RoundedCornerShape(0.dp)
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    label = { Text("Script body") },
                    placeholder = { Text("#!/data/data/com.termux/files/usr/bin/bash\n…") },
                    shape = RoundedCornerShape(0.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    if (activity.saveScript(name, content) != null) {
                        stateHolder.showEditor = false
                        stateHolder.refresh()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { stateHolder.showEditor = false }) { Text("Cancel") }
        }
    )
}
