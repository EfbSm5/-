package com.example.agent.rootpilot.root

import org.junit.Assert.*
import org.junit.Test

class ScreenObservationParserTest {
    private val resumed = "mResumedActivity: ActivityRecord{a12b u0 com.example.app/.MainActivity t42}"
    private val windows = """
        Window #0 Window{c12d u0 Private document title}:
          mDisplayId=0 rootTaskId=1 mSession=Session{abc} mClient=android.os.BinderProxy@abc
          mOwnerUid=10123 showForAllUsers=false package=com.example.editor appop=NONE
        mCurrentFocus=Window{c12d u0 Private document title}
    """.trimIndent()

    @Test fun knownFieldsAndRelativeActivityAreParsed() {
        val result = ScreenObservationParser.parse(resumed, windows, ime15("mInputShown=true"), 123)
        assertEquals("com.example.app", result.foregroundPackage)
        assertEquals("com.example.app.MainActivity", result.foregroundActivity)
        assertEquals("com.example.editor", result.focusedPackage)
        assertEquals("c12d", result.focusedWindowId)
        assertEquals(true, result.keyboardVisible)
        assertEquals(123L, result.observedAtMillis)
        assertFalse(result.toString().contains("Private document title"))
    }

    @Test fun fullyQualifiedActivityAndAosp16ImeSectionAreParsed() {
        val result = ScreenObservationParser.parse(
            resumed.replace(".MainActivity", "com.example.app.MainActivity"), null,
            "Input Method Manager Service state:\n  UserId=0\n    mVisibilityStateComputer:\n      mInputShown=false", 1,
        )
        assertEquals("com.example.app.MainActivity", result.foregroundActivity)
        assertEquals(false, result.keyboardVisible)
    }

    @Test fun missingAndUnknownFormatsAreUnknown() {
        for (raw in listOf(null, "", "null", "unknown vendor dump", "mInputShown=1")) {
            val result = ScreenObservationParser.parse(raw, raw, raw, 7)
            assertNull(result.foregroundPackage)
            assertNull(result.foregroundActivity)
            assertNull(result.focusedPackage)
            assertNull(result.focusedWindowId)
            assertNull(result.keyboardVisible)
            assertEquals(7L, result.observedAtMillis)
        }
    }

    @Test fun conflictingActivitiesAndMalformedAliasesAreUnknown() {
        for (second in listOf(resumed.replace("app/", "other/"), "mResumedActivity: null", resumed.replace("a12b", "ffff"))) {
            val result = ScreenObservationParser.parse("$resumed\n$second", windows, null, 0)
            assertNull(result.foregroundActivity)
            assertNull(result.foregroundPackage)
            assertEquals("c12d", result.focusedWindowId)
        }
    }

    @Test fun matchingAospTaskAndDisplaySummaryAliasesAreMerged() {
        val record = resumed.substringAfter(": ")
        val result = ScreenObservationParser.parse(
            "Display #0 (activities from top to bottom):\n  topResumedActivity=$record\n  $resumed\n    Resumed: $record",
            windows, null, 0,
        )
        assertEquals("com.example.app.MainActivity", result.foregroundActivity)
    }

    @Test fun nonDefaultAndMultipleDisplaysAreRejected() {
        for (prefix in listOf("Display #1 (activities from top to bottom):", "Display #0 (activities from top to bottom):\nDisplay #1 (activities from top to bottom):")) {
            val result = ScreenObservationParser.parse("$prefix\n$resumed", windows, ime15("mInputShown=true"), 0)
            assertNull(result.foregroundPackage)
            assertNull(result.focusedPackage)
            assertNull(result.keyboardVisible)
        }
        assertNull(ScreenObservationParser.parse(resumed, windows.replace("mDisplayId=0", "mDisplayId=2"), null, 0).focusedPackage)
    }

    private val defaultActivities get() = "Display #0 (activities from top to bottom):\n$resumed"
    private val idleActivities get() = "$defaultActivities\nDisplay #264 (activities from top to bottom):\nDisplay #265 (activities from top to bottom):"
    private val defaultDisplay = """
        Display: mDisplayId=0 (organized)
          mCurrentFocus=Window{c12d u0 Private document title}
          mFocusedApp=ActivityRecord{a12b u0 com.example.app/.MainActivity t42}
    """.trimIndent()

