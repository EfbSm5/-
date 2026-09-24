package com.example.agent.rootpilot

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.ui.AppLaunchPicker
import com.example.agent.rootpilot.ui.RootPilotScreen
import com.example.agent.rootpilot.ui.RootPilotTheme
import com.example.agent.rootpilot.ui.ChatScreen
import com.example.agent.rootpilot.chat.ChatMessage
import com.example.agent.rootpilot.chat.ChatUiState
import com.example.agent.ui.theme.AgentTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class RootPilotMiuixInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: RootPilotActivity

    @Before
    fun launchRealActivity() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RootPilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as RootPilotActivity
    }

    @After
    fun finishActivity() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun settingsSwitchCallsBackOnceAndEmptyTokenHasPasswordSemantics() {
        val fixture = Fixture()
        showFixture { fixture.Content() }
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("manual_confirmation").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("manual_confirmation").assertIsOff()
        compose.runOnIdle {
            assertEquals(1, fixture.manualChanges)
            assertEquals(false, fixture.state.value.config.manualConfirmation)
        }
        compose.onNodeWithTag("api_edit").performScrollTo().performClick()
        compose.onNodeWithTag("api_token").performScrollTo().assertIsEnabled()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle {
            assertEquals("", fixture.api.value.draft.apiKey)
            assertEquals(1, fixture.edits)
        }
    }

    @Test
    fun busyAndCompletedCleanupDisableEditorsAndManualSwitch() {
        val fixture = Fixture()
        fixture.state.value = RootPilotUiState(status = RootPilotStatus.EXECUTING, running = true)
        showFixture { fixture.Content() }
        compose.onNodeWithTag("open_settings").performClick()
        for (status in listOf(RootPilotStatus.EXECUTING, RootPilotStatus.COMPLETED)) {
            compose.runOnIdle {
                fixture.state.value = fixture.state.value.copy(status = status, running = true)
                fixture.api.value = fixture.api.value.copy(editing = false)
            }
            compose.onNodeWithTag("api_edit").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("manual_confirmation").performScrollTo().assertIsNotEnabled()
            // An already-open editor must also remain disabled while the run owns the UI.
            compose.runOnIdle { fixture.api.value = fixture.api.value.copy(editing = true) }
            listOf("api_token", "api_base_url", "api_model", "api_save", "api_test", "api_clear")
                .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsNotEnabled() }
        }
        compose.runOnIdle {
            assertEquals(0, fixture.manualChanges)
            assertEquals(0, fixture.edits)
        }
    }

    @Test
    fun appPickerSearchSelectionAndClearHaveSingleCallbacks() {
        val apps = mutableStateOf(AppLaunchUiState(apps = listOf(
            RootPilotApp("fixture.notes", "笔记示例", "fixture.notes.MainActivity"),
            RootPilotApp("fixture.clock", "时钟示例", "fixture.clock.MainActivity"),
        )))
        val changes = mutableListOf<Pair<String, Boolean>>()
        var clears = 0
        showFixture {
            AppLaunchPicker(
                state = apps.value,
                onAllowedChanged = { name, allowed ->
                    changes += name to allowed
                    apps.value = apps.value.copy(allowedPackages = if (allowed)
                        apps.value.allowedPackages + name else apps.value.allowedPackages - name)
                },
                onClear = { clears++; apps.value = apps.value.copy(allowedPackages = emptySet()) },
                onRefresh = {}, onDismiss = {},
            )
        }
        compose.onNodeWithTag("launch_app_search").assertIsDisplayed()
        saveScreenshot(compose.onNode(isDialog()), "miuix-app-picker.png")
        compose.onNodeWithTag("launch_app_search").performTextReplacement("笔记")
        compose.onNodeWithTag("launch_app_fixture.clock").assertDoesNotExist()
        compose.onNodeWithTag("launch_app_fixture.notes").assertIsOff().performClick()
        compose.onNodeWithTag("launch_app_fixture.notes").assertIsOn()
        compose.onNodeWithTag("launch_apps_clear").performScrollTo().performClick()
        compose.onNodeWithTag("launch_app_fixture.notes").assertIsOff()
        compose.onNodeWithTag("launch_app_search").performScrollTo().performTextReplacement("fixture.clock")
        compose.onNodeWithTag("launch_app_fixture.notes").assertDoesNotExist()
        compose.onNodeWithTag("launch_app_fixture.clock").assertExists()
        compose.onNodeWithTag("launch_app_search").performTextReplacement("no-matching-fixture")
        compose.onNodeWithTag("launch_app_fixture.clock").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("fixture.notes" to true), changes)
            assertEquals(1, clears)
            assertTrue(apps.value.allowedPackages.isEmpty())
        }
    }

    @Test
    fun keyboardKeepsNavigationInputSendAndStopVisibleAtLargeFont() {
        val fixture = Fixture()
        showFixture(fontScale = 1.5f) { fixture.Content() }
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("chat_draft").performClick()
        compose.waitUntil(5_000) {
            var visible = false
            instrumentation.runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        compose.onNodeWithTag("chat_draft").performTextReplacement("一\n二\n三\n四\n五🙂")
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
        compose.onNodeWithTag("chat_draft").assertIsDisplayed()
        compose.onNodeWithTag("chat_send").assertIsDisplayed().assertIsEnabled()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-chat-keyboard.png")
        compose.runOnIdle { fixture.chat.value = fixture.chat.value.copy(generating = true) }
        compose.onNodeWithTag("chat_send").assertIsNotEnabled()
        compose.onNodeWithTag("open_settings").assertIsNotEnabled()
        compose.onNodeWithTag("chat_stop").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.chatStops)
            assertEquals("一\n二\n三\n四\n五🙂", fixture.chat.value.draft)
        }
    }

    @Test
    fun taskInputUploadAndConfirmationKeepSingleCallbacksAndPrivateTextHidden() {
        val fixture = Fixture()
        showFixture { fixture.Content() }
        compose.onNodeWithTag("task_input").performTextReplacement("中文测试\n只读示例")
        compose.onNodeWithTag("screen_upload").performScrollTo().performClick()
        compose.onNodeWithTag("start_task").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("中文测试\n只读示例", fixture.state.value.config.task)
            assertEquals(1, fixture.uploadChanges)
            assertEquals(1, fixture.starts)
            fixture.state.value = fixture.state.value.copy(
                status = RootPilotStatus.WAITING_CONFIRMATION,
                pendingAction = RootPilotAction.Type("PRIVATE_TEXT_SENTINEL", "PRIVATE_REASON_SENTINEL"),
            )
        }
        compose.onNodeWithText("PRIVATE_TEXT_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE_REASON_SENTINEL", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("start_task").assertIsNotEnabled()
        compose.onNodeWithTag("confirm_action").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fixture.confirmations) }
    }

    @Test
    fun lightSettingsAt320DpAndLargeFontCaptureFixtureOnly() {
        captureSettings(dark = false, name = "light")
    }

    @Test
    fun darkSettingsAt320DpAndLargeFontCaptureFixtureOnly() {
        captureSettings(dark = true, name = "dark")
    }

    private fun captureSettings(dark: Boolean, name: String) {
        val fixture = Fixture()
        var themeColors: ThemeColors? = null
        showFixture(dark = dark, fontScale = 1.5f) {
            // A sibling probe must not supply a missing content color to the actual screen.
            RootPilotTheme {
                val colors = ThemeColors(
                    content = LocalContentColor.current,
                    onSurface = MiuixTheme.colorScheme.onSurface,
                    surface = MaterialTheme.colorScheme.surface,
                    miuixSurface = MiuixTheme.colorScheme.surface,
                    background = MaterialTheme.colorScheme.background,
                )
                SideEffect { themeColors = colors }
            }
            fixture.Content()
        }
        compose.onNodeWithTag("task_input").assertIsDisplayed()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-home-$name.png")
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("api_status").assertIsDisplayed()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-settings-$name.png")
        compose.onNodeWithTag("manual_confirmation").performScrollTo().assertIsDisplayed()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-settings-$name-manual.png")
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("chat_draft").assertIsDisplayed()
        compose.onNodeWithTag("chat_send").assertIsEnabled()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-chat-$name.png")
        compose.onNodeWithTag("back_to_task").performClick()
        compose.onNodeWithTag("task_input").assertIsDisplayed()
        compose.runOnIdle {
            val colors = checkNotNull(themeColors)
            assertEquals("$name root content must use Miuix onSurface", colors.onSurface, colors.content)
            assertEquals("$name Material surface must use Miuix surface", colors.miuixSurface, colors.surface)
            assertTrue("$name cards must differ from the page background", colors.surface != colors.background)
            assertTrue("$name group titles must contrast with the page", contrast(colors.content, colors.background) >= 4.5f)
            assertTrue("$name text must contrast with cards", contrast(colors.content, colors.surface) >= 4.5f)
            assertEquals("$name must use the requested light/dark scheme", dark,
                colors.background.luminance() < colors.content.luminance())
        }
    }

    private data class ThemeColors(
        val content: Color,
        val onSurface: Color,
        val surface: Color,
        val miuixSurface: Color,
        val background: Color,
    )

    private fun contrast(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
            (minOf(firstLuminance, secondLuminance) + 0.05f)
    }

    private fun showFixture(
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        instrumentation.runOnMainSync {
            activity.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    this.fontScale = fontScale
                    screenWidthDp = 320
                }
                val density = Density(LocalDensity.current.density, fontScale)
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density) {
                    AgentTheme(darkTheme = dark, dynamicColor = false) {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag(FIXTURE_TAG)) { content() }
                    }
                }
            }
        }
    }

    private fun saveScreenshot(node: SemanticsNodeInteraction, name: String) {
        compose.waitForIdle()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val file = File(instrumentation.targetContext.cacheDir, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 0)
    }

    private class Fixture {
        val state = mutableStateOf(RootPilotUiState())
        val api = mutableStateOf(ApiConfigUiState(
            draft = RootPilotApiConfig(apiKey = "", baseUrl = "https://fixture.invalid", model = "fixture-model"),
            configured = true, editing = false, busy = false,
        ))
        var manualChanges = 0
        var edits = 0
        var uploadChanges = 0
        var starts = 0
        var confirmations = 0
        var chatStops = 0
        val chat = mutableStateOf(ChatUiState(draft = "测试草稿", messages = listOf(
            ChatMessage(1, "assistant", "## 测试回答\n\n- 仅用于界面验收\n- 不请求网络", complete = true),
        )))

        @Composable
        fun Content() {
            RootPilotScreen(
                state = state.value, apiState = api.value, modifier = Modifier.fillMaxSize(),
                chatGenerating = chat.value.generating,
                chatContent = {
                    ChatScreen(
                        state = chat.value,
                        configured = true, onDraftChange = { chat.value = chat.value.copy(draft = it) },
                        onEffortChange = {}, onSend = {},
                        onStop = { chatStops++; chat.value = chat.value.copy(generating = false) },
                        onNewConversation = {},
                    )
                },
                onApiKeyChanged = {}, onBaseUrlChanged = {}, onModelChanged = {},
                onSaveApiConfig = {},
                onEditApiConfig = { edits++; api.value = api.value.copy(editing = true) },
                onCancelApiConfigEdit = { api.value = api.value.copy(editing = false) },
                onClearApiConfig = {}, onTestConnection = {},
                onTaskChanged = { state.value = state.value.copy(config = state.value.config.copy(task = it)) },
                onTestRoot = {}, onCaptureScreen = {}, onSingleStep = {}, onAutoExecute = { starts++ },
                onStop = {}, onConfirmAction = { confirmations++ }, onRecoverInterruptedRun = {},
                onDiscardInterruptedRun = {},
                onManualConfirmationChanged = {
                    manualChanges++
                    state.value = state.value.copy(config = state.value.config.copy(manualConfirmation = it))
                },
                onScreenUploadChanged = {
                    uploadChanges++
                    state.value = state.value.copy(config = state.value.config.copy(allowScreenUpload = it))
                }, overlayAllowed = false, inputMethodEnabled = false,
                inputMessage = null, onInputMethodSettings = {}, onOverlayPermission = {},
            )
        }
    }

    private companion object {
        const val FIXTURE_TAG = "miuix_fixture"
    }
}
