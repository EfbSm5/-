package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActivityStackParserTest {
    private val expected = ScreenObservation("com.test.app", "com.test.app.Top", "com.test.app", "abcd", false, 1)

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
