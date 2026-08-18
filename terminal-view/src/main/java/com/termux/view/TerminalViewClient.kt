package com.termux.view

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession

/**
 * The interface for communication between [TerminalView] and its client. It allows for getting
 * various  configuration options from the client and for sending back data to the client like logs,
 * key events, both hardware and IME (which makes it different from that available with
 * [android.view.View.OnKeyListener]), etc. It must be set for the
 * [TerminalView] through [TerminalView.setTerminalViewClient].
 */
interface TerminalViewClient {

    /**
     * Callback function on scale events according to [android.view.ScaleGestureDetector.getScaleFactor].
     */
    fun onScale(scale: Float): Float

    /**
     * On a single tap on the terminal if terminal mouse reporting not enabled.
     */
    fun onSingleTapUp(e: MotionEvent?)

    fun shouldBackButtonBeMappedToEscape(): Boolean

    fun shouldEnforceCharBasedInput(): Boolean

    fun shouldUseCtrlSpaceWorkaround(): Boolean

    fun isTerminalViewSelected(): Boolean

    fun copyModeChanged(copyMode: Boolean)

    fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean

    fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean

    fun onLongPress(event: MotionEvent?): Boolean

    /** Called on a rightward swipe across the terminal; the client opens the session drawer. */
    fun onDrawerSwipe()

    /** Called on a leftward swipe across the terminal; the client opens the script bar. */
    fun onScriptBarSwipe()

    /** Called on a long press at the given view coordinates; the client shows the j-code-style
     * selection menu (Copy/Paste/Select all/Clear/Select text) with icons at the touch point.
     */
    fun onContextMenu(x: Float, y: Float)

    /** Called on a double tap across the terminal; the client shows the quick-commands floating panel. */
    fun onQuickCommandsRequest()

    fun readControlKey(): Boolean

    fun readAltKey(): Boolean

    fun readShiftKey(): Boolean

    fun readFnKey(): Boolean

    fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean

    fun onEmulatorSet()

    fun logError(tag: String?, message: String?)

    fun logWarn(tag: String?, message: String?)

    fun logInfo(tag: String?, message: String?)

    fun logDebug(tag: String?, message: String?)

    fun logVerbose(tag: String?, message: String?)

    fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?)

    fun logStackTrace(tag: String?, e: Exception?)
}