    private fun idleDisplay(id: Int) = """
        Display: mDisplayId=$id (organized)
          mSleeping=true mAllSleepTokens=[]
          mCurrentFocus=null
          mFocusedApp=null
          mImeWindow=null
    """.trimIndent()

    @Test fun explicitlyIdleVirtualDisplaysKeepDefaultDisplayIdentity() {
        for (separator in listOf("\n", "  ")) {
            val result = ScreenObservationParser.parse(
                idleActivities, windows, ime15("mInputShown=true"), 123,
                idleDisplay(265) + separator + idleDisplay(264) + separator + defaultDisplay,
            )
            assertEquals("com.example.app", result.foregroundPackage)
            assertEquals("com.example.app.MainActivity", result.foregroundActivity)
            assertEquals("com.example.editor", result.focusedPackage)
            assertEquals("c12d", result.focusedWindowId)
            assertEquals(true, result.keyboardVisible)
            assertEquals(123L, result.observedAtMillis)
        }
    }

    @Test fun activityGlobalSummaryAfterEmptyDisplayIsNotAnActivityInThatDisplay() {
        val record = resumed.substringAfter(": ")
        for (summary in listOf(
            "  ResumedActivity: $record\n\nActivityTaskSupervisor state:\n  topDisplayFocusedRootTask=Task{abc}\n  Display: mDisplayId=0",
            "ActivityTaskSupervisor state:\n  topDisplayFocusedRootTask=Task{abc}\n  Display: mDisplayId=0",
        )) {
            val result = ScreenObservationParser.parse(
                "$idleActivities\n\n$summary", windows,
                "Input Method Manager Service state:\n  UserId=0\n    mVisibilityStateComputer:\n      mInputShown=true", 0,
                idleDisplay(265) + "  " + idleDisplay(264).replace(" (organized)", "") + "  " + defaultDisplay,
            )
            assertEquals("com.example.app.MainActivity", result.foregroundActivity)
            assertEquals("com.example.editor", result.focusedPackage)
            assertEquals("c12d", result.focusedWindowId)
            assertEquals(true, result.keyboardVisible)
        }
    }

