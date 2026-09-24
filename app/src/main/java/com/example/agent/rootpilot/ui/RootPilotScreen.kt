package com.example.agent.rootpilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.material3.MaterialTheme
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.ApiConfigUiState
import com.example.agent.rootpilot.AppLaunchUiState
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.RootPilotStatus

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun RootPilotScreen(
    state: RootPilotUiState,
    apiState: ApiConfigUiState,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onSaveApiConfig: () -> Unit,
    onEditApiConfig: () -> Unit,
    onCancelApiConfigEdit: () -> Unit,
    onClearApiConfig: () -> Unit,
    onTestConnection: () -> Unit,
    onTaskChanged: (String) -> Unit,
    onTestRoot: () -> Unit,
    onCaptureScreen: () -> Unit,
    onSingleStep: () -> Unit,
    onAutoExecute: () -> Unit,
    onStop: () -> Unit,
    onConfirmAction: () -> Unit,
    onRecoverInterruptedRun: () -> Unit,
    onDiscardInterruptedRun: () -> Unit,
    onManualConfirmationChanged: (Boolean) -> Unit,
    onScreenUploadChanged: (Boolean) -> Unit,
    overlayAllowed: Boolean,
    inputMethodEnabled: Boolean,
    inputMessage: String?,
    onInputMethodSettings: () -> Unit,
    onOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLegacyAgent: () -> Unit = {},
    appLaunchState: AppLaunchUiState = AppLaunchUiState(),
    onRefreshLaunchApps: () -> Unit = {},
    onAppLaunchAllowedChanged: (String, Boolean) -> Unit = { _, _ -> },
    onClearLaunchApps: () -> Unit = {},
    chatContent: (@Composable () -> Unit)? = null,
    chatGenerating: Boolean = false,
    historyContent: (@Composable (onBack: () -> Unit) -> Unit)? = null,
) {
    var mode by rememberSaveable { mutableStateOf(ScreenMode.TASK) }
    var settingsOrigin by rememberSaveable { mutableStateOf(ScreenMode.TASK) }
    val showSettings = mode !in setOf(ScreenMode.TASK, ScreenMode.CHAT)
    var showLaunchApps by rememberSaveable { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    LaunchedEffect(mode) { detailScroll.scrollTo(0) }
    if (showLaunchApps) {
        AppLaunchPicker(
            state = appLaunchState,
            onAllowedChanged = onAppLaunchAllowedChanged,
            onClear = onClearLaunchApps,
            onRefresh = onRefreshLaunchApps,
            onDismiss = { showLaunchApps = false },
        )
    }
    val taskBusy = state.running || state.status in setOf(
        RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL,
        RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN,
        RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.STOPPING,
    )
    val busy = taskBusy || chatGenerating
    LaunchedEffect(taskBusy, chatGenerating, chatContent != null) {
        if (chatGenerating && !taskBusy && chatContent != null) mode = ScreenMode.CHAT
        else if (mode == ScreenMode.CHAT && (taskBusy || chatContent == null)) mode = ScreenMode.TASK
    }
    val stopping = state.status == RootPilotStatus.STOPPING
    val recoveryRequired = state.status == RootPilotStatus.RECOVERY_REQUIRED
    val apiControlsEnabled = !busy && !apiState.busy
    val apiReady = apiState.configured && !apiState.editing && !apiState.busy
    val keyboardVisible = WindowInsets.isImeVisible
    val returnFromSettings = {
        mode = if (taskBusy || chatContent == null) ScreenMode.TASK else settingsOrigin
    }
    BackHandler(enabled = (mode != ScreenMode.TASK || chatGenerating) && !showLaunchApps) {
        if (!chatGenerating) when (mode) {
            ScreenMode.HISTORY, ScreenMode.PERMISSIONS, ScreenMode.DEBUG -> mode = ScreenMode.SETTINGS
            ScreenMode.SETTINGS -> returnFromSettings()
            else -> mode = ScreenMode.TASK
        }
    }
    val openSettings = {
        settingsOrigin = if (mode == ScreenMode.CHAT) ScreenMode.CHAT else ScreenMode.TASK
        mode = ScreenMode.SETTINGS
    }

    RootPilotTheme {
        Scaffold(
            modifier = modifier.semantics { testTagsAsResourceId = true },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showSettings && mode != ScreenMode.HISTORY) {
                            ToolbarButton("返回", PilotIcon.BACK, true,
                                { if (mode == ScreenMode.SETTINGS) returnFromSettings() else mode = ScreenMode.SETTINGS },
                                Modifier.testTag("back_to_task"))
                        }
                        Text(when (mode) {
                            ScreenMode.SETTINGS -> "设置"
                            ScreenMode.PERMISSIONS -> "权限与输入"
                            ScreenMode.DEBUG -> "调试工具"
                            else -> "RootPilot"
                        },
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (!showSettings) {
                            ToolbarButton("设置", PilotIcon.SETTINGS, !chatGenerating,
                                openSettings, Modifier.testTag("open_settings"))
                        }
                    }
                    if (showSettings && taskBusy) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(when {
                                stopping -> "正在停止，请等待运行结束"
                                state.status == RootPilotStatus.WAITING_CONFIRMATION -> "请返回任务核对并确认动作"
                                else -> "任务正在运行"
                            }, modifier = Modifier.weight(1f))
                            Button(onClick = onStop, enabled = !stopping, modifier = Modifier.testTag("settings_stop")) { Text("停止") }
                        }
                    }
                }
            },
            bottomBar = {
                if (!showSettings && !keyboardVisible && chatContent != null) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("bottom_navigation"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        NavigationItem("任务", PilotIcon.TASK, mode == ScreenMode.TASK, !chatGenerating,
                            { mode = ScreenMode.TASK }, Modifier.weight(1f)
                                .testTag(if (mode == ScreenMode.TASK) "task_tab" else "back_to_task"))
                        NavigationItem("聊天", PilotIcon.CHAT, mode == ScreenMode.CHAT, !taskBusy,
                            { mode = ScreenMode.CHAT }, Modifier.weight(1f).testTag("open_chat"))
                    }
                }
            },
        ) { paddingValues ->
            if (mode == ScreenMode.CHAT && chatContent != null) {
                Box(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues).fillMaxSize()) { chatContent() }
            } else if (mode == ScreenMode.HISTORY && historyContent != null) {
                Box(Modifier.padding(paddingValues).fillMaxSize()) {
                    historyContent { mode = ScreenMode.SETTINGS }
                }
            } else {
                Column(
                    modifier = Modifier.padding(paddingValues)
                        .consumeWindowInsets(paddingValues).imePadding()
                        .verticalScroll(when (mode) {
                            ScreenMode.PERMISSIONS, ScreenMode.DEBUG -> detailScroll
                            ScreenMode.SETTINGS -> settingsScroll
                            else -> homeScroll
                        })
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (!showSettings) {
                        if (taskBusy) TaskOverview(state, stopping, onStop)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("想让手机做什么？", style = MaterialTheme.typography.headlineSmall)
                            Text("描述目标，重要操作由你确认。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Card(Modifier.fillMaxWidth().testTag("task_composer")) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                TextField(
                                    value = state.config.task, onValueChange = onTaskChanged,
                                    modifier = Modifier.fillMaxWidth().testTag("task_input"),
                                    label = "描述目标和完成条件", minLines = 3, maxLines = 5, enabled = !busy,
                                )
                                ToggleRow(
                                    label = "允许上传当前屏幕截图",
                                    checked = state.config.allowScreenUpload, enabled = !busy,
                                    onCheckedChange = onScreenUploadChanged,
                                )
                                Text(if (state.config.manualConfirmation) "执行方式：逐步确认" else "执行方式：自动点击/滑动",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = onAutoExecute, enabled = !busy && !recoveryRequired && apiReady,
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_task")) { Text("开始") }
                            }
                        }
                        if (!apiReady) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(when {
                                        apiState.editing && apiState.configured -> "API 配置尚未保存，请前往设置保存或取消编辑。"
                                        !apiState.configured -> "请先在设置中完成 API 配置。"
                                        else -> "API 配置正在处理，请稍候。"
                                    })
                                    Button(onClick = openSettings, enabled = !chatGenerating,
                                        modifier = Modifier.testTag("api_setup_hint")) {
                                        Text("前往设置")
                                    }
                                }
                            }
                        }
                        if (!taskBusy) TaskOverview(state, stopping, onStop)
                        TaskResultCard(state)
                        if (state.status == RootPilotStatus.WAITING_CONFIRMATION) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("待确认动作", style = MaterialTheme.typography.titleMedium)
                                    Text(state.pendingAction?.let {
                                        if (it is RootPilotAction.Type) it.overlayDetails() else it.describe()
                                    } ?: "等待动作信息", modifier = Modifier.testTag("pending_action"))
                                    Button(onClick = onConfirmAction, enabled = state.pendingAction != null,
                                        colors = ButtonDefaults.buttonColorsPrimary(),
                                        modifier = Modifier.fillMaxWidth().testTag("confirm_action")) {
                                        Text(if (state.pendingAction is RootPilotAction.AskUser) "我已按提示处理，继续" else "确认执行当前动作")
                                    }
                                }
                            }
                        }
                        if (recoveryRequired) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("上次任务中断", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "无法确认上一步 Root 动作是否已经生效，不会自动重放。" +
                                            "请确认当前屏幕后重新规划，或放弃上次任务。",
                                    )
                                    Button(onClick = onRecoverInterruptedRun, enabled = apiReady) {
                                        Text("从当前屏幕重新规划")
                                    }
                                    Button(onClick = onDiscardInterruptedRun) {
                                        Text("放弃上次任务")
                                    }
                                }
                            }
                        }
                        if (state.taskResultContent() == null && state.savedTodos.isEmpty()) {
                            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        RootPilotSettingsContent(
                            state = state, apiState = apiState, appLaunchState = appLaunchState,
                            busy = busy, recoveryRequired = recoveryRequired, apiReady = apiReady,
                            apiControlsEnabled = apiControlsEnabled, overlayAllowed = overlayAllowed,
                            inputMethodEnabled = inputMethodEnabled, inputMessage = inputMessage,
                            showDebug = mode == ScreenMode.DEBUG,
                            showPermissions = mode == ScreenMode.PERMISSIONS,
                            onToggleDebug = { mode = ScreenMode.DEBUG },
                            onOpenPermissions = { mode = ScreenMode.PERMISSIONS },
                            onOpenApps = { showLaunchApps = true; onRefreshLaunchApps() },
                            onApiKeyChanged = onApiKeyChanged, onBaseUrlChanged = onBaseUrlChanged,
                            onModelChanged = onModelChanged, onSaveApiConfig = onSaveApiConfig,
                            onEditApiConfig = onEditApiConfig, onCancelApiConfigEdit = onCancelApiConfigEdit,
                            onClearApiConfig = onClearApiConfig, onTestConnection = onTestConnection,
                            onOverlayPermission = onOverlayPermission, onInputMethodSettings = onInputMethodSettings,
                            onManualConfirmationChanged = onManualConfirmationChanged,
                            onTestRoot = onTestRoot, onCaptureScreen = onCaptureScreen,
                            onSingleStep = onSingleStep, onOpenLegacyAgent = onOpenLegacyAgent,
                        )
                        if (mode == ScreenMode.SETTINGS && historyContent != null) {
                            Button(onClick = { mode = ScreenMode.HISTORY },
                                modifier = Modifier.fillMaxWidth().testTag("open_history")) {
                                Text("任务历史与失败回顾")
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ScreenMode { TASK, CHAT, SETTINGS, HISTORY, PERMISSIONS, DEBUG }

@Composable
private fun NavigationItem(
    label: String,
    icon: PilotIcon,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier.clip(RoundedCornerShape(20.dp))
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
        .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
        .heightIn(min = 56.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PilotGlyph(icon, color)
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolbarButton(label: String, icon: PilotIcon, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
        minWidth = 48.dp, minHeight = 48.dp, insideMargin = PaddingValues(12.dp)) {
        PilotGlyph(icon, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
    }
}

@Composable
private fun TaskOverview(state: RootPilotUiState, stopping: Boolean, onStop: () -> Unit) {
    val active = state.running || state.status in setOf(RootPilotStatus.CAPTURING,
        RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.EXECUTING, RootPilotStatus.WAITING_SCREEN,
        RootPilotStatus.WAITING_CONFIRMATION, RootPilotStatus.STOPPING)
    Card(Modifier.fillMaxWidth().testTag("task_overview")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("状态：${state.status.displayName()}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("task_status"))
                Text(if (state.status == RootPilotStatus.IDLE) "准备好后，开始一个新任务"
                    else "当前步骤：${state.step + 1} / $MAX_STEPS",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) Button(onClick = onStop, enabled = !stopping,
                modifier = Modifier.testTag("stop_task")) { Text("停止") }
        }
    }
}

private enum class PilotIcon { TASK, CHAT, SETTINGS, BACK }

@Composable
private fun PilotGlyph(icon: PilotIcon, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), 1.8f * unit)
        when (icon) {
            PilotIcon.TASK -> {
                drawRoundRect(color, Offset(4 * unit, 3 * unit), Size(16 * unit, 18 * unit),
                    androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(1.8f * unit))
                line(8f, 9f, 16f, 9f); line(8f, 14f, 14f, 14f)
            }
            PilotIcon.CHAT -> {
                val path = Path().apply {
                    moveTo(4 * unit, 4 * unit); lineTo(20 * unit, 4 * unit)
                    lineTo(20 * unit, 17 * unit); lineTo(10 * unit, 17 * unit)
                    lineTo(4 * unit, 21 * unit); close()
                }
                drawPath(path, color, style = Stroke(1.8f * unit))
                line(8f, 9f, 16f, 9f); line(8f, 13f, 13f, 13f)
            }
            PilotIcon.SETTINGS -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                drawCircle(color, 2.5f * unit, Offset(9 * unit, 6 * unit))
                drawCircle(color, 2.5f * unit, Offset(15 * unit, 12 * unit))
                drawCircle(color, 2.5f * unit, Offset(9 * unit, 18 * unit))
            }
            PilotIcon.BACK -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.testTag("screen_upload"))
    }
}

private fun RootPilotStatus.displayName(): String = when (this) {
    RootPilotStatus.IDLE -> "空闲"
    RootPilotStatus.CAPTURING -> "截取屏幕"
    RootPilotStatus.REQUESTING_MODEL -> "请求模型"
    RootPilotStatus.WAITING_CONFIRMATION -> "等待人工确认"
    RootPilotStatus.EXECUTING -> "执行动作"
    RootPilotStatus.WAITING_SCREEN -> "等待页面稳定"
    RootPilotStatus.COMPLETED -> "已完成"
    RootPilotStatus.FAILED -> "失败"
    RootPilotStatus.STOPPING -> "正在停止"
    RootPilotStatus.STOPPED -> "已停止"
    RootPilotStatus.RECOVERY_REQUIRED -> "需要恢复确认"
}

internal fun RootPilotAction.describe(): String = when (this) {
    is RootPilotAction.CreateTodo -> "创建本地待办\n标题：$title\n截止时间：${dueAt ?: "无"}\n$reason"
    is RootPilotAction.Tap -> "tap($x,$y)：$reason"
    is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)：$reason"
    is RootPilotAction.OpenApp -> "open_app($packageName)：$reason"
    is RootPilotAction.Type -> "type(${text.length} chars)：$reason"
    is RootPilotAction.Key -> "key($key)：$reason"
    is RootPilotAction.Wait -> "wait($durationMillis)：$reason"
    is RootPilotAction.AskUser -> "ask_user：$message"
    is RootPilotAction.Finish -> "finish($success)：$message"
}

private const val MAX_STEPS = 20
