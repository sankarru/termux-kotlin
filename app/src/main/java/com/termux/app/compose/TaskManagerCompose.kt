package com.termux.app.compose

import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.app.TermuxActivity
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Host device RAM (the Android device), read from /proc/meminfo. */
private data class HostMemory(val availKb: Long, val totalKb: Long)

/** One app-ownable Linux process from /proc, with resident memory. */
private data class AppProcess(val pid: Int, val name: String, val rssKb: Long, val cwd: String?)

/** State for the task-manager panel (opened from the left drawer). */
class TaskManagerStateHolder {
    var isOpen by mutableStateOf(false)
}

/**
 * The task-manager panel (j-code Tasks style): host RAM gauge, the app's terminal sessions with a
 * per-session close button, and the app's own /proc process list (name, pid, memory) with a per-
 * process kill. Live-refreshed every 2s while open; processes are read on a background thread.
 */
@Composable
fun TaskManagerOverlay(
    activity: TermuxActivity,
    sessions: List<TermuxSession>,
    onCloseSession: (TermuxSession) -> Unit,
    stateHolder: TaskManagerStateHolder
) {
    if (!stateHolder.isOpen) return

    var processes by remember { mutableStateOf<List<AppProcess>>(emptyList()) }
    var hostMemory by remember { mutableStateOf<HostMemory?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val (procs, mem) = withContext(Dispatchers.IO) { AppProcesses.list() to readHostMemory() }
            processes = procs
            hostMemory = mem
            delay(2_000L)
        }
    }

    Dialog(
        onDismissRequest = { stateHolder.isOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Task Manager",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { stateHolder.isOpen = false }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                hostMemory?.let { mem ->
                    TaskSectionLabel("Device")
                    HostMemoryRow(mem)
                }

                TaskSectionLabel("Sessions")
                if (sessions.isEmpty()) {
                    Text(
                        text = "Nothing running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                sessions.forEach { session ->
                    TaskRow(
                        title = session.terminalSession?.title ?: "Terminal",
                        subtitle = session.terminalSession?.mSessionName?.takeIf { it.isNotEmpty() }
                            ?: session.terminalSession?.title ?: "",
                        cwd = session.terminalSession?.linuxCwd?.guestPath ?: session.terminalSession?.cwd,
                        onStop = { onCloseSession(session) }
                    )
                }

                val totalMb = processes.sumOf { it.rssKb } / 1024
                TaskSectionLabel("Processes · ${processes.size} · $totalMb MB")
                Surface(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column {
                        processes.forEach { proc ->
                            val isSelf = proc.pid == android.os.Process.myPid()
                            ProcessRow(
                                proc = proc,
                                isSelf = isSelf,
                                onKill = {
                                    runCatching { Os.kill(proc.pid, OsConstants.SIGTERM) }
                                    processes = processes.filterNot { it.pid == proc.pid }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun TaskRow(
    title: String,
    subtitle: String,
    cwd: String?,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!cwd.isNullOrEmpty()) {
                    Text(
                        text = cwd,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Close session",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Host device RAM overview: available (free) memory prominent, with a used-memory gauge. */
@Composable
private fun HostMemoryRow(mem: HostMemory) {
    val usedKb = (mem.totalKb - mem.availKb).coerceAtLeast(0)
    val usedFraction = if (mem.totalKb > 0) (usedKb.toFloat() / mem.totalKb).coerceIn(0f, 1f) else 0f
    val availGb = mem.availKb / 1_048_576.0
    val totalGb = mem.totalKb / 1_048_576.0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Device memory",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "%.1f GB free · %.1f GB".format(availGb, totalGb),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        }
    }
}

@Composable
private fun ProcessRow(proc: AppProcess, isSelf: Boolean, onKill: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 1.dp, bottom = 1.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSelf) "${proc.name} (app)" else proc.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!proc.cwd.isNullOrEmpty()) {
                Text(
                    text = proc.cwd,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "pid ${proc.pid}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${proc.rssKb / 1024} MB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isSelf) {
            // Killing our own process would just crash the app; keep the slot for alignment.
            Box(
                modifier = Modifier.size(36.dp).clickable(enabled = false) {}
            )
        } else {
            IconButton(onClick = onKill) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Kill process",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * The app's own Linux processes, as Android counts them. Android mounts /proc with hidepid for apps,
 * so listing it yields exactly this app's tree: the app process plus every shell/proot process it
 * forked. Heaviest first.
 */
private object AppProcesses {
    fun list(): List<AppProcess> {
        val pageKb = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(4L)
        return ownProcDirs().mapNotNull { dir ->
            runCatching {
                val pid = dir.name.toInt()
                val cmdline = File(dir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .split(Char(0))
                    .firstOrNull { it.isNotBlank() }
                val comm = File(dir, "comm").readText().trim()
                val name = (cmdline?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: comm)
                    .ifBlank { "pid $pid" }
                val rssPages = File(dir, "statm").readText().split(' ').getOrNull(1)?.toLongOrNull() ?: 0L
                val cwd = com.termux.terminal.TerminalSession.resolveProcessLinuxDir(pid)?.guestPath
                    ?: runCatching { File(dir, "cwd").canonicalPath }.getOrNull()
                        ?.takeIf { it.startsWith("/") }
                AppProcess(pid = pid, name = name, rssKb = rssPages * pageKb, cwd = cwd)
            }.getOrNull()
        }.sortedByDescending { it.rssKb }
    }

    private fun ownProcDirs(): List<File> {
        val myUid = android.os.Process.myUid()
        return File("/proc").listFiles().orEmpty().filter { dir ->
            dir.name.toIntOrNull() != null &&
                runCatching { Os.stat(dir.path).st_uid }.getOrNull() == myUid
        }
    }
}

/** Host device RAM from /proc/meminfo — MemTotal and MemAvailable (kB). Null if unparseable. */
private fun readHostMemory(): HostMemory? = runCatching {
    var total = 0L
    var avail = 0L
    File("/proc/meminfo").forEachLine { line ->
        when {
            line.startsWith("MemTotal:") ->
                total = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: total
            line.startsWith("MemAvailable:") ->
                avail = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: avail
        }
    }
    if (total > 0L) HostMemory(availKb = avail, totalKb = total) else null
}.getOrNull()
