package com.example.agent.rootpilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import kotlin.math.abs

internal fun RootPilotStatus.showsOverlay(): Boolean =
    this == RootPilotStatus.REQUESTING_MODEL || this == RootPilotStatus.WAITING_CONFIRMATION

internal fun RootPilotAction.requiresFullOverlayPreview(): Boolean =
    this is RootPilotAction.CreateTodo || this is RootPilotAction.Type || this is RootPilotAction.AskUser

internal fun RootPilotAction.overlayDetails(): String = when (this) {
    is RootPilotAction.Type -> "输入文本（${text.length} 字符）\n请在输入法面板核对完整文本"
    else -> describe()
}

private fun RootPilotAction.overlaySummary(): String = when (this) {
    is RootPilotAction.Tap -> "点击 ($x, $y)"
    is RootPilotAction.Swipe -> "滑动 ($x1, $y1) → ($x2, $y2)"
    is RootPilotAction.OpenApp -> "打开 $packageName"
    is RootPilotAction.Type -> "输入 ${text.length} 字符 · 展开查看"
    is RootPilotAction.Key -> "按键 $key"
    is RootPilotAction.Wait -> "等待 ${durationMillis}ms"
    is RootPilotAction.CreateTodo -> "创建待办 · 展开查看"
    is RootPilotAction.AskUser -> "需要接管 · 展开查看"
    is RootPilotAction.Finish -> "任务结束"
}

/** Owned by the service; all window operations run on the main thread. */
internal class RootPilotOverlay(
    context: Context,
    private val onConfirm: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val windowContext = context.createWindowContext(
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null,
    )
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val panel = LinearLayout(windowContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply {
            setColor(Color.rgb(28, 33, 43))
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(8).toFloat()
    }
    private val header = TextView(windowContext).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "RootPilot 悬浮窗，拖动移动，点击展开或收起"
    }
    private val summary = TextView(windowContext).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        tag = "动作摘要"
    }
    private val detail = TextView(windowContext).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        maxLines = Int.MAX_VALUE
    }
    private val detailScroll = ScrollView(windowContext).apply {
        addView(detail)
    }
    private val markdown by lazy { createMarkdownRenderer(windowContext) }
    private var renderedDetail: String? = null
    private val buttons = LinearLayout(windowContext)
    private val confirm = Button(windowContext).apply {
        compactStyle(Color.rgb(51, 91, 145))
        text = "确认"
        setOnClickListener {
            if (!isEnabled) return@setOnClickListener
            // Remove the input window before allowing the pending device action to resume.
            hide()
            onConfirm()
        }
    }
    private val stop = Button(windowContext).apply {
        compactStyle(Color.rgb(68, 73, 83))
        text = "停止"
        setOnClickListener { hide(); onStop() }
    }
    private val params = WindowManager.LayoutParams(
        dp(220), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = dp(8)
        y = dp(120)
        title = "RootPilotOverlay"
    }
    private var attached = false
    private var expanded = false
    private var state = RootPilotUiState()

    init {
        panel.addView(header)
        panel.addView(summary)
        panel.addView(detailScroll)
        buttons.addView(confirm, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(buttons)
        header.setOnClickListener {
            expanded = !expanded
            render(state)
        }
        enableDragging()
    }

    fun render(value: RootPilotUiState) {
        val actionChanged = value.pendingAction !== state.pendingAction || value.step != state.step ||
            value.status != state.status
        val fullPreview = value.pendingAction?.requiresFullOverlayPreview() == true
        if (actionChanged) expanded = fullPreview && value.status == RootPilotStatus.WAITING_CONFIRMATION
        state = value
        if (!value.status.showsOverlay() || !Settings.canDrawOverlays(windowContext)) {
            hide()
            return
        }
        val awaiting = value.status == RootPilotStatus.WAITING_CONFIRMATION
        header.text = "第 ${value.step + 1} 步 · ${if (awaiting) "待确认" else "思考中"} ${if (expanded) "－" else "＋"}"
        header.stateDescription = "第 ${value.step + 1} 步，${if (awaiting) "待确认" else "思考中"}，详情${if (expanded) "已展开" else "已收起"}"
        summary.text = if (awaiting) value.pendingAction?.overlaySummary().orEmpty()
            else if (value.modelStream.content.isNotEmpty()) "正在生成动作 · 展开预览"
            else if (value.modelStream.reasoning.isNotEmpty()) "正在思考 · 展开预览"
            else "正在分析截图…"
        summary.visibility = if (expanded) View.GONE else View.VISIBLE
        // Raw task output can contain screen text. Show only after explicit expansion,
        // never make it an executable action or substitute it for confirmation details.
        val detailText = if (!expanded) "" else if (awaiting) value.pendingAction?.overlayDetails().orEmpty()
            else modelPreviewText(value.modelStream)
        if (actionChanged || renderedDetail != detailText) {
            if (!awaiting && expanded) markdown.setMarkdown(detail, detailText) else detail.text = detailText
            detail.linksClickable = false
            detail.movementMethod = null
            renderedDetail = detailText
        }
        val todo = value.pendingAction is RootPilotAction.CreateTodo
        detailScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(128),
        )
        detailScroll.contentDescription = if (todo) "待办详情，可上下滚动查看完整内容" else "动作详情，可上下滚动查看完整内容"
        if (actionChanged) detailScroll.scrollTo(0, 0)
        detailScroll.visibility = if (expanded) View.VISIBLE else View.GONE
        confirm.isEnabled = awaiting && value.pendingAction != null && (!fullPreview || expanded)
        confirm.alpha = if (confirm.isEnabled) 1f else 0.45f
        confirm.text = if (value.pendingAction is RootPilotAction.AskUser) "已处理，继续" else "确认"
        try {
            if (!attached) {
                manager.addView(panel, params)
                attached = true
            } else {
                manager.updateViewLayout(panel, params)
            }
        } catch (_: SecurityException) {
            // Permission may be revoked while a run is active; the activity remains usable.
            hide()
        } catch (_: WindowManager.BadTokenException) {
            hide()
        }
    }

    private fun Button.compactStyle(color: Int) {
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        setPadding(dp(4), 0, dp(4), 0)
        background = InsetDrawable(GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }, dp(2), dp(8), dp(2), dp(8))
    }

    fun hide() {
        if (attached) {
            manager.removeViewImmediate(panel)
            attached = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(windowContext).scaledTouchSlop
        header.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    dragging = dragging || abs(dx) > slop || abs(dy) > slop
                    if (dragging && attached) {
                        val bounds = manager.currentWindowMetrics.bounds
                        params.x = (startX + dx.toInt()).coerceIn(0, (bounds.width() - panel.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (bounds.height() - panel.height).coerceAtLeast(0))
                        manager.updateViewLayout(panel, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * windowContext.resources.displayMetrics.density).toInt()
}

internal fun modelPreviewText(stream: com.example.agent.rootpilot.deepseek.ModelStreamSnapshot): String = buildString {
    append("模型输出预览（未执行）\n\n")
    if (stream.reasoning.isNotBlank()) {
        append("**思考**\n\n")
        append(stream.reasoning.takeLast(OVERLAY_PREVIEW_CHARS))
        append("\n\n")
    }
    if (stream.content.isNotBlank()) {
        append("**动作草稿**\n\n")
        // Indented code cannot terminate a Markdown fence supplied by the model.
        append(stream.content.takeLast(OVERLAY_PREVIEW_CHARS).lineSequence().joinToString("\n") { "    $it" })
    } else if (stream.reasoning.isBlank()) append("等待模型输出…")
}

private const val OVERLAY_PREVIEW_CHARS = 4_000