    @Test fun globalStoppingAndFinishingQueuesDoNotBelongToIdleDisplay() {
        val displays = idleDisplay(265) + "\n" + idleDisplay(264) + "\n" + defaultDisplay
        for (queue in listOf("stop", "finish")) {
            val summary = "  Activities waiting to $queue:\n    Task{abcd #41}\n" +
                "      * Stop #0: ActivityRecord{bbbb u0 com.previous.app/.Main t41}\n" +
                "  ResumedActivity: ${resumed.substringAfter(": ")}"
            val result = ScreenObservationParser.parse("$idleActivities\n$summary", windows, ime15("mInputShown=true"), 0, displays)
            assertEquals("com.example.app", result.foregroundPackage)
            assertEquals("com.example.editor", result.focusedPackage)
            assertEquals(true, result.keyboardVisible)
            for (activities in listOf(
                "$idleActivities\n$resumed\n$summary",
                "$idleActivities\n${summary.prependIndent("  ")}",
                "$idleActivities\n${summary.replace("waiting to $queue", "waiting to unknown")}",
                "$idleActivities\n${summary.replace("ResumedActivity: ActivityRecord{a12b", "ResumedActivity: ActivityRecord{ffff")}",
                "${idleActivities.replace(resumed, "")}\n$summary",
            )) {
                assertNull(ScreenObservationParser.parse(activities, windows, null, 0, displays).foregroundPackage)
            }
        }
    }

    @Test fun unknownOrNestedSummaryHeaderDoesNotHideSecondaryActivity() {
        val displays = idleDisplay(265) + "  " + idleDisplay(264) + "  " + defaultDisplay
        val record = resumed.substringAfter(": ")
        for (tail in listOf(
            "  UnknownSummary:\n    $resumed",
            "    ResumedActivity: $record",
            "  ActivityTaskSupervisor state:\n    $resumed",
            "$resumed\n  ResumedActivity: $record",
            "  ResumedActivity: ${record.replace("a12b", "ffff")}",
            "  ResumedActivity: null",
            "  ResumedActivity: $record unknown",
            "  ResumedActivity:",
            "ActivityTaskSupervisor state:\n  Display: mDisplayId=3",
        )) {
            val result = ScreenObservationParser.parse("$idleActivities\n$tail", windows, null, 0, displays)
            assertNull(result.foregroundPackage)
            assertNull(result.foregroundActivity)
        }
    }

    @Test fun globalSummaryAloneCannotSupplyDefaultDisplayActivity() {
        val result = ScreenObservationParser.parse(
            idleActivities.replace(resumed, "") + "\n  ResumedActivity: ${resumed.substringAfter(": ")}", windows, null, 0,
            idleDisplay(265) + "  " + idleDisplay(264) + "  " + defaultDisplay,
        )
        assertNull(result.foregroundPackage)
        assertNull(result.foregroundActivity)
    }

    @Test fun deviceInlineSummaryMatchesDefaultDisplayFullIdentity() {
        val record = "ActivityRecord{213832538 u0 com.example.agent.test/com.example.agent.rootpilot.input.InputFixtureActivity t18349}"
        val activities = "Display #0 (activities from top to bottom):\n  topResumedActivity=$record\n  Resumed: $record\n" +
            "Display #264 (activities from top to bottom):\n\nDisplay #265 (activities from top to bottom):\n\n" +
            "  ResumedActivity: $record\nActivityTaskSupervisor state:\n  Display: mDisplayId=0"
        val result = ScreenObservationParser.parse(
            activities, windows, null, 0,
            idleDisplay(265) + "  " + idleDisplay(264) + "  " + defaultDisplay,
        )
        assertEquals("com.example.agent.test", result.foregroundPackage)
        assertEquals("com.example.agent.rootpilot.input.InputFixtureActivity", result.foregroundActivity)
        assertEquals("c12d", result.focusedWindowId)
        assertEquals("com.example.editor", result.focusedPackage)
    }

    @Test fun secondaryActivitiesIncludingNonResumedRecordsAreRejected() {
        for (body in listOf(resumed, "* Hist #0: ActivityRecord{b123 u0 com.other.app/.Main t43}", "* Task{abc}", "unknown vendor activity state")) {
            assertUnknownDisplays(
                "$idleActivities\n$body",
                idleDisplay(265) + "\n" + idleDisplay(264) + "\n" + defaultDisplay,
            )
        }
    }

    @Test fun secondaryFocusAndUnknownFocusStateAreRejectedEvenWithMidlineHeader() {
        val idle = idleDisplay(265)
        for (secondary in listOf(
            idle.replace("mCurrentFocus=null", "mCurrentFocus=Window{ffff u0 external}"),
            idle.replace("mFocusedApp=null", "mFocusedApp=ActivityRecord{ffff u0 com.other.app/.Main t43}"),
            idle.replace("mCurrentFocus=null", "mCurrentFocus=vendor"),
            idle.replace("mCurrentFocus=null", ""),
            idle.replace("mFocusedApp=null", ""),
            idle.replace("mCurrentFocus=null", "mCurrentFocus=null\nmCurrentFocus=null"),
            idle.replace("mFocusedApp=null", "mFocusedApp=null\nmFocusedApp=null"),
            idle.replace("mImeWindow=null", "mActivityRecord=ActivityRecord{ffff u0 com.other.app/.Main t43}"),
            idle.replace("mImeWindow=null", "mDisplayId=0"),
        )) {
            assertUnknownDisplays(idleActivities, secondary + "  " + idleDisplay(264) + "  " + defaultDisplay)
        }
    }

    @Test fun missingDuplicateAndMalformedDisplaySectionsAreRejected() {
        val displays = idleDisplay(265) + "  " + idleDisplay(264) + "  " + defaultDisplay
        for (raw in listOf(
            displays.replace(idleDisplay(264), ""),
            displays.replace("mDisplayId=264", "mDisplayId=unknown"),
            displays.replace("mDisplayId=264", "mDisplayId=265"),
            displays.replace("mDisplayId=264 (organized)", "mDisplayId=264 unknown"),
            displays.replace(defaultDisplay, ""),
            displays + "\n" + defaultDisplay,
            "mCurrentFocus=Window{ffff u0 unscoped}\n$displays",
        )) assertUnknownDisplays(idleActivities, raw)
        for (raw in listOf(
            idleActivities.replace("Display #264 (activities from top to bottom):", ""),
            idleActivities.replace("Display #264", "Display #unknown"),
            idleActivities.replace("Display #264", "Display #265"),
            idleActivities.replace(defaultActivities, ""),
            "$resumed\n$idleActivities",
            "$idleActivities\n$defaultActivities",
        )) assertUnknownDisplays(raw, displays)
    }

    @Test fun focusedWindowMustStillBelongToDefaultDisplay() {
        val result = ScreenObservationParser.parse(
            idleActivities, windows.replace("mDisplayId=0", "mDisplayId=264"), ime15("mInputShown=true"), 0,
            idleDisplay(265) + "\n" + idleDisplay(264) + "\n" + defaultDisplay,
        )
        assertEquals("com.example.app", result.foregroundPackage)
        assertEquals("c12d", result.focusedWindowId)
        assertNull(result.focusedPackage)
        assertEquals(true, result.keyboardVisible)
    }

    private val sleepingSystemWindow = """
        Window #1 Window{eeee u0 System window}:
          mDisplayId=264 rootTaskId=0
          mOwnerUid=1000 showForAllUsers=false package=com.example.system appop=NONE
    """.trimIndent()

    @Test fun sleepingIdleDisplayMayRetainNonFocusedSystemWindows() {
        val summary = "\n  ResumedActivity: ${resumed.substringAfter(": ")}\nActivityTaskSupervisor state:\n" +
            "  Display: mDisplayId=0 (organized)\n  mImeWindow=null  Display: mDisplayId=264\n" +
            "  mImeWindow=null  Display: mDisplayId=265 (organized)"
        val result = ScreenObservationParser.parse(
            idleActivities + summary, "$windows\n$sleepingSystemWindow", ime15("mInputShown=true"), 0,
            idleDisplay(265) + "\n" + idleDisplay(264).replace(
                "mImeWindow=null", "mObscuringWindow=Window{eeee u0 System window}\nmImeWindow=null",
            ) + "  " + defaultDisplay,
        )
        assertEquals("com.example.app.MainActivity", result.foregroundActivity)
        assertEquals("com.example.editor", result.focusedPackage)
        assertEquals("c12d", result.focusedWindowId)
        assertEquals(true, result.keyboardVisible)
    }

    @Test fun awakeMissingUnknownAndDuplicateSleepStatesAreRejected() {
        for (sleep in listOf("mSleeping=false mAllSleepTokens=[]", "", "mSleeping=unknown", "mSleeping=true\nmSleeping=true", "mSleeping=true\nmSleeping=false", "mSleeping=true mAllSleepTokens=[unknown]")) {
            assertUnknownDisplays(
                idleActivities,
                idleDisplay(265) + "\n" + idleDisplay(264).replace("mSleeping=true mAllSleepTokens=[]", sleep) + "  " + defaultDisplay,
            )
        }
    }

    @Test fun lastSleepingWindowEndsBeforeGlobalSnapshotCache() {
        val lastWindow = sleepingSystemWindow.replace("Window #1", "Window #26").prependIndent("  ")
        for (boundary in listOf("  mGlobalConfiguration={}", " SnapshotCache")) {
            val raw = "$windows\n$lastWindow\n\n$boundary\n" +
                "  mTopFocusedDisplayId=0\n SnapshotCache\n" +
                "     topApp=ActivityRecord{ffff u0 com.other.app/.Main t43}\n" +
                "  mResumeActivity:ActivityRecord{ffff u0 com.other.app/.Main t43}"
            val result = ScreenObservationParser.parse(
                idleActivities, raw, ime15("mInputShown=true"), 0,
                idleDisplay(265) + "\n" + idleDisplay(264) + "  " + defaultDisplay,
            )
            assertEquals("com.example.app.MainActivity", result.foregroundActivity)
            assertEquals("com.example.editor", result.focusedPackage)
            assertEquals("c12d", result.focusedWindowId)
            assertEquals(true, result.keyboardVisible)
        }
    }

    @Test fun activityRecordInsideIndentedSleepingWindowIsStillRejected() {
        val lastWindow = sleepingSystemWindow.replace("Window #1", "Window #26").prependIndent("  ")
        val raw = "$windows\n$lastWindow\n\n" +
            "    mActivityRecord=ActivityRecord{ffff u0 com.other.app/.Main t43}\n" +
            "  mGlobalConfiguration={}"
        val result = ScreenObservationParser.parse(
            idleActivities, raw, ime15("mInputShown=true"), 0,
            idleDisplay(265) + "\n" + idleDisplay(264) + "  " + defaultDisplay,
        )
        assertNull(result.foregroundPackage)
        assertNull(result.focusedPackage)
        assertNull(result.focusedWindowId)
        assertNull(result.keyboardVisible)
    }

    @Test fun focusedPackageDoesNotReadGlobalOwnerAfterWindowBlock() {
        val raw = windows.substringBefore("mCurrentFocus").trimEnd().prependIndent("  ") +
            "\n\n  mGlobalConfiguration={}\n" +
            "    mOwnerUid=1000 showForAllUsers=false package=com.other.app appop=NONE"
        val result = ScreenObservationParser.parse(defaultActivities, raw, null, 0, defaultDisplay)
        assertEquals("com.example.editor", result.focusedPackage)
        assertEquals("c12d", result.focusedWindowId)
    }

    @Test fun globalSummaryRejectsUnknownDisplayIdsIncludingMidlineHeaders() {
        for (id in listOf("999", "unknown")) {
            assertUnknownDisplays(
                idleActivities + "\nActivityTaskSupervisor state:\n  Display: mDisplayId=0 (organized)\n" +
                    "  mImeWindow=null  Display: mDisplayId=$id",
                idleDisplay(265) + "\n" + idleDisplay(264) + "  " + defaultDisplay,
            )
        }
    }

    @Test fun systemWindowRequiresAKnownUnambiguousDisplayWithoutActivityRecord() {
        for (secondary in listOf(
            sleepingSystemWindow.replace("mDisplayId=264", "mDisplayId=999"),
            sleepingSystemWindow.replace("mDisplayId=264", "mDisplayId=unknown"),
            sleepingSystemWindow.replace("mDisplayId=264 rootTaskId=0", ""),
            sleepingSystemWindow.replace("mDisplayId=264", "mDisplayId=264\n  mDisplayId=265"),
            "$sleepingSystemWindow\n  mActivityRecord=ActivityRecord{ffff u0 com.other.app/.Main t43}",
        )) {
            val result = ScreenObservationParser.parse(
                idleActivities, "$windows\n$secondary", ime15("mInputShown=true"), 0,
                idleDisplay(265) + "\n" + idleDisplay(264) + "  " + defaultDisplay,
            )
            assertNull(result.foregroundPackage)
            assertNull(result.focusedPackage)
            assertNull(result.focusedWindowId)
            assertNull(result.keyboardVisible)
        }
    }

    private fun assertUnknownDisplays(activities: String, displays: String) {
        val result = ScreenObservationParser.parse(activities, windows, ime15("mInputShown=true"), 0, displays)
        assertNull(result.foregroundPackage)
        assertNull(result.foregroundActivity)
        assertNull(result.focusedPackage)
        assertNull(result.focusedWindowId)
        assertNull(result.keyboardVisible)
    }

    @Test fun unknownOrDuplicateWindowDisplayDoesNotBindPackage() {
        for (raw in listOf(windows.replace("mDisplayId=0", "unknownDisplay=0"), windows.replace("mDisplayId=0", "mDisplayId=vendor"), windows.replace("mDisplayId=0", "mDisplayId=0\n  mDisplayId=0"))) {
            assertNull(ScreenObservationParser.parse(resumed, raw, null, 0).focusedPackage)
        }
    }

    @Test fun focusIsReadFromSeparateDisplayDump() {
        val result = ScreenObservationParser.parse(resumed, windows.substringBefore("mCurrentFocus"), null, 0,
            "Display: mDisplayId=0\n  mCurrentFocus=Window{c12d u0 private title}")
        assertEquals("c12d", result.focusedWindowId)
        assertEquals("com.example.editor", result.focusedPackage)
    }

    @Test fun imeClientSectionsAndHistoryDoNotOverrideManagerState() {
        val raw = ime15("mInputShown=true") + "\n  mStartInputHistory:\n    mInputShown=false\nInput method service state for private:\n  mInputShown=false"
        assertEquals(true, ScreenObservationParser.parse(null, null, raw, 0).keyboardVisible)
        assertNull(ScreenObservationParser.parse(null, null, "Input method service state for private:\n  mInputShown=true", 0).keyboardVisible)
    }

    @Test fun multipleImeUsersAndVisibilitySectionsAreUnknown() {
        val raw = "Input Method Manager Service state:\n  UserId=0\n    mVisibilityStateComputer:\n      mInputShown=true"
        for (suffix in listOf("\n  UserId=10", "\n    mVisibilityStateComputer:\n      mInputShown=true")) {
            assertNull(ScreenObservationParser.parse(null, null, raw + suffix, 0).keyboardVisible)
        }
    }

    @Test fun duplicateFocusLinesInvalidateBothFocusFields() {
        for (second in listOf("mCurrentFocus=null", "mCurrentFocus=Window{c12d u0 title}")) {
            val result = ScreenObservationParser.parse(null, "$windows\n$second", null, 0)
            assertNull(result.focusedWindowId)
            assertNull(result.focusedPackage)
        }
    }

    @Test fun duplicateWindowRecordsAndOwnersDoNotSelectAPackage() {
        val duplicateRecord = windows.substringBefore("mCurrentFocus") + windows
        val duplicateOwner = windows.replace("mCurrentFocus", "  mOwnerUid=10124 showForAllUsers=false package=com.other.app appop=NONE\nmCurrentFocus")
        for (raw in listOf(duplicateRecord, duplicateOwner)) {
            val result = ScreenObservationParser.parse(null, raw, null, 0)
            assertEquals("c12d", result.focusedWindowId)
            assertNull(result.focusedPackage)
        }
    }

    @Test fun packageIsNeverInferredFromWindowTitleOrUnrelatedOwner() {
        val raw = """
            Window #0 Window{aaaa u0 com.secret.app/.PrivateActivity}:
              mOwnerUid=10123 showForAllUsers=false package=com.unrelated.app appop=NONE
            Window #1 Window{bbbb u0 com.secret.app/.PrivateActivity}:
              arbitrary sensitive text
            mCurrentFocus=Window{bbbb u0 com.secret.app/.PrivateActivity}
        """.trimIndent()
        val result = ScreenObservationParser.parse(null, raw, null, 0)
        assertEquals("bbbb", result.focusedWindowId)
        assertNull(result.focusedPackage)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("sensitive"))
    }

    @Test fun unknownOwnerSuffixAndMalformedFocusAreRejected() {
        assertNull(ScreenObservationParser.parse(null, windows.replace("appop=NONE", "unknown=secret"), null, 0).focusedPackage)
        assertNull(ScreenObservationParser.parse(null, windows.replace("mCurrentFocus=Window{c12d", "mCurrentFocus=Window{private-token"), null, 0).focusedWindowId)
    }

    @Test fun duplicateOrConflictingImeStateIsUnknown() {
        for (raw in listOf("mInputShown=true\nmInputShown=false", "mInputShown=true\nmInputShown=true", "mInputShown=true extra=secret")) {
            assertNull(ScreenObservationParser.parse(null, null, ime15(raw), 0).keyboardVisible)
        }
    }

    @Test fun activitySuffixesAndArbitraryTitlesDoNotEscape() {
        for (raw in listOf("$resumed secret", resumed.replace(".MainActivity", "Private document title"))) {
            val result = ScreenObservationParser.parse(raw, "mCurrentFocus=Window{abcd u0 secret title}", null, 0)
            assertNull(result.foregroundActivity)
            assertNull(result.focusedPackage)
            assertEquals("abcd", result.focusedWindowId)
            assertFalse(result.toString().contains("secret"))
        }
    }

    private fun ime15(fields: String) = "Current Input Method Manager state:\n" +
        fields.lineSequence().joinToString("\n") { "  $it" }
}
