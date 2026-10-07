package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActivityStackParserTest {
    private val expected = ScreenObservation("com.test.app", "com.test.app.Top", "com.test.app", "abcd", false, 1)
    private val owned = DisplaySession(42, "00000000-0000-0000-0000-000000000042")
    private val virtual = expected.copy(displayId = owned.displayId, sessionId = owned.sessionId)

    @Test fun readsOnlyCurrentTaskAndDiscardsIntentsPathsAndOtherTasks() {
        val result = ActivityStackParser.parse(dump(), expected)!!
        val activities = result.data["activities"]!!.jsonArray
        assertEquals(listOf("com.test.app.Top", "com.test.app.Base"), activities.map { it.jsonObject["activity"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), activities[0].jsonObject["top_resumed"])
        assertFalse(result.truncated)
        assertFalse(result.data.toString().contains("private"))
        assertFalse(result.data.toString().contains("com.other"))
    }

    @Test fun mismatchedMissingOrAmbiguousTopRecordsAreUnavailable() {
        assertNull(ActivityStackParser.parse(dump(), expected.copy(foregroundActivity = "com.test.app.Other")))
        assertNull(ActivityStackParser.parse(dump().replace("Display #0", "Display #1"), expected))
        assertNull(ActivityStackParser.parse(dump().replace("topResumedActivity=", "notResumed="), expected))
        assertNull(ActivityStackParser.parse(dump() + "\n  mResumedActivity: ActivityRecord{c3 u0 com.test.app/.Base t7}", expected))
        assertNull(ActivityStackParser.parse(dump().replace("* Hist  #1:", "* Hist  #0:"), expected))
    }

    @Test fun handlesRepeatedResumedRecordAndTaskMayContainCrossAppActivities() {
        val raw = dump().replace("com.test.app/.Base", "com.link.app/.Base") +
            "\n  mResumedActivity: ActivityRecord{a1 u0 com.test.app/.Top t7}"
        val result = ActivityStackParser.parse(raw, expected)!!
        assertEquals("com.link.app", result.data["activities"]!!.jsonArray[1].jsonObject["package_name"]!!.jsonPrimitive.content)
    }

    @Test fun explicitCapDoesNotPretendToBeComplete() {
        val histories = (0..32).joinToString("\n") { index ->
            "  * Hist  #$index: ActivityRecord{${(index + 1).toString(16)} u0 com.test.app/.${if (index == 32) "Top" else "Base$index"} t7}"
        }
        val raw = "Display #0 (activities from top to bottom):\n topResumedActivity=ActivityRecord{21 u0 com.test.app/.Top t7}\n$histories"
        val result = ActivityStackParser.parse(raw, expected)!!
        assertEquals(32, result.data["activities"]!!.jsonArray.size)
        assertTrue(result.truncated)
    }

    @Test fun mainWireRemainsUnchangedAndSamePackageDisplaysDoNotLeak() {
        val main = dump().replace(".Base", ".MainBase")
        val own = ownedDump().replace(".Base", ".OwnedBase")
        val foreign = dump().replace("Display #0", "Display #420").replace(".Base", ".ForeignBase")
        for (raw in listOf("$main\n$own\n$foreign", "$foreign\n$main\n$own")) {
            val mainResult = ActivityStackParser.parse(raw, expected)!!
            assertEquals("""{"task_id":"7","order":"top_to_bottom","activities":[{"package_name":"com.test.app","activity":"com.test.app.Top","top_resumed":true},{"package_name":"com.test.app","activity":"com.test.app.MainBase","top_resumed":false}]}""",
                mainResult.data.toString())
            val ownResult = ActivityStackParser.parse(raw, virtual, owned)!!
            assertEquals(JsonPrimitive(42), ownResult.data["display_id"])
            assertEquals(listOf("com.test.app.Top", "com.test.app.OwnedBase"),
                ownResult.data["activities"]!!.jsonArray.map { it.jsonObject["activity"]!!.jsonPrimitive.content })
            assertFalse(ownResult.data.toString().contains("MainBase"))
            assertFalse(ownResult.data.toString().contains("ForeignBase"))
            assertFalse(ownResult.data.toString().contains("private"))
            assertFalse(ownResult.data.toString().contains("com.other"))
            assertFalse(ownResult.data.toString().contains(owned.sessionId))
        }
    }

    @Test fun ownedIdentityAndAvailableTargetAreRequired() {
        val raw = dump() + "\n" + ownedDump()
        for (target in listOf(
            virtual.copy(displayId = 43),
            virtual.copy(sessionId = "00000000-0000-0000-0000-000000000043"),
            virtual.copy(sessionId = null),
            virtual.copy(foregroundPackage = null),
            virtual.copy(foregroundActivity = null),
            virtual.copy(focusedPackage = null),
            virtual.copy(focusedPackage = "com.other.app"),
            virtual.copy(focusedWindowId = null),
            virtual.copy(keyboardVisible = null),
            expected,
        )) assertNull(ActivityStackParser.parse(raw, target, owned))
        assertNull(ActivityStackParser.parse(raw, virtual))
        assertNull(ActivityStackParser.parse(raw, expected.copy(sessionId = owned.sessionId)))
    }

    @Test fun missingMalformedOrDuplicateOwnedSectionNeverFallsBackToMain() {
        assertNull(ActivityStackParser.parse(dump(), virtual, owned))
        assertNull(ActivityStackParser.parse(dump() + "\n" + ownedDump().replace("Display #42", "Display #420"), virtual, owned))
        assertNull(ActivityStackParser.parse(dump() + "\n" + ownedDump().replace("top to bottom", "bottom to top"), virtual, owned))
        assertNull(ActivityStackParser.parse(ownedDump() + "\n" + ownedDump(), virtual, owned))
    }

    @Test fun ownedTopMustMatchTheWholeCurrentTaskAndUserRecord() {
        val raw = ownedDump()
        assertNull(ActivityStackParser.parse(raw.replace("* Hist  #1: ActivityRecord{a1 u0 com.test.app/.Top t7}",
            "* Hist  #1: ActivityRecord{a1 u0 com.test.app/.Other t7}"), virtual, owned))
        assertNull(ActivityStackParser.parse(raw.replace("* Hist  #1: ActivityRecord{a1 u0", "* Hist  #1: ActivityRecord{a1 u10"), virtual, owned))
        assertNull(ActivityStackParser.parse(raw.replace("* Hist  #1: ActivityRecord{a1 u0 com.test.app/.Top t7}",
            "* Hist  #1: ActivityRecord{a1 u0 com.test.app/.Top t8}"), virtual, owned))
        assertNull(ActivityStackParser.parse(raw + "\n  mResumedActivity: ActivityRecord{a1 u10 com.test.app/.Top t7}", virtual, owned))
        assertNull(ActivityStackParser.parse(raw + "\n  mResumedActivity: ActivityRecord{c3 u0 com.test.app/.Base t7}", virtual, owned))
        assertNotNull(ActivityStackParser.parse(raw + "\n  mResumedActivity: ActivityRecord{a1 u0 com.test.app/.Top t7}", virtual, owned))
    }

    @Test fun globalSummaryAndWaitingQueuesAreOutsideOwnedSection() {
        val summary = """
              ResumedActivity: ActivityRecord{c3 u0 com.test.app/.Other t8}
              Activities waiting to stop:
              * Hist  #2: ActivityRecord{c3 u0 com.test.app/.Other t7}
            ActivityTaskSupervisor state:
              mResumedActivity: ActivityRecord{d4 u0 com.test.app/.Other t9}
        """.trimIndent()
        assertNotNull(ActivityStackParser.parse(ownedDump() + "\n" + summary, virtual, owned))
        assertNull(ActivityStackParser.parse(dump() + "\nDisplay #42 (activities from top to bottom):\n" + summary,
            virtual, owned))
        val queue = "  Activities waiting to finish:\n  * Hist  #2: ActivityRecord{c3 u0 com.test.app/.Other t7}"
        val result = ActivityStackParser.parse(ownedDump() + "\n" + queue, virtual, owned)!!
        assertEquals(2, result.data["activities"]!!.jsonArray.size)
    }

    @Test fun ownedStackPreservesTheEntryLimit() {
        val histories = (0..32).joinToString("\n") { index ->
            "  * Hist  #$index: ActivityRecord{${(index + 1).toString(16)} u0 com.test.app/.${if (index == 32) "Top" else "Base$index"} t7}"
        }
        val raw = "Display #42 (activities from top to bottom):\n  topResumedActivity=ActivityRecord{21 u0 com.test.app/.Top t7}\n$histories"
        val result = ActivityStackParser.parse(raw, virtual, owned)!!
        assertEquals(32, result.data["activities"]!!.jsonArray.size)
        assertTrue(result.truncated)
    }

    internal fun ownedDump() = dump().replace("Display #0", "Display #42")

    internal fun dump() = """
        Display #0 (activities from top to bottom):
          topResumedActivity=ActivityRecord{a1 u0 com.test.app/.Top t7}
          * Hist  #1: ActivityRecord{a1 u0 com.test.app/.Top t7}
            Intent { private-content private-path }
          * Hist  #0: ActivityRecord{b2 u0 com.test.app/.Base t7}
          * Hist  #0: ActivityRecord{d4 u0 com.other.app/.Private t8}
          * Hist  #0: ActivityRecord{e5 u10 com.other.app/.Private t7}
    """.trimIndent()
}
