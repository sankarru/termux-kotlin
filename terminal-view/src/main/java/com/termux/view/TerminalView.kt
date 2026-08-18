package com.termux.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.JCodeTerminalEmulator
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle

/**
 * The termux-facing terminal view, backed by the j-code native renderer ([com.termux.TerminalView])
 * and the [JCodeTerminalEmulator]/[com.termux.terminal.JCodeTerminalScreen] facades.
 *
 * Exposes the same API surface the app and termux-shared use against the old
 * [com.termux.view.TerminalView], forwarding rendering and native input to the j-code view while
 * preserving the app's [TerminalViewClient] interaction: single-tap URL/keyboard handling, sticky
 * ctrl/alt/shift chips from the extra keys row, volume-key virtual keys, back-as-escape and
 * shift+PageUp/PageDown history scrolling.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : com.termux.TerminalView(context, attrs) {

    /** The currently displayed terminal session, whose emulator is [mEmulator]. */
    @JvmField
    var mTermSession: TerminalSession? = null

    /** The facade answering the app's queries against the current session. */
    @JvmField
    var mEmulator: JCodeTerminalEmulator? = null

    @JvmField
    var mClient: TerminalViewClient? = null

    interface OnContextMenuShowListener {
        fun onShowContextMenu(view: View): Boolean
    }

    private var mOnContextMenuShowListener: OnContextMenuShowListener? = null

    fun setOnContextMenuShowListener(listener: OnContextMenuShowListener?) {
        mOnContextMenuShowListener = listener
    }

    private var mTerminalCursorBlinkerRate = 0

    val currentSession: TerminalSession?
        get() = mTermSession

    companion object {
        /** Log terminal view key and IME events. */
        @JvmField
        var TERMINAL_VIEW_KEY_LOGGING_ENABLED = false

        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000

        /** The [KeyEvent] is generated from a virtual keyboard, like manually with the [KeyEvent#KeyEvent(int, int)] constructor. */
        const val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD // -1

        /** The [KeyEvent] is generated from a non-physical device, like if 0 value is returned by [KeyEvent#getDeviceId()]. */
        const val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0

        private const val LOG_TAG = "TerminalView"
    }

    init {
        // Route the j-code confirmed-single-tap to the app client, which opens URLs and raises the
        // soft keyboard (the j-code view opens a token under the finger on its own; that callback is
        // not wired for termux).
        onSingleTapConfirmed = { e ->
            if (mEmulator != null) {
                mClient?.onSingleTapUp(e)
            }
        }

        // Long press pops the j-code-style action menu at the finger (the app renders the
        // CompactContextMenu-equivalent with icons): Copy (only when a selection is active),
        // Paste, Select all, Clear, plus Select text to begin word selection.
        onContextMenu = { x, y ->
            mClient?.onContextMenu(x, y)
        }

        // Double tap opens the quick-commands floating panel (small saved commands), not the
        // terminal actions menu — that is reachable from the left drawer's button instead.
        onDoubleTap = { _, _ ->
            mClient?.onQuickCommandsRequest()
        }

        // A rightward swipe anywhere on the terminal opens the session drawer (the app's edge-swipe
        // gesture is unreliable over the terminal, which claims the whole touch stream).
        onRightSwipe = { mClient?.onDrawerSwipe() }

        // A leftward swipe anywhere on the terminal opens the script bar (mirror of the right swipe).
        onLeftSwipe = { mClient?.onScriptBarSwipe() }

        // Pinch-to-zoom: the host resizes the terminal font size based on the scale factor.
        onScale = { scale -> mClient?.onScale(scale) ?: 1.0f }
    }

    /**
     * @param client The [TerminalViewClient] interface implementation to allow
     * for communication between [TerminalView] and its client.
     */
    fun setTerminalViewClient(client: TerminalViewClient?) {
        mClient = client
    }

    /** Sets whether terminal view key logging is enabled or not. */
    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value
    }

    /**
     * Attach a [TerminalSession] to this view.
     *
     * @param session The [TerminalSession] this view will be displaying.
     */
    fun attachSession(session: TerminalSession): Boolean {
        if (session == mTermSession) return false

        mTermSession = session
        mEmulator = session.emulator

        val emulator = mEmulator
        if (emulator == null) {
            // Spawn failed — nothing to render. The app's own error handling kicks in (the view
            // client exits the activity when mEmulator is null).
            mClient?.onEmulatorSet()
            isVerticalScrollBarEnabled = true
            return true
        }

        bind(session)
        applyColorScheme()

        // Size the terminal now that a session is bound. onSizeChanged may have fired earlier with
        // no session bound (and the first-layout listener removed itself), so bind + resize here to
        // match the old updateSize() contract.
        if (width > 0 && height > 0 && getCellWidth() > 0f && getCellHeight() > 0f) {
            val newCols = (width / getCellWidth()).toInt().coerceAtLeast(1)
            val newRows = (height / getCellHeight()).toInt().coerceAtLeast(1)
            if (newCols != session.cols || newRows != session.rows) {
                resizeTerminal(newCols, newRows)
            }
        }

        setActive(true)
        isVerticalScrollBarEnabled = true

        mClient?.onEmulatorSet()
        return true
    }

    /** Apply the termux color scheme (foreground/background/cursor + 256-color palette) to the view. */
    private fun applyColorScheme() {
        val emulator = mEmulator ?: return
        val current = emulator.mColors.mCurrentColors
        setTerminalColors(
            current[TextStyle.COLOR_INDEX_FOREGROUND],
            current[TextStyle.COLOR_INDEX_BACKGROUND],
            current
        )
    }

    /** Re-apply the color scheme when the font changes, since termux reloads the theme along with it. */
    override fun setTypeface(tf: Typeface) {
        super.setTypeface(tf)
        applyColorScheme()
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    fun setTextSize(textSize: Int) {
        setFontSize(textSize.toFloat())
    }

    /** Refresh the view (called by the app when the current session's text changes). */
    fun onScreenUpdated() {
        invalidate()
    }

    /**
     * Get the zero indexed column and row of the terminal view for the position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the row number will take the scroll position into account.
     * @return Array with the column and row.
     */
    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        return getColumnAndRow(event.x, event.y, relativeToScroll)
    }

    /**
     * The currently selected text (from a long-press token selection) or null when nothing is
     * selected. Used by the app's context menu for "Share selected text".
     */
    fun getStoredSelectedText(): String? {
        val text = getSelectedText()
        return text.takeIf { it.isNotBlank() }
    }

    /** Termux's AutoFill flow is not wired through the j-code view. */
    fun isAutoFillEnabled(): Boolean = false

    /** Unused: AutoFill is disabled ([isAutoFillEnabled] returns false); kept for the app's menu wiring. */
    fun requestAutoFillUsername() = Unit

    /** Unused: AutoFill is disabled ([isAutoFillEnabled] returns false); kept for the app's menu wiring. */
    fun requestAutoFillPassword() = Unit

    /** Called by the hosting activity in [android.app.Activity.onContextMenuClosed]. */
    fun onContextMenuClosed(menu: Menu?) {
        // No stored-text state to clear: getStoredSelectedText() reads the live selection.
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyPreIme(keyCode=$keyCode, event=$event)")
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (hasSelection()) {
                clearSelection()
                return true
            } else if (mClient?.shouldBackButtonBeMappedToEscape() == true) {
                // Intercept back button to treat it as escape:
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (mClient?.shouldUseCtrlSpaceWorkaround() == true &&
            keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event0: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyDown(keyCode=$keyCode, isSystem()=${event0.isSystem}, event=$event0)")
        }
        if (mEmulator == null) return true

        val event = foldChipsIntoEvent(event0)

        if (mClient?.onKeyDown(keyCode, event, mTermSession) == true) {
            invalidate()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Termux maps the back button to ESC when configured; otherwise let it reach the system
            // (the activity may finish).
            if (mClient?.shouldBackButtonBeMappedToEscape() == true) {
                mTermSession?.write("\u001B")
                invalidate()
                return true
            }
            return super.onKeyDown(keyCode, event0)
        } else if (event0.isSystem) {
            return super.onKeyDown(keyCode, event0)
        } else if (event0.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession?.write(event0.characters)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event0)
        }

        // shift+page_up and shift+page_down should scroll scrollback history instead of
        // scrolling command history or changing pages
        val shiftDown = event.isShiftPressed || mClient?.readShiftKey() == true
        if (shiftDown && (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            val rows = mEmulator!!.mRows
            scrollByLines(if (keyCode == KeyEvent.KEYCODE_PAGE_UP) rows else -rows)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "onKeyUp(keyCode=$keyCode, event=$event)")
        }

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (mClient?.onKeyUp(keyCode, event) == true) {
            invalidate()
            return true
        } else if (event.isSystem) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event)
        }

        return true
    }

    /**
     * Input the specified code point, applying the app's control/alt handling (the client's
     * [TerminalViewClient.onCodePoint] hook, ctrl-letter control bytes and left-alt ESC prefix).
     */
    fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        var cp = codePoint
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient?.logInfo(LOG_TAG, "inputCodePoint(eventSource=$eventSource, codePoint=$cp, controlDownFromEvent=$controlDownFromEvent, leftAltDownFromEvent=$leftAltDownFromEvent)")
        }

        if (mTermSession == null) return

        val controlDown = controlDownFromEvent || mClient?.readControlKey() == true
        val altDown = leftAltDownFromEvent || mClient?.readAltKey() == true

        if (mClient?.onCodePoint(cp, controlDown, mTermSession) == true) return

        if (controlDown) {
            if (cp in 'a'.code..'z'.code) {
                cp = cp - 'a'.code + 1
            } else if (cp in 'A'.code..'Z'.code) {
                cp = cp - 'A'.code + 1
            } else if (cp == ' '.code || cp == '2'.code) {
                cp = 0
            } else if (cp == '['.code || cp == '3'.code) {
                cp = 27 // ^[ (Esc)
            } else if (cp == '\\'.code || cp == '4'.code) {
                cp = 28
            } else if (cp == ']'.code || cp == '5'.code) {
                cp = 29
            } else if (cp == '^'.code || cp == '6'.code) {
                cp = 30 // control-^
            } else if (cp == '_'.code || cp == '7'.code || cp == '/'.code) {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                cp = 31
            } else if (cp == '8'.code) {
                cp = 127 // DEL
            }
        }

        if (cp > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect.
                when (cp) {
                    0x02DC -> cp = 0x007E // TILDE (~).
                    0x02CB -> cp = 0x0060 // GRAVE ACCENT (`).
                    0x02C6 -> cp = 0x005E // CIRCUMFLEX ACCENT (^).
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession?.writeCodePoint(altDown, cp)
        }
    }

    /**
     * Fold the extra-keys sticky ctrl/alt/shift chips into the key event's meta state, so the
     * j-code key handling (control bytes, arrows, etc.) behaves like the old view where
     * `controlDown = event.isCtrlPressed || readControlKey()`.
     */
    private fun foldChipsIntoEvent(event: KeyEvent): KeyEvent {
        var meta = event.metaState
        if (mClient?.readControlKey() == true) {
            meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        }
        if (mClient?.readAltKey() == true) {
            meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        }
        if (mClient?.readShiftKey() == true) {
            meta = meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        }
        if (mClient?.readFnKey() == true) {
            meta = meta or KeyEvent.META_FUNCTION_ON
        }
        if (meta == event.metaState) return event
        return KeyEvent(
            event.downTime, event.eventTime, event.action, event.keyCode,
            event.repeatCount, meta, event.deviceId, event.scanCode, event.flags, event.source
        )
    }

    /**
     * Set terminal cursor blinker rate. It must be between [TERMINAL_CURSOR_BLINK_RATE_MIN] and
     * [TERMINAL_CURSOR_BLINK_RATE_MAX], otherwise it will be disabled.
     *
     * @return Returns `true` if setting blinker rate was successfully set, otherwise `false`.
     */
    @Synchronized
    fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        // The j-code renderer manages its own fixed-rate blink; this only validates and records the
        // app's preference so the same contract (and return value) is preserved.
        val result: Boolean
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient?.logError(LOG_TAG, "The cursor blink rate must be in between $TERMINAL_CURSOR_BLINK_RATE_MIN-$TERMINAL_CURSOR_BLINK_RATE_MAX: $blinkRate")
            mTerminalCursorBlinkerRate = 0
            result = false
        } else {
            mClient?.logVerbose(LOG_TAG, "Setting cursor blinker rate to $blinkRate")
            mTerminalCursorBlinkerRate = blinkRate
            result = true
        }
        return result
    }

    /**
     * The j-code view blinks its own cursor while focused; this is accepted so the app's lifecycle
     * calls to enable/disable the blinker keep working unchanged.
     */
    @Synchronized
    fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        // No-op: the native renderer owns cursor blinking.
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun showContextMenu(): Boolean {
        if (mOnContextMenuShowListener != null) {
            return mOnContextMenuShowListener!!.onShowContextMenu(this)
        }
        return super.showContextMenu()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun showContextMenu(x: Float, y: Float): Boolean {
        if (mOnContextMenuShowListener != null) {
            return mOnContextMenuShowListener!!.onShowContextMenu(this)
        }
        return super.showContextMenu(x, y)
    }
}
