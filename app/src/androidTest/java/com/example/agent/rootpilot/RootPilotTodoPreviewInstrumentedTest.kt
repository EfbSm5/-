package com.example.agent.rootpilot

import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.ui.RootPilotOverlay
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotTodoPreviewInstrumentedTest {
    @Test fun multilineTodoCanScrollToDeadlineWithoutApproving() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        var overlay: RootPilotOverlay? = null
        var approved = false
        val title = (1..18).joinToString("\n") { "第${it}行" }
        val deadline = "2026-09-22T09:00:00+08:00"
        try {
            instrumentation.runOnMainSync {
                overlay = RootPilotOverlay(context, { approved = true }, {})
                overlay.render(RootPilotUiState(
                    status = RootPilotStatus.WAITING_CONFIRMATION,
                    pendingAction = RootPilotAction.CreateTodo(title, deadline, "测试预览"),
                ))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val scroll = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap(::descendants).filterIsInstance<ScrollView>()
                    .single { it.contentDescription == "待办详情，可上下滚动查看完整内容" }
                val text = scroll.getChildAt(0) as TextView
                assertTrue(text.text.contains(title))
                assertTrue(text.text.contains(deadline))
                assertTrue(scroll.canScrollVertically(1))
                scroll.scrollTo(0, text.height)
                assertTrue(scroll.scrollY > 0)
                assertTrue(!scroll.canScrollVertically(1))
                assertTrue(!approved)
            }
        } finally {
            instrumentation.runOnMainSync { overlay?.hide() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
