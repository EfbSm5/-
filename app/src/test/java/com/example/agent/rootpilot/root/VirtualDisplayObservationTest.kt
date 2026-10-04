package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.sameTarget
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayObservationTest {
    private val tasks = """
        Display #0 (activities from top to bottom):
          topResumedActivity=ActivityRecord{aaa u0 com.example.main/.Main t1}
        Display #9 (activities from top to bottom):
          topResumedActivity=ActivityRecord{bbb u0 com.example.target/.Target t2}
    """.trimIndent()
    private val displays = """
        Display: mDisplayId=0 (organized)
          mCurrentFocus=Window{111 u0 main window}
          mFocusedApp=ActivityRecord{aaa u0 com.example.main/.Main t1}
          mImeWindow=Window{333 u0 main keyboard}
        Display: mDisplayId=9 (organized)
          mCurrentFocus=Window{222 u0 virtual window}
          mFocusedApp=ActivityRecord{bbb u0 com.example.target/.Target t2}
          mImeWindow=null
    """.trimIndent()
    private val windows = """
        Window #0 Window{111 u0 main window}:
          mDisplayId=0
          mOwnerUid=10001 showForAllUsers=false package=com.example.main appop=NONE
        Window #1 Window{222 u0 virtual window}:
          mDisplayId=9
          mOwnerUid=10002 showForAllUsers=false package=com.example.target appop=NONE
    """.trimIndent()
    private fun parse(t: String = tasks, w: String = windows, d: String = displays, session: String = "session-a") =
        ScreenObservationParser.parseVirtual(t, w, d, 42, DisplaySession(9, session))

    @Test fun selectsOnlyOwnedDisplayWhileMainKeyboardIsVisible() {
        val result = parse()
        assertEquals("com.example.target", result.foregroundPackage)
        assertEquals("com.example.target.Target", result.foregroundActivity)
        assertEquals("222", result.focusedWindowId)
        assertEquals(false, result.keyboardVisible)
        assertEquals(9, result.displayId)
        assertEquals("session-a", result.sessionId)
    }
    @Test fun absentDisplayNeverFallsBackToMain() {
        assertNull(parse(t = tasks.replace("Display #9", "Display #8")).foregroundPackage)
        assertNull(parse(d = displays.replace("mDisplayId=9", "mDisplayId=8")).focusedPackage)
    }
    @Test fun focusedWindowMustActuallyBelongToOwnedDisplay() {
        assertNull(parse(w = windows.replace("mDisplayId=9", "mDisplayId=0")).focusedWindowId)
        assertNull(parse(d = displays.replace("Window{222 u0 virtual window}", "Window{111 u0 main window}")).focusedWindowId)
    }
    @Test fun duplicatedOrUnfocusedTargetIsUnavailable() {
        assertNull(parse(d = displays + "\nDisplay: mDisplayId=9\n mCurrentFocus=null").foregroundPackage)
        assertNull(parse(d = displays.replace("mCurrentFocus=Window{222 u0 virtual window}", "mCurrentFocus=null")).foregroundPackage)
        assertNull(parse(t = tasks + "\n  topResumedActivity=ActivityRecord{ccc u0 com.example.other/.Other t3}").foregroundPackage)
    }
    @Test fun windowOwnerMismatchAndUnknownImeAreNotAcceptedAsKnownSafe() {
        assertNull(parse(w = windows.replace("package=com.example.target", "package=com.example.other")).focusedPackage)
        assertNull(parse(d = displays.replace("mImeWindow=null", "mImeWindow=Window{333 u0 keyboard}")).keyboardVisible)
    }
    @Test fun repeatedNullImeWindowsAndSeparateTokenFieldAgreeOnHiddenKeyboard() {
        val actualShape = displays.replace("mImeWindow=null",
            "mImeWindow=null\n  mImeWindowToken=null\n  mImeWindow=null")
        assertEquals(false, parse(d = actualShape).keyboardVisible)
    }
    @Test fun conflictingRepeatedImeOrTokenAloneDoesNotProveHiddenKeyboard() {
        assertNull(parse(d = displays + "\n  mImeWindow=Window{333 u0 keyboard}").keyboardVisible)
        assertNull(parse(d = displays.replace("mImeWindow=null", "mImeWindowToken=null")).keyboardVisible)
    }
    @Test fun sessionAndDisplayIdentityParticipateInConfirmationBinding() {
        val a = parse()
        assertTrue(a.sameTarget(parse()))
        assertFalse(a.sameTarget(parse(session = "session-b")))
        assertFalse(a.sameTarget(a.copy(displayId = 0)))
        assertFalse(a.sameTarget(a.copy(sessionId = null)))
    }
    @Test fun legacyDefaultParserStillRejectsActiveSecondaryDisplay() {
        val result = ScreenObservationParser.parse(tasks, windows, "Current Input Method Manager state:\n  mInputShown=false", 42, displays)
        assertNull(result.foregroundPackage)
    }
}
