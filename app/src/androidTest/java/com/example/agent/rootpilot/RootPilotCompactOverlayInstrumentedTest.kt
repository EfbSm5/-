package com.example.agent.rootpilot

import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.ui.RootPilotOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotCompactOverlayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun ordinaryActionStartsCompactAndExpandsWithoutConfirming() = withOverlay { fixture ->
        val action = RootPilotAction.Tap(500, 250, "确认测试目标位置")
        fixture.show(action)
        instrumentation.runOnMainSync {
            val panel = panel()
            val density = panel.resources.displayMetrics.density
            assertEquals((220 * density).toInt(), panel.width)
            assertEquals(View.GONE, scroll().visibility)
            val summary = descendants(panel).filterIsInstance<TextView>()
                .single { it.tag == "动作摘要" }
            assertEquals("点击 (500, 250)", summary.text.toString())
            assertEquals(1, summary.maxLines)
            assertEquals(null, summary.contentDescription)
            assertTrue(header().stateDescription.toString().contains("已收起"))
            assertTrue(summary.isShown)
            buttons().forEach {
                assertTrue(it.width >= (48 * density).toInt())
                assertTrue(it.height >= (48 * density).toInt())
            }
            assertTrue(confirm().isEnabled)
            header().performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            assertTrue(header().stateDescription.toString().contains("已展开"))
            assertTrue(details().text.contains(action.reason))
            assertEquals(Int.MAX_VALUE, details().maxLines)
            assertEquals(0, fixture.approved)
            header().performClick()
            assertEquals(View.GONE, scroll().visibility)
            confirm().performClick()
            assertEquals(1, fixture.approved)
            assertTrue(fixture.removedBeforeConfirm)
        }
    }

    @Test
    fun newSensitiveActionsResetCollapsedPreviewAndCannotConfirmWhileCollapsed() = withOverlay { fixture ->
        val title = (1..18).joinToString("\n") { "第${it}行" }
        val todo = RootPilotAction.CreateTodo(title, "2026-09-22T09:00:00+08:00", "预览测试")
        fixture.show(todo)
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            assertTrue(details().text.contains(title))
            assertTrue(scroll().canScrollVertically(1))
            scroll().scrollTo(0, details().height)
            assertTrue(scroll().scrollY > 0)
            header().performClick()
            assertFalse(confirm().isEnabled)
            confirm().performClick()
            assertEquals(0, fixture.approved)
        }
        val text = "PRIVATE_INPUT_SENTINEL\n第二行"
        fixture.show(RootPilotAction.Type(text, "PRIVATE_REASON_SENTINEL"), step = 1)
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            assertEquals(0, scroll().scrollY)
            assertTrue(confirm().isEnabled)
            assertTrue(details().text.contains("请在输入法面板核对完整文本"))
            assertFalse(descendants(panel()).filterIsInstance<TextView>()
                .any { it.text.contains("SENTINEL") })
            header().performClick()
            assertFalse(confirm().isEnabled)
            confirm().performClick()
            assertEquals(0, fixture.approved)
        }
        val question = (1..20).joinToString("\n") { "请核对第${it}项" }
        val ask = RootPilotAction.AskUser(question)
        fixture.show(ask, step = 2)
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            assertTrue(details().text.contains(question))
            assertEquals(Int.MAX_VALUE, details().maxLines)
            assertTrue(scroll().canScrollVertically(1))
            scroll().scrollTo(0, details().height)
            assertFalse(scroll().canScrollVertically(1))
            assertEquals("已处理，继续", confirm().text.toString())
            header().performClick()
            assertFalse(confirm().isEnabled)
        }
        // A new step must re-open the preview even if the action instance is reused.
        fixture.show(ask, step = 3)
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            assertEquals(0, scroll().scrollY)
            assertTrue(confirm().isEnabled)
            assertEquals(0, fixture.approved)
        }
        fixture.show(RootPilotAction.Key(com.example.agent.rootpilot.model.RootPilotKey.BACK, "返回"), step = 4)
        instrumentation.runOnMainSync {
            assertEquals(View.GONE, scroll().visibility)
            assertTrue(confirm().isEnabled)
        }
    }

    @Test
    fun thinkingKeepsStopAvailableAndSameActionRenderKeepsUserExpansion() = withOverlay { fixture ->
        fixture.show(null, status = RootPilotStatus.REQUESTING_MODEL)
        instrumentation.runOnMainSync {
            assertFalse(confirm().isEnabled)
            assertTrue(buttons().single { it.text == "停止" }.isEnabled)
        }
        val action = RootPilotAction.Tap(1, 2, "测试说明")
        fixture.show(action)
        instrumentation.runOnMainSync { header().performClick() }
        fixture.show(action)
        instrumentation.runOnMainSync {
            assertTrue(scroll().isShown)
            buttons().single { it.text == "停止" }.performClick()
            assertEquals(1, fixture.stopped)
            assertEquals(0, fixture.approved)
        }
    }

    private fun withOverlay(test: (Fixture) -> Unit) {
        assumeTrue(Settings.canDrawOverlays(context))
        val fixture = Fixture()
        try {
            instrumentation.runOnMainSync {
                fixture.overlay = RootPilotOverlay(context, {
                    fixture.removedBeforeConfirm = headers().none()
                    fixture.approved++
                }, { fixture.stopped++ })
            }
            test(fixture)
        } finally {
            instrumentation.runOnMainSync { fixture.hide() }
        }
    }

    private inner class Fixture {
        lateinit var overlay: RootPilotOverlay
        var approved = 0
        var stopped = 0
        var removedBeforeConfirm = false

        fun hide() {
            if (::overlay.isInitialized) overlay.hide()
        }

        fun show(action: RootPilotAction?, step: Int = 0, status: RootPilotStatus = RootPilotStatus.WAITING_CONFIRMATION) {
            instrumentation.runOnMainSync {
                overlay.render(RootPilotUiState(status = status, step = step, pendingAction = action))
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun headers(): Sequence<TextView> = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<TextView>()
        .filter { it.contentDescription == "RootPilot 悬浮窗，拖动移动，点击展开或收起" }

    private fun header(): TextView = headers().single()
    private fun panel(): LinearLayout = header().parent as LinearLayout
    private fun scroll(): ScrollView = descendants(panel()).filterIsInstance<ScrollView>().single()
    private fun details(): TextView = scroll().getChildAt(0) as TextView
    private fun buttons(): List<Button> = descendants(panel()).filterIsInstance<Button>().toList()
    private fun confirm(): Button = buttons().single { it.text != "停止" }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
