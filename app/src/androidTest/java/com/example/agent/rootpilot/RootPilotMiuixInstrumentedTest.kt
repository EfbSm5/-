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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.ui.AppLaunchPicker
import com.example.agent.rootpilot.ui.RootPilotScreen
import com.example.agent.rootpilot.ui.RootPilotSettingsTheme
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
            RootPilotSettingsTheme {
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
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("api_status").assertIsDisplayed()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-settings-$name.png")
        compose.onNodeWithTag("manual_confirmation").performScrollTo().assertIsDisplayed()
        saveScreenshot(compose.onNodeWithTag(FIXTURE_TAG), "miuix-settings-$name-manual.png")
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

        @Composable
        fun Content() {
            RootPilotScreen(
                state = state.value, apiState = api.value, modifier = Modifier.fillMaxSize(),
                onApiKeyChanged = {}, onBaseUrlChanged = {}, onModelChanged = {},
                onSaveApiConfig = {},
                onEditApiConfig = { edits++; api.value = api.value.copy(editing = true) },
                onCancelApiConfigEdit = { api.value = api.value.copy(editing = false) },
                onClearApiConfig = {}, onTestConnection = {}, onTaskChanged = {},
                onTestRoot = {}, onCaptureScreen = {}, onSingleStep = {}, onAutoExecute = {},
                onStop = {}, onConfirmAction = {}, onRecoverInterruptedRun = {},
                onDiscardInterruptedRun = {},
                onManualConfirmationChanged = {
                    manualChanges++
                    state.value = state.value.copy(config = state.value.config.copy(manualConfirmation = it))
                },
                onScreenUploadChanged = {}, overlayAllowed = false, inputMethodEnabled = false,
                inputMessage = null, onInputMethodSettings = {}, onOverlayPermission = {},
            )
        }
    }

    private companion object {
        const val FIXTURE_TAG = "miuix_fixture"
    }
}
