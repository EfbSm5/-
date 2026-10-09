package com.example.agent.rootpilot.parity

import org.junit.Assert.*
import org.junit.Test

class VirtualTextEditPlanTest {
    @Test fun insertsUnicodeAtKnownCaretWithoutRemovingExistingText() {
        val plan = requireNotNull(VirtualTextEditPlan.create("甲乙", 1, 1, "🙂"))
        assertEquals("甲🙂乙", plan.replacement)
        assertEquals(3, plan.caret)
        assertTrue(plan.matchesSource("甲乙", 1, 1))
        assertTrue(plan.matchesResult("甲🙂乙", 3, 3))
    }

    @Test fun replacesOnlyExplicitSelectionWithChineseEmojiAndNewline() {
        val input = "中文🙂\n第二行"
        val plan = requireNotNull(VirtualTextEditPlan.create("甲乙丙", 1, 2, input))
        assertEquals("甲${input}丙", plan.replacement)
        assertEquals(1 + input.length, plan.caret)
    }

    @Test fun reverseSelectionUsesSameRangeButKeepsExactSourceBinding() {
        val plan = requireNotNull(VirtualTextEditPlan.create("abc", 2, 1, "X"))
        assertEquals("aXc", plan.replacement)
        assertTrue(plan.matchesSource("abc", 2, 1))
        assertFalse(plan.matchesSource("abc", 1, 2))
    }

    @Test fun appendsAndPrependsAtExplicitPositions() {
        assertEquals("abX", requireNotNull(VirtualTextEditPlan.create("ab", 2, 2, "X")).replacement)
        assertEquals("Xab", requireNotNull(VirtualTextEditPlan.create("ab", 0, 0, "X")).replacement)
    }

    @Test fun emptyKnownTextStillRequiresKnownSelection() {
        assertEquals("中文", requireNotNull(VirtualTextEditPlan.create("", 0, 0, "中文")).replacement)
        assertNull(VirtualTextEditPlan.create("", -1, -1, "中文"))
        assertNull(VirtualTextEditPlan.create(null, 0, 0, "中文"))
    }

    @Test fun unknownOrOutOfBoundsSelectionIsNotGuessed() {
        for ((start, end) in listOf(-1 to -1, -1 to 0, 0 to -1, 4 to 4, 0 to 4)) {
            assertNull(VirtualTextEditPlan.create("abc", start, end, "X"))
        }
    }

    @Test fun surrogatePairCannotBeSplitAtEitherSelectionBoundary() {
        for ((start, end) in listOf(2 to 2, 1 to 2, 2 to 3, 2 to 1)) {
            assertNull(VirtualTextEditPlan.create("甲🙂乙", start, end, "X"))
        }
        assertEquals("甲X乙", requireNotNull(VirtualTextEditPlan.create("甲🙂乙", 1, 3, "X")).replacement)
    }

    @Test fun payloadAndSourceMustBothBeValidBoundedUnicode() {
        for (invalid in listOf("\u0000", "\u0001", "\uD83D", "\uDE42", "a".repeat(129))) {
            assertNull(VirtualTextEditPlan.create("ab", 1, 1, invalid))
            assertNull(VirtualTextEditPlan.create(invalid, 0, 0, "X"))
        }
        assertNull(VirtualTextEditPlan.create("ab", 1, 1, ""))
    }

    @Test fun totalResultRemainsBoundedEvenWhenEachInputIsValid() {
        assertNull(VirtualTextEditPlan.create("a".repeat(128), 128, 128, "X"))
        val plan = requireNotNull(VirtualTextEditPlan.create("a".repeat(128), 0, 128, "b".repeat(128)))
        assertEquals(128, plan.replacement.length)
    }

    @Test fun changedTextOrSelectionAfterApprovalInvalidatesPlan() {
        val plan = requireNotNull(VirtualTextEditPlan.create("abc", 1, 2, "X"))
        assertFalse(plan.matchesSource("adc", 1, 2))
        assertFalse(plan.matchesSource("abc", 1, 1))
        assertFalse(plan.matchesSource("abc", 0, 2))
        assertFalse(plan.matchesSource(null, 1, 2))
        assertFalse(plan.matchesSource("abc".repeat(100), 1, 2))
    }

    @Test fun acceptedActionNeedsExactContentAndCaretReadback() {
        val plan = requireNotNull(VirtualTextEditPlan.create("abc", 1, 2, "X"))
        assertTrue(plan.matchesResult("aXc", 2, 2))
        assertFalse(plan.matchesResult("aXc", 1, 1))
        assertFalse(plan.matchesResult("aYc", 2, 2))
        assertFalse(plan.matchesResult(null, 2, 2))
    }

    @Test fun diagnosticStringDoesNotExposeSourceOrInput() {
        val plan = requireNotNull(VirtualTextEditPlan.create("abc", 1, 1, "private"))
        assertEquals("VirtualTextEditPlan", plan.toString())
    }
}
