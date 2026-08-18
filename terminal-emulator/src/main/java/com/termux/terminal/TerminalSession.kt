package com.termux.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.termux.JTermSession
import com.termux.PtyProcess
import com.termux.VtParser
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess is executed by the constructor (an eager spawn into a [PtyProcess] with a default
 * 80x24 size). A background thread reads the pty and feeds the j-code [VtParser]; a bound
 * [com.termux.TerminalView] repaints through [onUpdate]. All [TerminalSessionClient] callbacks are
 * made on the main thread.
 * <p>
 * The child process may be exited forcefully by using the [finishIfRunning] method.
 * <p>
 * NOTE: The terminal session may outlive the view, so be careful with callbacks!
 */
class TerminalSession(
    private val mShellPath: String?,
    private val mCwd: String?,
    private val mArgs: Array<String>?,
    private val mEnv: Array<String>?,
    @Suppress("UNUSED_PARAMETER") private val mTranscriptRows: Int?,
    client: TerminalSessionClient?
) : TerminalOutput(), JTermSession {

    @JvmField
    val mHandle: String = UUID.randomUUID().toString()

    @JvmField
    internal var mEmulator: JCodeTerminalEmulator? = null

    val emulator: JCodeTerminalEmulator?
        get() = mEmulator

    /** Callback which gets notified when a session finishes or changes title. */
    @JvmField
    internal var mClient: TerminalSessionClient? = client

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    @Volatile
    @JvmField
    internal var mShellPid = 0

    val pid: Int
        get() = mShellPid

    /** The exit status of the shell process. Only valid if [mShellPid] is -1. */
    @JvmField
    internal var mShellExitStatus = 0

    val exitStatus: Int
        get() = synchronized(this) { mShellExitStatus }

    /** Set by the application for user identification of session, not by terminal. */
    @JvmField
    var mSessionName: String? = null

    @JvmField
    val mMainThreadHandler: Handler = MainThreadHandler()

    // ---- JTermSession ----

    override lateinit var pty: PtyProcess
        private set
    override lateinit var parser: VtParser
        private set

    override var cols: Int = 80
    override var rows: Int = 24
    override var onUpdate: (() -> Unit)? = null
    override var inputModesSnapshot: Int = 0
    override val foreground: String? = null

    /** The terminal title as set through escape sequences or the shell-integration tab event. */
    @Volatile
    private var mTitle: String? = null

    val title: String?
        get() = mTitle

    /** Guards against double session-finished handling. */
    private var mSessionFinished = false

    init {
        // Eager spawn: the shell starts immediately in a default-sized pty. The first real size
        // comes from the bound view's layout, which resizes pty and parser together.
        initializeEmulator(80, 24)
    }

    /** Inform the attached pty and parser of the new size. */
    fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        resize(columns, rows)
    }

    /** Resize the PTY and parser to a new cell size. */
    override fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (cols == newCols && rows == newRows) return
        cols = newCols
        rows = newRows
        synchronized(this) {
            if (::parser.isInitialized && parser.isOpen) parser.resize(newRows, newCols)
        }
        if (::pty.isInitialized) runCatching { pty.resize(newCols, newRows) }
    }

    /**
     * Spawn the shell process and start terminal I/O.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    @JvmOverloads
    fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int = 0, cellHeightPixels: Int = 0) {
        if (mShellPid != 0) return
        this.cols = columns
        this.rows = rows

        try {
            val argv = (mArgs ?: emptyArray()).ifEmpty { arrayOf(mShellPath ?: "") }
            pty = PtyProcess.create(
                mShellPath ?: "",
                argv.toList(),
                mEnv?.toList() ?: emptyList(),
                mCwd,
                columns,
                rows
            )
            parser = VtParser(rows, columns)
        } catch (e: Exception) {
            mShellPid = -1
            mClient?.setTerminalShellPid(this, -1)
            mMainThreadHandler.post {
                synchronized(this) {
                    if (mSessionFinished) return@post
                    mSessionFinished = true
                }
                mClient?.onSessionFinished(this)
            }
            return
        }

        mEmulator = JCodeTerminalEmulator(this)
        mShellPid = pty.getChildPid()
        mClient?.setTerminalShellPid(this, mShellPid)

        object : Thread("TermSessionInputReader[pid=$mShellPid]") {
            override fun run() {
                val buffer = ByteArray(8192)
                try {
                    while (mShellPid > 0 && pty.isOpen) {
                        val n = try {
                            pty.read(buffer)
                        } catch (e: Exception) {
                            // Ignore, just shutting down.
                            -1
                        }
                        when {
                            n > 0 -> {
                                // Mutate the parser and the mode snapshot under the session lock so
                                // the view's reader-side scrollbackSize/scrollbackPushed read is one
                                // consistent pair.
                                synchronized(this@TerminalSession) {
                                    if (parser.isOpen) {
                                        parser.feed(buffer, n)
                                        inputModesSnapshot = parser.inputModes()
                                    }
                                }
                                // Answerback replies (DA/DSR/CPR/DECRQM/OSC-color queries) must reach
                                // the pty or programs block waiting on them.
                                if (parser.isOpen) {
                                    parser.takeResponses()?.let { resp ->
                                        if (pty.isOpen) runCatching { pty.write(resp) }
                                    }
                                }
                                processOscEvents()
                                onUpdate?.invoke()
                            }
                            n < 0 -> break
                            else -> {
                                // Timeout while idle: re-check session state and keep reading. Only
                                // exit when the pty/session is actually gone — the timeout exists so
                                // teardown notices (close of an fd poll() won't wake on) are seen.
                                if (!pty.awaitReadable(1000) && (!pty.isOpen || mShellPid <= 0)) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore, just shutting down.
                }
            }
        }.start()

        object : Thread("TermSessionWaiter[pid=$mShellPid]") {
            override fun run() {
                val processExitCode = pty.waitForExit()
                mMainThreadHandler.sendMessage(
                    mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode)
                )
            }
        }.start()
    }

    /**
     * Handle shell-integration OSC events drained from the native parser (clipboard write 52, tab
     * title 7712). Runs on the reader thread; client callbacks are posted to the main thread.
     */
    private fun processOscEvents() {
        if (!parser.isOpen) return
        for ((code, payload) in parser.drainOsc()) {
            when (code) {
                52 -> {
                    // Clipboard write: OSC 52 c;<base64>. Queries are filtered; we never report the
                    // clipboard back.
                    if (payload == "?") continue
                    val decoded = try {
                        String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (decoded != null) {
                        mMainThreadHandler.post { mClient?.onCopyTextToClipboard(this, decoded) }
                    }
                }
                7712 -> {
                    // Tab title reported via shell integration.
                    mTitle = payload.takeIf { it.isNotBlank() }
                    mMainThreadHandler.post { mClient?.onTitleChanged(this) }
                }
            }
        }
    }

    /** Write data to the shell process. */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (mShellPid > 0 && ::pty.isInitialized && pty.isOpen) {
            try {
                pty.write(data, offset, count)
            } catch (e: IllegalStateException) {
                // PTY closed while writing — drop.
            }
        }
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8, optionally prefixed by ESC. */
    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (codePoint > 1114111 || (codePoint in 0xD800..0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw IllegalArgumentException("Invalid code point: $codePoint")
        }

        var bufferPosition = 0
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27.toByte()

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = codePoint.toByte()
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b11000000 or (codePoint shr 6)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or (codePoint and 0b111111)).toByte()
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b11100000 or (codePoint shr 12)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or (codePoint and 0b111111)).toByte()
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b11110000 or (codePoint shr 18)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or ((codePoint shr 12) and 0b111111)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (0b10000000 or (codePoint and 0b111111)).toByte()
        }
        write(mUtf8InputBuffer, 0, bufferPosition)
    }

    private val mUtf8InputBuffer = ByteArray(5)

    /** Notify the [mClient] that the screen has changed. */
    protected fun notifyScreenUpdate() {
        mClient?.onTextChanged(this)
    }

    /** Reset state for terminal emulator state. */
    fun reset() {
        mEmulator?.reset()
        notifyScreenUpdate()
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    fun finishIfRunning() {
        if (isRunning) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.message)
            }
        }
    }

    /** Cleanup resources when the process exits. The parser stays open so the final screen remains viewable. */
    internal fun cleanupResources(exitStatus: Int) {
        synchronized(this) {
            mShellPid = -1
            mShellExitStatus = exitStatus
        }
        if (::pty.isInitialized) runCatching { pty.close() }
    }

    val isRunning: Boolean
        get() = synchronized(this) { mShellPid != -1 }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        mClient?.onTitleChanged(this)
    }

    override fun onCopyTextToClipboard(text: String) {
        mClient?.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        mClient?.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        mClient?.onBell(this)
    }

    override fun onColorsChanged() {
        mClient?.onColorsChanged(this)
    }

    /** Change the callback for communication between [TerminalSession] and its client. */
    fun updateTerminalSessionClient(client: TerminalSessionClient?) {
        mClient = client
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    val cwd: String?
        get() {
            if (mShellPid < 1) {
                return null
            }
            try {
                val cwdSymlink = "/proc/$mShellPid/cwd/"
                val outputPath = File(cwdSymlink).canonicalPath
                var outputPathWithTrailingSlash = outputPath
                if (!outputPath.endsWith("/")) {
                    outputPathWithTrailingSlash += '/'
                }
                if (cwdSymlink != outputPathWithTrailingSlash) {
                    return outputPath
                }
            } catch (e: Exception) {
                if (e is IOException || e is SecurityException) {
                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e)
                } else {
                    throw e
                }
            }
            return null
        }

    /**
     * The Linux-guest working directory for this session: the guest path the shell sees plus the host
     * path that file-manager operations should act on. Unlike [cwd] (which reads the real kernel
     * `/proc/PID/cwd`), this understands that proot fakes getcwd/chdir without performing a real
     * kernel chdir, so a `proot-distro login` session's `/proc/PID/cwd` stays at proot's launch
     * directory while the guest shell reports a translated path like `/root`.
     *
     * Resolution order:
     *  1. Walk the session's process tree for a `proot` process and parse its `--rootfs=`/`-r` and
     *     `--cwd=`/`-w` arguments (the guest cwd maps to `rootfs + guestPath` on the host).
     *  2. Otherwise fall back to [resolveProcessLinuxDir]: the kernel cwd, translated when the shell
     *     itself is proot.
     */
    val linuxCwd: LinuxWorkingDirectory?
        get() {
            if (mShellPid < 1) {
                return null
            }
            return sessionGuestDir(mShellPid) ?: resolveProcessLinuxDir(mShellPid)
        }

    /**
     * Breadth-first walk of the process tree rooted at [shellPid] looking for a proot process that
     * defines the guest filesystem view for the session. The container process is a descendant of
     * this session's shell (the user runs `proot-distro login` in the session), so the walk starts
     * from the session shell and descends through children. Readable child lists are not available
     * on every kernel (`/proc/PID/task/PID/children` is missing on some Android builds), so the
     * parent map is derived by scanning `/proc/[pid]/status` PPid lines instead.
     */
    private fun sessionGuestDir(shellPid: Int): LinuxWorkingDirectory? {
        val childrenByParent = HashMap<Int, MutableList<Int>>()
        val procDir = File("/proc")
        val procEntries = procDir.listFiles { f -> f.name.toIntOrNull() != null } ?: return null
        for (proc in procEntries) {
            val pid = proc.name.toInt()
            val ppid = readStatusPpid(proc) ?: continue
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val queue = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        queue.add(shellPid)
        visited.add(shellPid)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            prootArgs(pid)?.let { (rootfs, guestCwd) ->
                return LinuxWorkingDirectory(
                    guestPath = guestCwd,
                    hostPath = rootfs + guestCwd,
                )
            }
            for (child in childrenByParent[pid].orEmpty()) {
                if (visited.add(child)) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    /** The Linux-guest working directory: what the shell reports plus the host path to operate on. */
    data class LinuxWorkingDirectory(val guestPath: String, val hostPath: String)

    @SuppressLint("HandlerLeak")
    inner class MainThreadHandler : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_PROCESS_EXITED -> {
                    synchronized(this@TerminalSession) {
                        if (mSessionFinished) return
                        mSessionFinished = true
                    }

                    val exitCode = msg.obj as Int
                    cleanupResources(exitCode)

                    var exitDescription = "\r\n[Process completed"
                    if (exitCode > 0) {
                        // Non-zero process exit.
                        exitDescription += " (code $exitCode)"
                    } else if (exitCode < 0) {
                        // Negated signal.
                        exitDescription += " (signal ${-exitCode})"
                    }
                    exitDescription += " - press Enter]"

                    val bytesToWrite = exitDescription.toByteArray(Charsets.UTF_8)
                    synchronized(this@TerminalSession) {
                        if (parser.isOpen) {
                            parser.feed(bytesToWrite, bytesToWrite.size)
                            inputModesSnapshot = parser.inputModes()
                        }
                    }
                    notifyScreenUpdate()

                    mClient?.onSessionFinished(this@TerminalSession)
                }
            }
        }
    }

    companion object {
        private const val MSG_PROCESS_EXITED = 4
        private const val LOG_TAG = "TerminalSession"

        /**
         * Resolve the Linux-guest working directory for an arbitrary process: proot process (parse
         * its `--rootfs`/`--cwd`), or plain Termux (the kernel cwd as-is).
         */
        fun resolveProcessLinuxDir(pid: Int): LinuxWorkingDirectory? {
            if (pid < 1) {
                return null
            }
            // 1) proot process itself.
            prootArgs(pid)?.let { (rootfs, guestCwd) ->
                return LinuxWorkingDirectory(
                    guestPath = guestCwd,
                    hostPath = rootfs + guestCwd,
                )
            }
            // 2) plain: kernel cwd is real.
            val kernelCwd = try {
                File("/proc/$pid/cwd/").canonicalPath
            } catch (e: Exception) {
                null
            } ?: return null
            return LinuxWorkingDirectory(guestPath = kernelCwd, hostPath = kernelCwd)
        }

        /** The PID's parent from its /proc/pid/status PPid line, or null. */
        private fun readStatusPpid(proc: File): Int? = try {
            val statusText = File(proc, "status").readText()
            statusText.lineSequence()
                .mapNotNull { line ->
                    if (line.startsWith("PPid:")) line.substringAfter(':').trim().toIntOrNull() else null
                }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }

        /** If [pid] is a proot process, parse its guest cwd, else null. */
        private fun prootArgs(pid: Int): Pair<String, String>? = try {
            val cmdline = File("/proc/$pid/cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .split('\u0000')
                .filter { it.isNotBlank() }
            if (cmdline.isEmpty() || !cmdline[0].endsWith("proot")) {
                return null
            }
            val rootfs = findArg(cmdline, listOf("--rootfs", "-r")) ?: return null
            val guestCwd = findArg(cmdline, listOf("--cwd", "-w")) ?: "/"
            val normalizedRootfs = rootfs.trimEnd('/')
            val normalizedCwd = if (guestCwd.startsWith("/")) guestCwd else "/$guestCwd"
            normalizedRootfs to normalizedCwd
        } catch (e: Exception) {
            null
        }

        /** Value of the first of [flags] present in [cmdline], supporting both `--flag=value` and `--flag value`. */
        private fun findArg(cmdline: List<String>, flags: List<String>): String? {
            for (i in cmdline.indices) {
                val token = cmdline[i]
                for (flag in flags) {
                    if (token == flag && i + 1 < cmdline.size) {
                        return cmdline[i + 1]
                    }
                    if (token.startsWith("$flag=")) {
                        return token.substringAfter('=')
                    }
                }
            }
            return null
        }
    }
}
