package com.example.agent.rootpilot.ui

import android.content.Context
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration

internal fun createMarkdownRenderer(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { _, _ -> }
        }
    })
    .build()

@Composable
fun StreamingMarkdown(content: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember(context) { createMarkdownRenderer(context) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true); autoLinkMask = 0 } },
        update = { view ->
            view.setTextColor(color)
            if (view.tag != content) {
                renderer.setMarkdown(view, content)
                view.tag = content
            }
            // Core-only rendering has no remote image loader or HTML plugin. Links never launch intents.
            view.linksClickable = false
            view.movementMethod = null
        },
    )
}
