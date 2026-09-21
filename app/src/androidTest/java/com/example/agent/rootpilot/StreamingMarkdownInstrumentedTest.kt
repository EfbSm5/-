package com.example.agent.rootpilot

import android.content.ContextWrapper
import android.content.Intent
import android.text.Spanned
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.ui.createMarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingMarkdownInstrumentedTest {
    @Test
    fun coreFormatsBlocksAndStreamingUpdatesWithoutOpeningLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var launches = 0
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun startActivity(intent: Intent) { launches++ }
            }
            val renderer = createMarkdownRenderer(context)
            val view = TextView(context)
            renderer.setMarkdown(view, "# 标题\n\n- 列表\n\n> 引用\n\n```kotlin\nval x = 1")
            renderer.setMarkdown(view, "# 标题\n\n- 列表\n\n> 引用\n\n```kotlin\nval x = 12\n```\n\n[链接](https://example.invalid)")
            val rendered = view.text as Spanned
            assertTrue(rendered.contains("val x = 12"))
            val spans = rendered.getSpans(0, rendered.length, Any::class.java).map { it.javaClass.simpleName }
            listOf("HeadingSpan", "BulletListItemSpan", "BlockQuoteSpan", "CodeBlockSpan").forEach { expected ->
                assertTrue("Missing $expected in $spans", expected in spans)
            }
            renderer.configuration().linkResolver().resolve(view, "https://example.invalid")
            assertEquals(0, launches)
        }
    }
}
