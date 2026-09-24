package com.example.agent.rootpilot

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.RootPilotStatus
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: only empty task/chat screens are captured; no requests or actions are sent. */
@RunWith(AndroidJUnit4::class)
class RootPilotHomeLiveUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun homeNavigationAndKeyboard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("homeLiveUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, RootPilotActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RootPilotActivity
        try {
            lateinit var model: RootPilotViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(activity, RootPilotViewModel.Factory(activity.applicationContext))
                    .get(RootPilotViewModel::class.java)
            }
            compose.waitUntil(10_000) { !model.apiState.value.busy }
            assumeTrue(model.apiState.value.configured && !model.apiState.value.editing)
            assumeTrue(model.uiState.value.status == RootPilotStatus.IDLE && model.uiState.value.config.task.isBlank())
            assumeTrue(model.chatState.value.messages.isEmpty() && model.chatState.value.draft.isBlank())
            fun capture(name: String) {
                compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    File(activity.cacheDir, "home-ui-$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
            fun imeVisible(): Boolean {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                return visible
            }
            fun hideIme() {
                instrumentation.runOnMainSync {
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    activity.currentFocus?.clearFocus()
                }
                compose.waitUntil(5000) { !imeVisible() }
                compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
            }
            compose.onNodeWithTag("task_composer").assertIsDisplayed()
            compose.onNodeWithTag("bottom_navigation").assertIsDisplayed()
            capture("task")
            compose.onNodeWithTag("task_input").performClick()
            compose.waitUntil(5000) { imeVisible() }
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            hideIme()
            compose.onNodeWithTag("open_chat").performClick()
            compose.onNodeWithTag("chat_draft").assertIsDisplayed()
            capture("chat")
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithTag("chat_draft").assertIsDisplayed()
            compose.onNodeWithTag("chat_draft").performClick()
            compose.waitUntil(5000) { imeVisible() }
            compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
            compose.onNodeWithTag("chat_send").assertIsDisplayed()
            capture("chat-keyboard")
            hideIme()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithTag("task_composer").assertIsDisplayed()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
