package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.Locale

/** State for the tree-style file-manager tab inside the script bar. */
class FileManagerStateHolder {
    // The tree root. Defaults to the Termux home but is user-switchable to the filesystem root "/",
    // so the same browser can reach proot-distro rootfs trees without hardcoded paths.
    var rootPath by mutableStateOf(TermuxConstants.TERMUX_HOME_DIR_PATH)
    // When true the browser roots at the active terminal session's Linux cwd, so a proot-distro
    // login shows the proot filesystem instead of Android's "/".
    var followSession by mutableStateOf(true)
    val expandedPaths = mutableStateMapOf<String, Boolean>()
    var filesVersion by mutableStateOf(0)

    fun refresh() {
        filesVersion++
    }

    fun isExpanded(dir: File): Boolean = expandedPaths[dir.absolutePath] ?: false

    fun toggleExpand(dir: File) {
        val key = dir.absolutePath
        expandedPaths[key] = !(expandedPaths[key] ?: false)
    }
}

/** A node of the flattened visible tree, pre-computed with its indent depth. */
private data class TreeNode(
    val file: File,
    val depth: Int,
    val isDir: Boolean
)

/**
 * A tree-style file browser for the script bar's "Files" tab. Directories render as expandable
 * nodes (`ls`-sorted, directories first); expanding a directory reveals its children indented
 * beneath it. Tapping a regular file opens it for editing with nvim in the current session. The
 * root is not hardcoded: it defaults to `~` but a button jumps to the filesystem root `/`, which
 * also reaches proot-distro rootfs trees.
 */
@Composable
fun FileManagerTab(
    activity: TermuxActivity,
    stateHolder: FileManagerStateHolder,
    modifier: Modifier = Modifier
) {
    // When following the terminal session, the root is the shell's Linux cwd. This is what makes a
    // proot-distro login show the proot rootfs: the shell's Linux cwd maps into the rootfs directory
    // (hostPath is where the real files live; guestPath is what the shell reports, e.g. "/root").
    val sessionLinuxDir = activity.currentSessionLinuxDirectory
    val effectiveRoot = if (stateHolder.followSession && sessionLinuxDir != null) sessionLinuxDir.hostPath
    else stateHolder.rootPath
    val effectiveDisplayPath = if (stateHolder.followSession && sessionLinuxDir != null) sessionLinuxDir.guestPath
    else null
    val root = remember(stateHolder.filesVersion, effectiveRoot) { File(effectiveRoot) }
    // Rebuild the flattened tree whenever the expansion set changes (reading the entries tracks
    // both key additions and value toggles in composition).
    val expandKey = stateHolder.expandedPaths.entries.toList()
    val nodes = remember(root, stateHolder.filesVersion, expandKey) { buildVisibleTree(root, stateHolder) }
    var contextTarget by remember { mutableStateOf<File?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Header: home / root switcher + refresh.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = effectiveDisplayPath ?: root.absolutePath.replace(TermuxConstants.TERMUX_HOME_DIR_PATH, "~"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    stateHolder.followSession = true
                    stateHolder.refresh()
                }
            ) {
                Icon(
                    Icons.Rounded.Home,
                    contentDescription = "Follow session",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = {
                    stateHolder.followSession = false
                    stateHolder.rootPath = "/"
                    stateHolder.refresh()
                }
            ) {
                Icon(
                    Icons.Rounded.StarBorder,
                    contentDescription = "Filesystem root",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { stateHolder.refresh() }) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { contextTarget = root }) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Create in current directory",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Empty directory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(nodes, key = { it.file.absolutePath }) { node ->
                    FileTreeNodeRow(
                        node = node,
                        expanded = stateHolder.isExpanded(node.file),
                        onToggle = {
                            if (node.isDir) {
                                stateHolder.toggleExpand(node.file)
                            } else {
                                activity.openFileInNvim(node.file)
                            }
                        },
                        onLongPress = { contextTarget = node.file }
                    )
                }
            }
        }
    }

    // Long-press context menu for a tree node: new file / new folder / delete / permissions.
    FileNodeContextMenu(
        activity = activity,
        target = contextTarget,
        onDismiss = { contextTarget = null },
        onChanged = {
            contextTarget = null
            stateHolder.refresh()
        }
    )
}

