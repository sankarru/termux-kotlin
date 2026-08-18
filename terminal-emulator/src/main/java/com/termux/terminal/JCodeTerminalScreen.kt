package com.termux.terminal

import com.termux.JTermSession
import com.termux.VtParser

/**
 * Facade over the j-code [JTermSession] exposing the parts of the old [TerminalBuffer] read API
 * that the app and termux-shared depend on: selection text extraction, word lookup and transcript
 * dumps. All reads go through [VtParser.readRow], whose logical row coordinates match the old
 * external coordinate system (negative rows address scrollback, row -1 being the most recently
 * scrolled-off line).
 */
class JCodeTerminalScreen internal constructor(
    private val session: JTermSession
) {

    private val parser: VtParser
        get() = session.parser

    private val mColumns: Int
        get() = parser.cols

    private val mScreenRows: Int
        get() = parser.rows

    /** Number of scrollback lines currently available above the live screen. */
    fun getActiveTranscriptRows(): Int = if (parser.isOpen) parser.scrollbackSize else 0

    /** Total rows including scrollback. */
    fun getActiveRows(): Int = getActiveTranscriptRows() + mScreenRows

    /**
     * Extract the text of a rectangular region, mirroring the old [TerminalBuffer.getSelectedText]
     * semantics (including wide character handling and wrap/join behaviour).
     */
    @JvmOverloads
    fun getSelectedText(
        selX1: Int,
        selY1: Int,
        selX2: Int,
        selY2: Int,
        joinBackLines: Boolean = true,
        joinFullLines: Boolean = false
    ): String {
        if (!parser.isOpen) return ""
        val builder = StringBuilder()
        val columns = mColumns
        var y1 = selY1
        var y2 = selY2
        if (y1 < -getActiveTranscriptRows()) y1 = -getActiveTranscriptRows()
        if (y2 >= mScreenRows) y2 = mScreenRows - 1

        val buffer = IntArray(columns * VtParser.CELL_STRIDE)
        for (row in y1..y2) {
            val x1 = if (row == y1) selX1 else 0
            val x2 = if (row == y2) (selX2 + 1).coerceAtMost(columns) else columns
            val rowLineWrap = rowIsWrapped(row)
            val text = rowText(row, buffer, rowLineWrap)
            var x1Index = text.findStartOfColumn(x1)
            var x2Index = if (x2 < columns) text.findStartOfColumn(x2) else text.text.length
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = text.findStartOfColumn(x2 + 1)
            }
            var lastPrintingCharIndex = -1
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1
            } else {
                for (i in x1Index until x2Index) {
                    if (text.text[i] != ' ') lastPrintingCharIndex = i
                }
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0) {
                builder.append(text.text, x1Index, x1Index + len)
            }

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth) && row < y2 && row < mScreenRows - 1) {
                builder.append('\n')
            }
        }
        return builder.toString()
    }

    fun getWordAtLocation(x: Int, y: Int): String {
        if (!parser.isOpen) return ""
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        var y1 = y
        var y2 = y
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, joinBackLines = true, joinFullLines = true).contains("\n")) {
            y1--
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, joinBackLines = true, joinFullLines = true).contains("\n")) {
            y2++
        }

        // Get the text for the whole wrapped line
        val text = getSelectedText(0, y1, mColumns, y2, joinBackLines = true, joinFullLines = true)
        // The index of x in text
        val textOffset = (y - y1) * mColumns + x

        if (textOffset >= text.length) return ""

        val x1 = text.lastIndexOf(' ', textOffset)
        var x2 = text.indexOf(' ', textOffset)
        if (x2 == -1) x2 = text.length
        if (x1 == x2) return ""
        return text.substring(x1 + 1, x2)
    }

    /** The full transcript: every scrollback line plus the screen, trimmed of leading/trailing blank lines. */
    val transcriptText: String
        get() = getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim()

    /** Like [transcriptText], but wrapped lines are joined only when they fill the full line width. */
    val transcriptTextWithoutJoinedLines: String
        get() = getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, joinBackLines = false).trim()

    /** Like [transcriptText], but also joining full-width lines. */
    val transcriptTextWithFullLinesJoined: String
        get() = getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, joinBackLines = true, joinFullLines = true).trim()

    /** Whether the line on [logicalRow] wraps onto the following row (old getLineWrap(row)). */
    private fun rowIsWrapped(logicalRow: Int): Boolean = parser.isRowWrapped(logicalRow + 1)

    /**
     * Extract a row as text. Wide characters are a single code point regardless of their column
     * span (matching how the old buffer stored rows); [RowText] retains the column of each character
     * so column coordinates map back onto the text. Trailing spaces are trimmed unless the row is
     * wrapped (a wrapped row keeps its trailing space, per old semantics).
     */
    private fun rowText(logicalRow: Int, buffer: IntArray, wrapped: Boolean): RowText {
        val columns = mColumns
        parser.readRow(logicalRow, buffer)
        val sb = StringBuilder(columns)
        val starts = IntArray(columns)
        var count = 0
        for (c in 0 until columns) {
            val cp = buffer[c * VtParser.CELL_STRIDE]
            if (cp != 0) {
                starts[count] = c
                count++
                sb.appendCodePoint(cp)
            }
            // Continuation cell of a wide char (cp == 0) or a blank (cp == ' '): advance the column
            // without emitting a character of its own.
        }
        var end = count
        if (!wrapped) {
            while (end > 0 && sb[end - 1] == ' ') end--
        }
        return RowText(sb.substring(0, end), starts.copyOf(end))
    }

    /** A row's text with the starting column of each of its characters. */
    private class RowText(val text: String, private val startCols: IntArray) {

        /**
         * The index in [text] of the character occupying column [col]. A wide character at column c
         * covers columns c and c+1; both map to the index of that single character.
         */
        fun findStartOfColumn(col: Int): Int {
            for (i in startCols.indices) {
                if (startCols[i] >= col) return i
            }
            return startCols.size
        }
    }
}
