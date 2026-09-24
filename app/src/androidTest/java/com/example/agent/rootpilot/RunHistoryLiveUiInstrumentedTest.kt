package com.example.agent.rootpilot

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only navigation of the installed app; never clear real history or edit credentials. */
@RunWith(AndroidJUnit4::class)
class RunHistoryLiveUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realHistoryEntryAndReturn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("historyLiveUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RootPilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as RootPilotActivity
        try {
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("open_history").performScrollTo().performClick()
            compose.onNodeWithTag("history_list").assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(activity.cacheDir, "rootpilot-history-acceptance.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithTag("history_back").performClick()
            compose.onNodeWithTag("open_history").assertIsDisplayed()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithTag("task_input").assertIsDisplayed()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
