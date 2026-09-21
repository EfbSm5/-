package com.example.agent.rootpilot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
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
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.rgb(28, 33, 43))
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(8).toFloat()
    }
    private val header = TextView(windowContext).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
        contentDescription = "RootPilot 悬浮窗，拖动移动，点击展开或收起"
    }
    private val detail = TextView(windowContext).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        maxLines = 6
    }
    private val detailScroll = ScrollView(windowContext).apply {
        addView(detail)
    }
    private val buttons = LinearLayout(windowContext)
    private val confirm = Button(windowContext).apply {
        text = "确认"
        setOnClickListener {
            // Remove the input window before allowing the pending device action to resume.
            hide()
            onConfirm()
        }
    }
    private val stop = Button(windowContext).apply {
        text = "停止"
        setOnClickListener { hide(); onStop() }
    }
    private val params = WindowManager.LayoutParams(
        dp(260), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = dp(8)
        y = dp(120)
        title = "RootPilotOverlay"
    }
    private var attached = false
    private var collapsed = false
    private var state = RootPilotUiState()

    init {
        panel.addView(header)
        panel.addView(detailScroll)
        buttons.addView(confirm, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(buttons)
        header.setOnClickListener {
            collapsed = !collapsed
            render(state)
        }
        enableDragging()
    }

    fun render(value: RootPilotUiState) {
        val actionChanged = value.pendingAction !== state.pendingAction
        state = value
        if (!value.status.showsOverlay() || !Settings.canDrawOverlays(windowContext)) {
            hide()
            return
        }
        val awaiting = value.status == RootPilotStatus.WAITING_CONFIRMATION
        header.text = "RootPilot · 第 ${value.step + 1} 步 · ${if (awaiting) "待确认" else "思考中"} ${if (collapsed) "＋" else "－"}"
        detail.text = if (awaiting) value.pendingAction?.describe().orEmpty() else "正在分析截图…"
        val todo = value.pendingAction is RootPilotAction.CreateTodo
        detail.maxLines = if (todo) Int.MAX_VALUE else 6
        detailScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (todo) dp(128) else LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        detailScroll.contentDescription = if (todo) "待办详情，可上下滚动查看完整内容" else null
        if (actionChanged) detailScroll.scrollTo(0, 0)
        detailScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        buttons.visibility = if (collapsed) View.GONE else View.VISIBLE
        confirm.isEnabled = awaiting && value.pendingAction != null
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
