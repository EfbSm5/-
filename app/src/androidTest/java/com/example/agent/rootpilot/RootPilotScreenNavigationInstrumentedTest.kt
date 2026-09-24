package com.example.agent.rootpilot

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.ui.RootPilotScreen
import com.example.agent.rootpilot.ui.RunHistoryScreen
import com.example.agent.rootpilot.history.RunHistoryState
import com.example.agent.ui.theme.AgentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotScreenNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun historyLivesInSettingsAndReturnsWithoutDiscardingTask() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("open_history").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("open_history").performScrollTo().performClick()
        compose.onNodeWithTag("history_empty").assertExists()
        compose.onNodeWithTag("history_back").performClick()
        compose.onNodeWithTag("open_history").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("task_input").assertExists()
    }

    @Test
    fun homeIsMinimalAndSettingsBackDoesNotDiscardDraft() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("task_input").assertExists()
        compose.onNodeWithText("允许上传当前屏幕截图").assertExists()
        compose.onNodeWithText("执行方式：逐步确认").assertExists()
        compose.onNodeWithTag("stop_task").assertDoesNotExist()
        compose.onNodeWithTag("api_token").assertDoesNotExist()
        compose.onNodeWithTag("launch_apps").assertDoesNotExist()
        compose.onNodeWithText("执行日志").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("api_edit").performScrollTo().performClick()
        compose.onNodeWithTag("api_token").assertExists()
        compose.runOnIdle { fixture.back!!.onBackPressed() }
        compose.onNodeWithTag("start_task").assertIsNotEnabled()
        compose.onNodeWithTag("api_setup_hint").performScrollTo().performClick()
        compose.onNodeWithTag("api_save").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(fixture.api.value.editing)
            assertEquals(0, fixture.cancelled)
        }
        compose.onNodeWithText("取消", useUnmergedTree = false).performScrollTo().performClick()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("start_task").assertIsEnabled()
    }

    @Test
    fun busySettingsAllowRevocationAndStopAndReturnToSafePendingPreview() {
        val fixture = Fixture()
        fixture.state.value = RootPilotUiState(status = RootPilotStatus.WAITING_CONFIRMATION,
            pendingAction = RootPilotAction.Type("PRIVATE_TEXT_SENTINEL", "PRIVATE_REASON_SENTINEL"))
        compose.setContent { fixture.Content() }
        compose.onNodeWithText("PRIVATE_TEXT_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_REASON_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithText("请在输入法面板核对完整文本", substring = true).assertExists()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_stop").assertIsEnabled().performClick()
        compose.onNodeWithTag("api_edit").assertIsNotEnabled()
        compose.onNodeWithTag("launch_apps").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("launch_apps_clear").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("confirm_action").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, fixture.stopped)
            assertEquals(1, fixture.cleared)
            fixture.state.value = fixture.state.value.copy(pendingAction = null)
        }
        compose.onNodeWithTag("confirm_action").assertIsNotEnabled()
    }

    @Test
    fun settingsRestoresAndDebugStartsCollapsed() {
        val fixture = Fixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Content() }
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithText("测试 Root").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("back_to_task").assertExists()
        compose.onNodeWithTag("toggle_debug").performScrollTo().performClick()
        compose.onNodeWithText("测试 Root").assertExists()
        compose.onNodeWithText("执行日志").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithText("执行日志").assertDoesNotExist()
    }

    @Test
    fun recoveryRemainsOnHomeAndBlocksStartingANewTask() {
        val fixture = Fixture()
        fixture.state.value = RootPilotUiState(status = RootPilotStatus.RECOVERY_REQUIRED, errorMessage = "恢复测试")
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("start_task").assertIsNotEnabled()
        compose.onNodeWithText("上次任务中断").assertExists()
        compose.onNodeWithText("从当前屏幕重新规划").assertIsEnabled()
        compose.onNodeWithText("放弃上次任务").assertExists()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("toggle_debug").performScrollTo().performClick()
        compose.onNodeWithText("测试 Root").assertIsNotEnabled()
        compose.onNodeWithText("单步执行").assertIsNotEnabled()
    }

    @Test
    fun stoppingBlocksStartEditingAndRepeatedStopButSettingsRemainAccessible() {
        val fixture = Fixture()
        fixture.state.value = RootPilotUiState(status = RootPilotStatus.STOPPING)
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("task_input").assertIsNotEnabled()
        compose.onNodeWithTag("start_task").assertIsNotEnabled()
        compose.onNodeWithTag("stop_task").assertIsNotEnabled()
        compose.onNodeWithTag("task_result_title").assertExists()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_stop").assertIsNotEnabled()
        compose.onNodeWithTag("api_edit").assertIsNotEnabled()
        compose.onNodeWithTag("launch_apps").assertIsEnabled()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("stop_task").assertIsNotEnabled()
    }

    @Test
    fun settingsSwitchDelegatesOnceAndCredentialEditorRetainsPasswordSemantics() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("manual_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("manual_confirmation").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.manualChanges)
            assertEquals(false, fixture.state.value.config.manualConfirmation)
        }
        compose.onNodeWithTag("api_edit").performScrollTo().performClick()
        compose.onNodeWithTag("api_token").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("manual_confirmation").assertDoesNotExist()
        compose.onNodeWithText("执行方式：自动点击/滑动").assertExists()
    }

    @Test
    fun completedButStillCleaningUpKeepsSettingsMutationsDisabled() {
        val fixture = Fixture()
        fixture.state.value = fixture.state.value.copy(status = RootPilotStatus.COMPLETED, running = true)
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("start_task").assertIsNotEnabled()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("api_edit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("manual_confirmation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("launch_apps").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, fixture.manualChanges) }
    }

    private class Fixture {
        val state = mutableStateOf(RootPilotUiState())
        val api = mutableStateOf(ApiConfigUiState(configured = true, editing = false, busy = false))
        var back: OnBackPressedDispatcher? = null
        var stopped = 0
        var cleared = 0
        var cancelled = 0
        var manualChanges = 0

        @Composable
        fun Content() {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AgentTheme {
                RootPilotScreen(
                    historyContent = { onBack -> RunHistoryScreen(RunHistoryState(), {}, onBack) },
                    state = state.value, apiState = api.value,
                    onApiKeyChanged = {}, onBaseUrlChanged = {}, onModelChanged = {},
                    onSaveApiConfig = {}, onEditApiConfig = { api.value = api.value.copy(editing = true) },
                    onCancelApiConfigEdit = { cancelled++; api.value = api.value.copy(editing = false) },
                    onClearApiConfig = {}, onTestConnection = {}, onTaskChanged = {},
                    onTestRoot = {}, onCaptureScreen = {}, onSingleStep = {}, onAutoExecute = {},
                    onStop = { stopped++ }, onConfirmAction = {}, onRecoverInterruptedRun = {},
                    onDiscardInterruptedRun = {}, onManualConfirmationChanged = {
                        manualChanges++
                        state.value = state.value.copy(config = state.value.config.copy(manualConfirmation = it))
                    }, onScreenUploadChanged = {},
                    overlayAllowed = false, inputMethodEnabled = false, inputMessage = null,
                    onInputMethodSettings = {}, onOverlayPermission = {},
                    onClearLaunchApps = { cleared++ },
                )
            }
        }
    }
}
