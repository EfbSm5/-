package com.example.agent.rootpilot.files

import com.example.agent.rootpilot.files.TextChangeDiff.Kind.*
import com.example.agent.rootpilot.files.TextChangeDiff.Line
import com.example.agent.rootpilot.files.TextChangeDiff.LineEnding.*
import org.junit.Assert.*
import org.junit.Test

class TextChangeDiffTest {
    @Test fun unchangedPreservesWhitespaceUnicodeAndMixedTerminators() {
        val text = "\uFEFF 中文🙂 \t\r\n末尾\n无换行 "
        val result = TextChangeDiff.compute(text, text)
        assertEquals(listOf(
            Line(UNCHANGED, "\uFEFF 中文🙂 \t", 1, 1, CRLF),
            Line(UNCHANGED, "末尾", 2, 2, LF),
            Line(UNCHANGED, "无换行 ", 3, 3, NONE),
        ), result.lines)
        assertCounts(result, 0, 0)
        assertFalse(result.truncated)
    }

    @Test fun emptyAndAbsentInputsHaveNoPhantomLines() {
        for (before in listOf(null, "")) {
            val result = TextChangeDiff.compute(before, "")
            assertTrue(result.lines.isEmpty())
            assertFalse(result.truncated)
            assertCounts(result, 0, 0)
        }
    }

    @Test fun creationAndRemovalUseOnlyTheirOwnLineNumbers() {
        val created = TextChangeDiff.compute(null, "甲\n乙")
        assertEquals(listOf(Line(ADDED, "甲", null, 1, LF), Line(ADDED, "乙", null, 2, NONE)), created.lines)
        assertCounts(created, 2, 0)
        assertEquals(created, TextChangeDiff.compute("", "甲\n乙"))
        val removed = TextChangeDiff.compute("甲\n乙", "")
        assertEquals(listOf(Line(REMOVED, "甲", 1, null, LF), Line(REMOVED, "乙", 2, null, NONE)), removed.lines)
        assertCounts(removed, 0, 2)
    }

    @Test fun commonSuffixKeepsIndependentOldAndNewNumbers() {
        val result = TextChangeDiff.compute("head\nold\ntail", "head\nnew\nextra\ntail")
        assertEquals(listOf(
            Line(UNCHANGED, "head", 1, 1, LF),
            Line(REMOVED, "old", 2, null, LF),
            Line(ADDED, "new", null, 2, LF),
            Line(ADDED, "extra", null, 3, LF),
            Line(UNCHANGED, "tail", 3, 4, NONE),
        ), result.lines)
        assertCounts(result, 2, 1)
    }

    @Test fun repeatedLinesDoNotOverlapPrefixAndSuffix() {
        val result = TextChangeDiff.compute("同\n同\n", "同\n同\n同\n")
        assertEquals(listOf(
            Line(UNCHANGED, "同", 1, 1, LF),
            Line(UNCHANGED, "同", 2, 2, LF),
            Line(ADDED, "同", null, 3, LF),
        ), result.lines)
        assertCounts(result, 1, 0)
        assertCounts(TextChangeDiff.compute("同\n同\n同\n", "同\n同\n"), 0, 1)
    }

    @Test fun matchingInteriorIsDeliberatelyNotAMinimalDiff() {
        val result = TextChangeDiff.compute("a\nsame\nb", "x\nsame\ny")
        assertEquals(listOf(REMOVED, REMOVED, REMOVED, ADDED, ADDED, ADDED), result.lines.map { it.kind })
        assertCounts(result, 3, 3)
    }

    @Test fun terminatorOnlyChangesAreVisibleInMetadata() {
        for (ending in listOf("\n" to LF, "\r\n" to CRLF, "\r" to CR)) {
            val added = TextChangeDiff.compute("文本", "文本${ending.first}")
            assertEquals(listOf(
                Line(REMOVED, "文本", 1, null, NONE),
                Line(ADDED, "文本", null, 1, ending.second),
            ), added.lines)
            assertCounts(added, 1, 1)
            val removed = TextChangeDiff.compute("文本${ending.first}", "文本")
            assertEquals(listOf(ending.second, NONE), removed.lines.map { it.ending })
            assertCounts(removed, 1, 1)
        }
        val converted = TextChangeDiff.compute("a\r\nb\r", "a\nb\n")
        assertEquals(listOf(CRLF, CR, LF, LF), converted.lines.map { it.ending })
        assertCounts(converted, 2, 2)
    }

    @Test fun blankLinesAndTrailingNewlinesAreRealLinesOnly() {
        val result = TextChangeDiff.compute(null, "\n\r\n\r")
        assertEquals(listOf(
            Line(ADDED, "", null, 1, LF),
            Line(ADDED, "", null, 2, CRLF),
            Line(ADDED, "", null, 3, CR),
        ), result.lines)
        assertCounts(result, 3, 0)
        val extraBlank = TextChangeDiff.compute("a\n", "a\n\n")
        assertEquals(Line(ADDED, "", null, 2, LF), extraBlank.lines.last())
        assertCounts(extraBlank, 1, 0)
    }

