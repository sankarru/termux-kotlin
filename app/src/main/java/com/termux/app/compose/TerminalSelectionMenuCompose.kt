package com.termux.app.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.termux.app.TermuxActivity
import kotlin.math.roundToInt

/** Long-press selection menu state: visible + the touch point in the terminal view's coordinates. */
class TerminalSelectionMenuStateHolder {
    var isVisible by mutableStateOf(false)
    var x by mutableStateOf(0f)
    var y by mutableStateOf(0f)
}

/**
 * The j-code-style long-press menu (CompactContextMenu), rendered at the touch point inside the
 * terminal's Box so the stored x/y align with the terminal view's own coordinates. Mirrors j-code:
 * a quick-actions icon row (Copy only when a selection is active, Paste) above a divider, then
 * icon+label rows: Select text, Select all, Clear.
 */
@Composable
fun TerminalSelectionMenuOverlay(
    activity: TermuxActivity,
    stateHolder: TerminalSelectionMenuStateHolder
) {
    if (!stateHolder.isVisible) return
    val view = activity.mTerminalView ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { stateHolder.isVisible = false }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .offset { IntOffset(stateHolder.x.roundToInt(), stateHolder.y.roundToInt()) }
        ) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (view.hasSelection()) {
                        QuickActionIcon(Icons.Rounded.ContentCopy, "Copy") {
                            stateHolder.isVisible = false
                            view.contextCopy()
                        }
                    }
                    QuickActionIcon(Icons.Rounded.ContentPaste, "Paste") {
                        stateHolder.isVisible = false
                        view.contextPaste()
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                ListActionRow(Icons.Rounded.TextFields, "Select text") {
                    stateHolder.isVisible = false
                    view.beginTextSelection()
                }
                ListActionRow(Icons.Rounded.SelectAll, "Select all") {
                    stateHolder.isVisible = false
                    view.contextSelectAll()
                }
                ListActionRow(Icons.Rounded.ClearAll, "Clear") {
                    stateHolder.isVisible = false
                    view.contextClear()
                }
            }
        }
    }
}

@Composable
private fun QuickActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .align(Alignment.Center)
        )
    }
}

@Composable
private fun ListActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
