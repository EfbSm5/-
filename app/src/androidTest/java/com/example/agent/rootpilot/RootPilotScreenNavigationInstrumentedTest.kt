package com.example.agent.rootpilot

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
    fun settingsSubpagesRestoreAndReturnThroughSettingsToChat() {
        val fixture = Fixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Content() }
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithText("开启悬浮操作面板").assertDoesNotExist()
        compose.onNodeWithTag("open_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("api_status").assertDoesNotExist()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("系统权限").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("api_status").assertExists()
        compose.onNodeWithTag("toggle_debug").performScrollTo().performClick()
        compose.onNodeWithText("测试 Root").assertExists()
        compose.onNodeWithTag("api_status").assertDoesNotExist()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("open_permissions").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, fixture.started)
            assertEquals(0, fixture.stopped)
        }
    }

    @Test
    fun bottomNavigationSwitchesPagesAndPreservesTaskDraft() {
        val fixture = Fixture()
        fixture.state.value = fixture.state.value.copy(
            config = fixture.state.value.config.copy(task = "本地导航草稿"),
        )
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("task_tab").assertIsSelected()
        compose.onNodeWithTag("open_chat").assertIsNotSelected().performClick()
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        compose.onNodeWithTag("open_chat").assertIsSelected()
        taskNavigation().assertIsNotSelected().performClick()
        compose.onNodeWithTag("task_tab").assertIsSelected()
        compose.onNodeWithTag("fake_chat").assertDoesNotExist()
        compose.onNodeWithTag("task_input").assertExists()
        compose.runOnIdle {
            assertEquals("本地导航草稿", fixture.state.value.config.task)
            assertEquals(0, fixture.started)
            assertEquals(0, fixture.stopped)
        }
    }

    @Test
    fun settingsAndHistoryHideBottomNavigationAndReturnToChat() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("open_chat").assertDoesNotExist()
        compose.onNodeWithTag("open_history").performScrollTo().performClick()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("history_empty").assertExists()
        compose.onNodeWithTag("back_to_task").assertDoesNotExist()
        compose.onNodeWithTag("history_back").performClick()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("open_chat").assertIsSelected()
    }

    @Test
    fun settingsRemembersChatOriginAcrossRestorationAndSystemBack() {
        val fixture = Fixture()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { fixture.Content() }
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.runOnIdle { fixture.back!!.onBackPressed() }
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        compose.onNodeWithTag("open_chat").assertIsSelected()
        compose.runOnIdle { fixture.back!!.onBackPressed() }
        compose.onNodeWithTag("task_tab").assertIsSelected()
    }

    @Test
    fun generatingChatBlocksNavigationAndSettingsUntilGenerationEnds() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("open_chat").performClick()
        compose.runOnIdle { fixture.chatGenerating.value = true }
        taskNavigation().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("open_settings").assertIsNotEnabled().performClick()
        compose.runOnIdle { fixture.back!!.onBackPressed() }
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        compose.onNodeWithTag("open_chat").assertIsSelected()
        compose.onNodeWithTag("api_edit").assertDoesNotExist()
        compose.runOnIdle { fixture.chatGenerating.value = false }
        compose.onNodeWithTag("open_settings").assertIsEnabled().performClick()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("fake_chat").assertIsDisplayed()
        taskNavigation().assertIsEnabled().performClick()
        compose.onNodeWithTag("task_tab").assertIsSelected()
    }

    @Test
    fun returningFromSettingsCannotReenterChatWhenTaskBecomesBusy() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        listOf(false, true).forEach { systemBack ->
            compose.runOnIdle { fixture.state.value = RootPilotUiState() }
            compose.onNodeWithTag("open_chat").performClick()
            compose.onNodeWithTag("open_settings").performClick()
            compose.runOnIdle {
                fixture.state.value = RootPilotUiState(
                    status = RootPilotStatus.WAITING_CONFIRMATION,
                    running = false,
                    pendingAction = RootPilotAction.Type("PRIVATE_TEXT_SENTINEL", "PRIVATE_REASON_SENTINEL"),
                )
            }
            if (systemBack) {
                compose.runOnIdle { fixture.back!!.onBackPressed() }
            } else {
                compose.onNodeWithTag("back_to_task").performClick()
            }
            compose.onNodeWithTag("task_tab").assertIsSelected()
            compose.onNodeWithTag("open_chat").assertIsNotEnabled()
            compose.onNodeWithTag("fake_chat").assertDoesNotExist()
            compose.onNodeWithTag("confirm_action").performScrollTo().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, fixture.confirmed) }
        }
    }

    @Test
    fun everyBusyTaskStateBlocksChatIncludingTerminalCleanup() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        val busyStates = listOf(
            RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL,
            RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN,
            RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.STOPPING,
        ).map { RootPilotUiState(status = it) } +
            RootPilotUiState(status = RootPilotStatus.COMPLETED, running = true)
        busyStates.forEach { state ->
            compose.runOnIdle { fixture.state.value = state }
            compose.onNodeWithTag("open_chat").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("task_tab").assertIsSelected()
            compose.onNodeWithTag("fake_chat").assertDoesNotExist()
            compose.onNodeWithTag("open_settings").assertIsEnabled()
        }
        compose.runOnIdle { fixture.state.value = RootPilotUiState() }
        compose.onNodeWithTag("open_chat").assertIsEnabled().performClick()
        compose.runOnIdle {
            fixture.state.value = RootPilotUiState(status = RootPilotStatus.REQUESTING_MODEL)
        }
        compose.onNodeWithTag("task_tab").assertIsSelected()
        compose.onNodeWithTag("fake_chat").assertDoesNotExist()
    }

    @Test
    fun homePlacesComposerAndOverviewAboveBottomNavigation() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("task_tab")
            .assert(hasAnyAncestor(hasTestTag("bottom_navigation")))
        compose.onNodeWithTag("open_chat")
            .assert(hasAnyAncestor(hasTestTag("bottom_navigation")))
        compose.onNodeWithTag("task_input")
            .assert(hasAnyAncestor(hasTestTag("task_composer")))
        compose.onNodeWithTag("start_task")
            .assert(hasAnyAncestor(hasTestTag("task_composer")))
        compose.onNodeWithTag("task_status")
            .assert(hasAnyAncestor(hasTestTag("task_overview")))
        compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
        val navigation = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("open_settings").fetchSemanticsNode().boundsInRoot
        assertTrue("设置入口应位于底部导航上方", settings.bottom <= navigation.top)
        listOf("task_composer", "task_overview").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val content = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val bottom = compose.onNodeWithTag("bottom_navigation").fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 不应与底部导航重叠", content.bottom <= bottom.top)
        }
    }

    @Test
    fun uploadConsentAndSafetyControlsSurviveNavigationWithoutImplicitActions() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("screen_upload").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("screen_upload").assertIsOn()
        compose.onNodeWithTag("open_chat").performClick()
        taskNavigation().performClick()
        compose.onNodeWithTag("screen_upload").performScrollTo().assertIsOn()
        compose.onNodeWithText("允许上传当前屏幕截图").assertIsDisplayed()
        compose.onNodeWithTag("screen_upload").performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(2, fixture.uploadChanges)
            assertEquals(0, fixture.started)
            assertEquals(0, fixture.confirmed)
            assertEquals(0, fixture.recovered)
            assertEquals(0, fixture.discarded)
        }
    }

    @Test
    fun stoppingAndStoppedKeepIrreversibleActionWarningAccessible() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        listOf(RootPilotStatus.STOPPING, RootPilotStatus.STOPPED).forEach { status ->
            compose.runOnIdle { fixture.state.value = RootPilotUiState(status = status) }
            compose.onNodeWithText("已发生的操作不会自动撤销", substring = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithText("已发生的操作不会自动撤销", substring = true)
                .performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals(0, fixture.started)
            assertEquals(0, fixture.stopped)
        }
    }

    private fun taskNavigation() = compose.onNode(
        hasTestTag("task_tab") or hasTestTag("back_to_task"),
    )

    @Test
    fun historyLivesInSettingsAndReturnsWithoutDiscardingTask() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.onNodeWithTag("open_history").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("open_history").performScrollTo().performClick()
        compose.onNodeWithTag("history_empty").assertExists()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("history_back").performClick()
        compose.onNodeWithTag("open_history").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("task_input").assertExists()
        compose.onNodeWithTag("task_tab").assertIsSelected()
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
        compose.onNodeWithText("请在输入法面板核对完整文本", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PRIVATE_TEXT_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_REASON_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("confirm_action").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(0, fixture.confirmed)
            assertEquals(1, fixture.stopped)
            assertEquals(1, fixture.cleared)
        }
        compose.onNodeWithTag("confirm_action").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.confirmed)
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
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("api_status").assertExists()
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithText("无法确认上一步 Root 动作是否已经生效，不会自动重放。", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, fixture.recovered)
            assertEquals(0, fixture.discarded)
            assertEquals(0, fixture.started)
        }
        compose.onNodeWithText("从当前屏幕重新规划").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fixture.recovered) }
        compose.onNodeWithText("放弃上次任务").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fixture.discarded) }
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
        val chatGenerating = mutableStateOf(false)
        var back: OnBackPressedDispatcher? = null
        var stopped = 0
        var cleared = 0
        var cancelled = 0
        var manualChanges = 0
        var started = 0
        var confirmed = 0
        var recovered = 0
        var discarded = 0
        var uploadChanges = 0

        @Composable
        fun Content() {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            AgentTheme {
                RootPilotScreen(
                    chatContent = { BasicText("本地聊天夹具", Modifier.testTag("fake_chat")) },
                    chatGenerating = chatGenerating.value,
                    historyContent = { onBack -> RunHistoryScreen(RunHistoryState(), {}, onBack) },
                    state = state.value, apiState = api.value,
                    onApiKeyChanged = {}, onBaseUrlChanged = {}, onModelChanged = {},
                    onSaveApiConfig = {}, onEditApiConfig = { api.value = api.value.copy(editing = true) },
                    onCancelApiConfigEdit = { cancelled++; api.value = api.value.copy(editing = false) },
                    onClearApiConfig = {}, onTestConnection = {}, onTaskChanged = {
                        state.value = state.value.copy(config = state.value.config.copy(task = it))
                    },
                    onTestRoot = {}, onCaptureScreen = {}, onSingleStep = {}, onAutoExecute = { started++ },
                    onStop = { stopped++ }, onConfirmAction = { confirmed++ },
                    onRecoverInterruptedRun = { recovered++ },
                    onDiscardInterruptedRun = { discarded++ }, onManualConfirmationChanged = {
                        manualChanges++
                        state.value = state.value.copy(config = state.value.config.copy(manualConfirmation = it))
                    }, onScreenUploadChanged = {
                        uploadChanges++
                        state.value = state.value.copy(config = state.value.config.copy(allowScreenUpload = it))
                    },
                    overlayAllowed = false, inputMethodEnabled = false, inputMessage = null,
                    onInputMethodSettings = {}, onOverlayPermission = {},
                    onClearLaunchApps = { cleared++ },
                )
            }
        }
    }
}
