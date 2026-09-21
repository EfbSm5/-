package com.example.agent.rootpilot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AllowlistedAppCatalog
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.ui.AppLaunchPicker
import com.example.agent.ui.theme.AgentTheme
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotAppLaunchPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchSelectReloadAndClearArePersistedWithoutLaunchingApps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "launch-picker-").toFile()
        val file = directory.resolve("selection.json")
        val store = AppLaunchAllowlistStore(file)
        val apps = listOf(
            RootPilotApp("com.example.notes", "测试笔记", "com.example.notes.Main"),
            RootPilotApp("com.example.reader", "测试阅读", "com.example.reader.Main"),
        )
        val state = mutableStateOf(AppLaunchUiState(apps = apps, allowedPackages = store.read()))
        try {
            compose.setContent {
                AgentTheme {
                    AppLaunchPicker(
                        state = state.value,
                        onAllowedChanged = { packageName, allowed ->
                            val selected = if (allowed) store.read() + packageName else store.read() - packageName
                            store.save(selected)
                            state.value = state.value.copy(allowedPackages = store.read())
                        },
                        onClear = {
                            store.save(emptySet())
                            state.value = state.value.copy(allowedPackages = store.read())
                        },
                        onRefresh = {}, onDismiss = {},
                    )
                }
            }
            compose.onNodeWithText("已允许 0 个应用").assertExists()
            compose.onNodeWithTag("launch_app_com.example.notes").assertIsOff()
            compose.onNodeWithTag("launch_app_search").performTextInput("笔记")
            compose.onNodeWithTag("launch_app_com.example.reader").assertDoesNotExist()
            compose.onNodeWithTag("launch_app_com.example.notes").performClick().assertIsOn()
            assertEquals(setOf("com.example.notes"), AppLaunchAllowlistStore(file).read())
            assertEquals(listOf(apps.first()), AllowlistedAppCatalog(AppCatalog { apps }, store).listApps())
            compose.onNodeWithTag("launch_app_search").performTextClearance()
            compose.onNodeWithTag("launch_app_search").performTextInput("com.example.reader")
            compose.onNodeWithTag("launch_app_com.example.reader").assertIsOff()
            compose.onNodeWithTag("launch_apps_clear").performClick()
            assertEquals(emptySet<String>(), AppLaunchAllowlistStore(file).read())
            assertEquals(emptyList<RootPilotApp>(), AllowlistedAppCatalog(AppCatalog { apps }, store).listApps())
            compose.onNodeWithTag("launch_app_search").performTextClearance()
            compose.onNodeWithTag("launch_app_com.example.notes").assertIsOff()
            compose.onNodeWithText("已允许 0 个应用").assertExists()
        } finally {
            directory.deleteRecursively()
        }
    }
}