    @Test fun outputLimitDoesNotChangeCountsOrOrder() {
        val full = TextChangeDiff.compute("head\nold\ntail", "head\nnew\nextra\ntail")
        val expectedIndices = listOf(
            emptyList(), listOf(1), listOf(1, 2), listOf(1, 2, 3),
            listOf(0, 1, 2, 3), listOf(0, 1, 2, 3, 4),
        )
        for (limit in 0..full.lines.size) {
            val limited = TextChangeDiff.compute("head\nold\ntail", "head\nnew\nextra\ntail", limit)
            assertEquals(expectedIndices[limit].map { full.lines[it] }, limited.lines)
            assertEquals(limit < full.lines.size, limited.truncated)
            assertCounts(limited, 2, 1)
        }
        assertFalse(TextChangeDiff.compute(null, "", 0).truncated)
    }

    @Test fun defaultCapHasExactBoundary() {
        assertFalse(TextChangeDiff.compute(null, "\n".repeat(300)).truncated)
        val result = TextChangeDiff.compute(null, "\n".repeat(301))
        assertEquals(300, result.lines.size)
        assertTrue(result.truncated)
        assertCounts(result, 301, 0)
    }

    @Test fun lastLineChangeAfterLongPrefixIsVisible() {
        val result = TextChangeDiff.compute("\n".repeat(301) + "old", "\n".repeat(301) + "new")
        assertEquals(listOf(
            Line(UNCHANGED, "", 299, 299, LF),
            Line(UNCHANGED, "", 300, 300, LF),
            Line(UNCHANGED, "", 301, 301, LF),
            Line(REMOVED, "old", 302, null, NONE),
            Line(ADDED, "new", null, 302, NONE),
        ), result.lines)
        assertTrue(result.truncated)
        assertCounts(result, 1, 1)
    }

    @Test fun bothContextsAreCappedAtThreeNearestLines() {
        val prefix = (1..10).joinToString("") { "prefix$it\n" }
        val suffix = (1..10).joinToString("") { "suffix$it\n" }
        val result = TextChangeDiff.compute(prefix + "old\n" + suffix, prefix + "new\n" + suffix)
        assertEquals(listOf("prefix8", "prefix9", "prefix10", "old", "new", "suffix1", "suffix2", "suffix3"),
            result.lines.map { it.text })
        assertEquals(listOf(8, 9, 10, 11, null, 12, 13, 14), result.lines.map { it.oldLineNumber })
        assertEquals(listOf(8, 9, 10, null, 11, 12, 13, 14), result.lines.map { it.newLineNumber })
        assertTrue(result.truncated)
        assertCounts(result, 1, 1)
    }

    @Test fun smallBudgetGoesToChangesBeforeContext() {
        val result = TextChangeDiff.compute("head\nold\ntail", "head\nnew\ntail", 2)
        assertEquals(listOf(REMOVED, ADDED), result.lines.map { it.kind })
        assertTrue(result.truncated)
        assertCounts(result, 1, 1)
    }

    @Test fun omittedUnchangedLinesAlsoMarkTruncated() {
        val result = TextChangeDiff.compute("1\n2\n3\n4", "1\n2\n3\n4")
        assertEquals(listOf("2", "3", "4"), result.lines.map { it.text })
        assertTrue(result.truncated)
        assertCounts(result, 0, 0)
    }

    @Test fun maximumInputsWithTensOfThousandsOfLinesRemainBounded() {
        val before = "\n".repeat(65_536)
        val after = "\r".repeat(65_536)
        val changed = TextChangeDiff.compute(before, after)
        assertEquals(300, changed.lines.size)
        assertTrue(changed.truncated)
        assertCounts(changed, 65_536, 65_536)
        val unchanged = TextChangeDiff.compute(before, before)
        assertEquals(3, unchanged.lines.size)
        assertTrue(unchanged.truncated)
        assertCounts(unchanged, 0, 0)
        assertCounts(TextChangeDiff.compute(null, before), 65_536, 0)
        assertCounts(TextChangeDiff.compute(before, ""), 0, 65_536)
    }

    @Test fun longLineIsNotTrimmedOrShortened() {
        val text = "中".repeat(21_844) + "🙂  "
        assertEquals(text, TextChangeDiff.compute(null, text).lines.single().text)
        val maximum = "a".repeat(65_536)
        assertEquals(maximum, TextChangeDiff.compute(null, maximum).lines.single().text)
    }

    @Test fun limitsRejectOversizedArgumentsWithoutEchoingText() {
        val secret = "private-body-".repeat(6_000)
        for ((before, after) in listOf(secret to "", "" to secret)) {
            val error = assertThrows(IllegalArgumentException::class.java) { TextChangeDiff.compute(before, after) }
            assertFalse(error.toString().contains("private-body"))
        }
        for (limit in listOf(-1, 301, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TextChangeDiff.compute(null, "", limit) }
        }
    }

    @Test fun stringRepresentationsNeverIncludeBodies() {
        val secret = "private-body-中文"
        val result = TextChangeDiff.compute(secret, "$secret-new")
        assertFalse(result.toString().contains(secret))
        assertFalse(result.lines.toString().contains(secret))
        result.lines.forEach { assertFalse(it.toString().contains(secret)) }
    }

    private fun assertCounts(result: TextChangeDiff.Result, added: Int, removed: Int) {
        assertEquals(added, result.addedLineCount)
        assertEquals(removed, result.removedLineCount)
    }
}