/** A long-pressed tree node plus which sub-dialog (if any) is open. */
private enum class ContextAction { None, NewFile, NewFolder, Delete, Permissions }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileNodeContextMenu(
    activity: TermuxActivity,
    target: File?,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    if (target == null) return

    var action by remember(target) { mutableStateOf(ContextAction.None) }
    var newName by remember(target) { mutableStateOf("") }
    // Permission toggles are remembered so they survive recompositions while the dialog is open.
    var permRead by remember(target) { mutableStateOf(target.canRead()) }
    var permWrite by remember(target) { mutableStateOf(target.canWrite()) }
    var permExecute by remember(target) { mutableStateOf(target.canExecute()) }

    val isDir = target.isDirectory
    // New file/folder are created inside a directory; long-pressing a file creates them next to it.
    val createParent = if (isDir) target else (target.parentFile ?: target)

    fun doDelete() {
        activity.deleteFileNode(target)
        onChanged()
    }

    when (action) {
        ContextAction.None -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(0.dp),
                title = {
                    Text(
                        text = target.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { action = ContextAction.NewFile },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "New file",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
                            )
                        }
                        TextButton(
                            onClick = { action = ContextAction.NewFolder },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "New folder",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
                            )
                        }
                        TextButton(
                            onClick = { action = ContextAction.Delete },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
                            )
                        }
                        TextButton(
                            onClick = { action = ContextAction.Permissions },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Change permissions",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }

        ContextAction.NewFile, ContextAction.NewFolder -> {
            val isFolder = action == ContextAction.NewFolder
            AlertDialog(
                onDismissRequest = { action = ContextAction.None },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(0.dp),
                title = {
                    Text(
                        text = if (isFolder) "New folder" else "New file",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "In: ${createParent.absolutePath.replace(TermuxConstants.TERMUX_HOME_DIR_PATH, "~")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (isFolder) "Folder name" else "File name") },
                            shape = RoundedCornerShape(0.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            if (isFolder) activity.createDirInDir(createParent, newName)
                            else activity.createFileInDir(createParent, newName)
                            onChanged()
                        }
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = { action = ContextAction.None }) { Text("Cancel") }
                }
            )
        }

        ContextAction.Delete -> {
            AlertDialog(
                onDismissRequest = { action = ContextAction.None },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(0.dp),
                title = {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Text(
                        text = "Delete ${if (isDir) "directory" else "file"} ${target.name}?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                confirmButton = {
                    TextButton(onClick = { doDelete() }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { action = ContextAction.None }) { Text("Cancel") }
                }
            )
        }

        ContextAction.Permissions -> {
            AlertDialog(
                onDismissRequest = { action = ContextAction.None },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(0.dp),
                title = {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Read", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = permRead, onCheckedChange = { permRead = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Write", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = permWrite, onCheckedChange = { permWrite = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Execute", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = permExecute, onCheckedChange = { permExecute = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        activity.setFilePermissions(target, permRead, permWrite, permExecute)
                        onChanged()
                    }) { Text("Apply") }
                },
                dismissButton = {
                    TextButton(onClick = { action = ContextAction.None }) { Text("Cancel") }
                }
            )
        }
    }
}

/** Flatten the tree from [root] down, honoring the expanded set, with indent depths. */
private fun buildVisibleTree(root: File, stateHolder: FileManagerStateHolder): List<TreeNode> {
    val result = mutableListOf<TreeNode>()

    fun walk(dir: File, depth: Int) {
        val children = listLsEntries(dir)
        for (child in children) {
            val isDir = child.isDirectory
            result.add(TreeNode(child, depth, isDir))
            if (isDir && stateHolder.isExpanded(child)) {
                walk(child, depth + 1)
            }
        }
    }

    walk(root, 0)
    return result
}

/** `ls`-style listing: children with directories first, then by name (case-insensitive). */
private fun listLsEntries(dir: File): List<File> {
    val children = dir.listFiles() ?: return emptyList()
    val visible = children.filter { !it.isHidden }
    val dirs = visible.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val files = visible.filter { !it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
    return dirs + files
}

@Composable
private fun FileTreeNodeRow(
    node: TreeNode,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit
) {
    val isDir = node.isDir
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 16).dp, end = 4.dp)
            .clip(RoundedCornerShape(0.dp))
            .background(
                if (isDir) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(0.dp)
            )
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isDir) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(0.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDir) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = if (isDir) "Folder" else "File",
                tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isDir) node.file.name + "/" else node.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!isDir) {
            Text(
                text = formatSize(node.file.length()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
}
