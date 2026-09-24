package com.example.agent.rootpilot.files

/**
 * Bounded preview diff, not a minimal diff: preserve a common prefix/suffix and
 * render the entire remaining old/new middle as removals followed by additions.
 * Time and auxiliary memory are O(before.length + after.length), never O(lines²).
 */
object TextChangeDiff {
    // UTF-16 units, not bytes; every valid 64 KiB UTF-8 file fits this bound.
    const val MAX_INPUT_CHARS = 65_536
    const val MAX_OUTPUT_LINES = 300

    enum class Kind { UNCHANGED, REMOVED, ADDED }

    enum class LineEnding { NONE, LF, CRLF, CR }

    /** Text excludes the terminator; consumers must display [ending] to expose EOL changes. */
    data class Line(
        val kind: Kind,
        val text: String,
        val oldLineNumber: Int?,
        val newLineNumber: Int?,
        val ending: LineEnding,
    ) {
        override fun toString(): String =
            "Line(kind=$kind, text=<redacted>, oldLineNumber=$oldLineNumber, " +
                "newLineNumber=$newLineNumber, ending=$ending)"
    }

    data class Result(
        val lines: List<Line>,
        val truncated: Boolean,
        val addedLineCount: Int,
        val removedLineCount: Int,
    ) {
        override fun toString(): String =
            "Result(lineCount=${lines.size}, truncated=$truncated, " +
                "addedLineCount=$addedLineCount, removedLineCount=$removedLineCount)"
    }

    /**
     * Line numbers are one-based; absent sides have null numbers. Null [before]
     * means creation and has zero lines, as does an empty string. Empty [after]
     * removes all text, without implying deletion of the file itself.
     *
     * Empty text has no lines; a trailing terminator belongs to the preceding line
     * and does not introduce a phantom empty line. Terminators participate in equality.
     * Changes take priority over context within [maxOutputLines]. At most three
     * preceding and three following unchanged lines are included, nearest the change.
     * With no changes, at most the last three lines are included. Removals precede
     * additions; changes themselves may be cut off when they exceed the budget.
     * [Result.truncated] indicates any omitted row, including omitted context.
     * Counts cover the full non-minimal diff even if hidden.
     * Throws IllegalArgumentException for oversized inputs or a limit outside 0..300.
     */
    fun compute(before: String?, after: String, maxOutputLines: Int = MAX_OUTPUT_LINES): Result {
        require((before?.length ?: 0) <= MAX_INPUT_CHARS && after.length <= MAX_INPUT_CHARS) {
            "Text diff input exceeds character limit"
        }
        require(maxOutputLines in 0..MAX_OUTPUT_LINES) { "Invalid text diff output limit" }
        val old = splitLines(before.orEmpty())
        val new = splitLines(after)
        var prefix = 0
        val commonLimit = minOf(old.size, new.size)
        while (prefix < commonLimit && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < commonLimit - prefix &&
            old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
        ) suffix++

        val removed = old.size - prefix - suffix
        val added = new.size - prefix - suffix
        val total = prefix + removed + added + suffix
        val contextBudget = (maxOutputLines - removed - added).coerceAtLeast(0)
        val prefixContext = minOf(prefix, 3, contextBudget)
        val suffixContext = minOf(suffix, 3, contextBudget - prefixContext)
        val rows = ArrayList<Line>(minOf(total, maxOutputLines))
        fun emit(kind: Kind, source: SourceLine, oldNumber: Int?, newNumber: Int?) {
            if (rows.size < maxOutputLines) {
                rows.add(Line(kind, source.text, oldNumber, newNumber, source.ending))
            }
        }
        for (index in prefix - prefixContext until prefix) emit(Kind.UNCHANGED, old[index], index + 1, index + 1)
        for (index in prefix until old.size - suffix) emit(Kind.REMOVED, old[index], index + 1, null)
        for (index in prefix until new.size - suffix) emit(Kind.ADDED, new[index], null, index + 1)
        for (offset in 0 until suffixContext) {
            val oldIndex = old.size - suffix + offset
            val newIndex = new.size - suffix + offset
            emit(Kind.UNCHANGED, old[oldIndex], oldIndex + 1, newIndex + 1)
        }
        return Result(rows.toList(), total > rows.size, added, removed)
    }

    private data class SourceLine(val text: String, val ending: LineEnding) {
        override fun toString(): String = "SourceLine(text=<redacted>, ending=$ending)"
    }

    private fun splitLines(text: String): List<SourceLine> {
        val lines = ArrayList<SourceLine>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '\r' && char != '\n') {
                index++
                continue
            }
            val end = index
            val ending = when {
                char == '\n' -> LineEnding.LF
                index + 1 < text.length && text[index + 1] == '\n' -> LineEnding.CRLF
                else -> LineEnding.CR
            }
            index += if (ending == LineEnding.CRLF) 2 else 1
            lines.add(SourceLine(text.substring(start, end), ending))
            start = index
        }
        if (start < text.length) lines.add(SourceLine(text.substring(start), LineEnding.NONE))
        return lines
    }
}
