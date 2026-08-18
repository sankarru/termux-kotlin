package com.termux.terminal

import com.termux.JTermSession
import com.termux.VtParser

/**
 * Facade exposing the parts of the old [TerminalEmulator] the app and termux-shared depend on, now
 * backed by the j-code [JTermSession] (native [com.termux.VtParser] + [com.termux.PtyProcess]).
 *
 * The j-code view renders the screen directly; this facade only answers the queries the termux app
 * makes against the emulator: window/selection colors, buffer reads (via [screen]), mouse/keypad
 * input modes, paste and reset.
 */
class JCodeTerminalEmulator internal constructor(
    private val session: JTermSession
) {

    /** The number of columns in the terminal. */
    val mColumns: Int
        get() = session.cols

    /** The number of rows in the terminal. */
    val mRows: Int
        get() = session.rows

    /**
     * The indexed color scheme read by the app for the window and terminal background. Reset copies
     * the app's [TerminalColors.COLOR_SCHEME] defaults (the theme loaded from color.properties), so
     * after a theme update the app can read `mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]`.
     */
    @JvmField
    val mColors: TerminalColors = TerminalColors()

    /** Read access to the terminal buffer (transcript dumps, selection text, word lookup). */
    @JvmField
    val screen: JCodeTerminalScreen = JCodeTerminalScreen(session)

    private var mAutoScrollDisabled: Boolean = false

    fun isAutoScrollDisabled(): Boolean = mAutoScrollDisabled

    fun toggleAutoScrollDisabled() {
        mAutoScrollDisabled = !mAutoScrollDisabled
    }

    fun shouldCursorBeVisible(): Boolean = session.parser.isOpen && session.parser.isCursorVisible

    fun isAlternateBufferActive(): Boolean = session.parser.isOpen && session.parser.isAlternateScreen

    /** Whether the app requested application cursor keys (?1 DECCKM) — arrows send SS3 form. */
    fun isCursorKeysApplicationMode(): Boolean = (session.inputModesSnapshot and VtParser.MODE_APP_CURSOR_KEYS) != 0

    /**
     * Application keypad mode (?66 DECKPAM). The j-code native parser does not track DEC private
     * mode 66, so this is always false — numeric keypad keys keep sending their normal form.
     */
    fun isKeypadApplicationMode(): Boolean = false

    /** Whether the application enabled mouse reporting (any of ?9/?1000/?1002/?1003). */
    fun isMouseTrackingActive(): Boolean = VtParser.modeMouseMode(session.inputModesSnapshot) != VtParser.MOUSE_OFF

    /** Reset the terminal to its initial state (clears screen and scrollback, resets modes). */
    fun reset() {
        if (session.parser.isOpen) session.parser.reset()
    }

    /**
     * Paste text into the terminal, mirroring the old emulator: strip escape and C1 control
     * characters, translate newlines to carriage returns, and apply bracketed paste wrapping when
     * the application enabled DECSET 2004.
     */
    fun paste(text: String) {
        var sanitized = text
        sanitized = sanitized.replace("(\u001B|[\u0080-\u009F])".toRegex(), "")
        sanitized = sanitized.replace("\r?\n".toRegex(), "\r")
        val pty = session.pty
        if (!pty.isOpen) return
        val bracketed = (session.inputModesSnapshot and VtParser.MODE_BRACKETED_PASTE) != 0
        try {
            if (bracketed) pty.write("\u001B[200~")
            pty.write(sanitized)
            if (bracketed) pty.write("\u001B[201~")
        } catch (e: IllegalStateException) {
            // PTY closed while pasting — drop the remainder.
        }
    }
}
