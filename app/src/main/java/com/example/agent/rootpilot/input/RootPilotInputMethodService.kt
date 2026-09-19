package com.example.agent.rootpilot.input

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.RootExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RootPilotInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inputViewJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // The local approval preview must not expose input text in screen captures.
        window.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val preview = TextView(context).apply { textSize = 18f; setPadding(16, 8, 16, 8) }
        addView(ScrollView(context).apply { addView(preview) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt()))
        val confirm = Button(context).apply {
            text = "确认输入到当前输入框"
            setOnClickListener { RootPilotService.send(context, RootPilotService.ACTION_CONFIRM) }
        }
        addView(confirm)
        addView(Button(context).apply {
            text = "停止 / 恢复常用输入法"
            setOnClickListener { RootPilotService.send(context, RootPilotService.ACTION_STOP) }
        })
        addView(Button(context).apply {
            text = "选择其他输入法"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })
        inputViewJob?.cancel()
        inputViewJob = scope.launch {
            RootPilotService.uiState.collect { state ->
                val action = state.pendingAction as? RootPilotAction.Type
                confirm.isEnabled = state.status == RootPilotStatus.WAITING_CONFIRMATION && action != null
                preview.text = if (confirm.isEnabled) "待输入文本（仅本地预览）：\n${action!!.text}" else ""
                confirm.text = if (confirm.isEnabled) "确认输入 ${action!!.text.length} 字符：${action.reason}" else "等待输入动作"
            }
        }
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val connection = currentInputConnection
        InputConnectionBridge.editor.value = if (attribute != null && connection != null) {
            InputConnectionBridge.Editor(this, attribute, connection)
        } else null
    }

    override fun onFinishInput() {
        clearConnection()
        super.onFinishInput()
    }

    override fun onDestroy() {
        scope.cancel()
        clearConnection()
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    private fun clearConnection() {
        if (InputConnectionBridge.editor.value?.owner === this) InputConnectionBridge.editor.value = null
    }
}

// The system binds the IME; text never travels through an exported receiver or shell command.
internal object InputConnectionBridge {
    data class Editor(
        val owner: RootPilotInputMethodService,
        val info: EditorInfo,
        val connection: InputConnection,
    )

    val editor = MutableStateFlow<Editor?>(null)

    suspend fun commit(text: String, ownPackage: String, confirm: suspend (String) -> Boolean): RootExecutionResult {
        if (!InputText.isValid(text)) return RootExecutionResult.Failure("输入文本不合法")
        // Switching an IME is asynchronous. Wait for onStartInput, not a fixed sleep.
        val ready = withTimeoutOrNull(5_000) { editor.filterNotNull().first() }
            ?: return RootExecutionResult.Failure("未获得输入框连接，请先聚焦目标输入框")
        val allowed = withContext(Dispatchers.Main.immediate) {
            editor.value === ready && ready.owner.currentInputConnection === ready.connection &&
                ready.info.packageName != ownPackage && ready.info.inputType != InputType.TYPE_NULL &&
                !isPassword(ready.info.inputType)
        }
        if (!allowed) return RootExecutionResult.Failure("不能在 RootPilot、密码框或非文本目标中自动输入")
        // Approval refers to this exact connection, not whichever editor appears afterward.
        if (!confirm(ready.info.packageName)) return RootExecutionResult.Failure("用户取消输入")
        return withContext(Dispatchers.Main.immediate) {
            if (editor.value !== ready || ready.owner.currentInputConnection !== ready.connection) {
                return@withContext RootExecutionResult.Failure("输入焦点已变化，未输入文本")
            }
            if (ready.info.packageName == ownPackage || ready.info.inputType == InputType.TYPE_NULL ||
                isPassword(ready.info.inputType)
            ) return@withContext RootExecutionResult.Failure("不能在 RootPilot、密码框或非文本目标中自动输入")
            if (ready.connection.commitText(text, 1)) {
                RootExecutionResult.Success("已提交文本，仍需截图确认实际内容")
            } else {
                RootExecutionResult.Failure("输入框未接受文本，未自动重试")
            }
        }
    }

    internal fun isPassword(inputType: Int): Boolean {
        val kind = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return kind == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            kind == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            kind == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            kind == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
