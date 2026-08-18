package com.termux

/**
 * Minimal surface of a terminal session the j-code [TerminalView] renders against.
 *
 * Implemented by [com.termux.terminal.TerminalSession], which owns the [PtyProcess] and [VtParser]
 * and a background reader that feeds the parser. The view is a pure renderer + input surface bound
 * to a session via [TerminalView.bind].
 */
interface JTermSession {

    /** The pseudo-terminal the session's shell runs in. */
    val pty: PtyProcess

    /** The native VT parser rendering the session's output. */
    val parser: VtParser

    /** Current terminal size in cells. */
    var cols: Int
    var rows: Int

    /**
     * Invoked off the main thread by the session reader whenever new output has been parsed, so the
     * bound view can repaint (hopping to the main thread itself). Null when no view is bound.
     */
    var onUpdate: (() -> Unit)?

    /**
     * Snapshot of the parser's DEC-mode bitfield, published by the session reader under the session
     * lock so modes + alternate-screen stay mutually consistent.
     */
    var inputModesSnapshot: Int

    /**
     * Name of the foreground program reported via shell integration, or null when the shell is at a
     * prompt — used to decide whether a mouse-reporting program is plausibly consuming input.
     */
    val foreground: String?

    /** Resize the PTY and parser to a new cell size. */
    fun resize(newCols: Int, newRows: Int)
}
