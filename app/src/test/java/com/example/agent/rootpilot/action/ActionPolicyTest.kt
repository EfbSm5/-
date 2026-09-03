package com.example.agent.rootpilot.action

import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotKey
import com.example.agent.rootpilot.model.ScreenSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {
    private val policy = ActionPolicy()

    @Test
    fun normalizesCoordinatesAgainstCurrentScreenSize() {
        assertEquals(
            ActionPolicyResult.Allowed(ExecutableRootAction.Tap(599, 1319)),
            policy.toExecutable(
                RootPilotAction.Tap(500, 500, "点击"),
                ScreenSize(width = 1_200, height = 2_640),
            ),
        )
    }

    @Test
    fun rejectsNonExecutableControlActions() {
        assertTrue(
            policy.toExecutable(
                RootPilotAction.AskUser("请接管"),
                ScreenSize(1_200, 2_640),
            ) is ActionPolicyResult.Rejected,
        )
        assertTrue(
            policy.toExecutable(
                RootPilotAction.Tap(1, 1, "点击"),
                ScreenSize(0, 2_640),
            ) is ActionPolicyResult.Rejected,
        )
        assertEquals(
            ActionPolicyResult.Allowed(ExecutableRootAction.Key(RootPilotKey.BACK)),
            policy.toExecutable(
                RootPilotAction.Key(RootPilotKey.BACK, "返回"),
                ScreenSize(1_200, 2_640),
            ),
        )
    }

    @Test
    fun manualConfirmationOnlyBypassesTapAndSwipeInAutomaticMode() {
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Tap(1, 1, "点击"),
                manualConfirmation = true,
            ),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Swipe(1, 1, 2, 2, 300, "滑动"),
                manualConfirmation = true,
            ),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Tap(1, 1, "点击"),
                manualConfirmation = false,
            ).not(),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Swipe(1, 1, 2, 2, 300, "滑动"),
                manualConfirmation = false,
            ).not(),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Type("hello", "输入"),
                manualConfirmation = false,
            ),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.Key(RootPilotKey.HOME, "返回桌面"),
                manualConfirmation = false,
            ),
        )
        assertTrue(
            policy.requiresConfirmation(
                action = RootPilotAction.OpenApp("com.android.settings", "打开设置"),
                manualConfirmation = false,
            ).not(),
        )
    }

    @Test
    fun openAppOnlyAllowsSettingsPackage() {
        assertEquals(
            ActionPolicyResult.Allowed(ExecutableRootAction.OpenApp(RootPilotApp.SETTINGS)),
            policy.toExecutable(
                RootPilotAction.OpenApp("com.android.settings", "打开设置"),
                ScreenSize(1_200, 2_640),
            ),
        )
        assertTrue(
            policy.toExecutable(
                RootPilotAction.OpenApp("com.example.untrusted", "打开应用"),
                ScreenSize(1_200, 2_640),
            ) is ActionPolicyResult.Rejected,
        )
    }
}
