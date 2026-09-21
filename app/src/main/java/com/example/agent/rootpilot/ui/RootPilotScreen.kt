package com.example.agent.rootpilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.example.agent.rootpilot.ApiConfigUiState
import com.example.agent.rootpilot.AppLaunchUiState
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.RootPilotStatus

@OptIn(ExperimentalComposeUiApi::class)
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
) {
    var mode by rememberSaveable { mutableStateOf(ScreenMode.TASK) }
    val showSettings = mode == ScreenMode.SETTINGS
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showLaunchApps by rememberSaveable { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    BackHandler(enabled = (mode != ScreenMode.TASK || chatGenerating) && !showLaunchApps) {
        if (!chatGenerating) mode = ScreenMode.TASK
    }
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

    RootPilotTheme {
        Scaffold(
            modifier = modifier.semantics { testTagsAsResourceId = true },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("RootPilot", style = MaterialTheme.typography.titleLarge)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NavigationButton(
                            label = "任务",
                            selected = mode == ScreenMode.TASK, enabled = !chatGenerating,
                            onClick = { mode = ScreenMode.TASK },
                            modifier = Modifier.weight(1f).testTag(if (mode != ScreenMode.TASK) "back_to_task" else "task_tab"),
                        )
                        if (chatContent != null) {
                            NavigationButton("聊天", mode == ScreenMode.CHAT, !taskBusy,
                                { mode = ScreenMode.CHAT }, Modifier.weight(1f).testTag("open_chat"))
                        }
                        NavigationButton("设置", showSettings, !chatGenerating,
                            { mode = ScreenMode.SETTINGS }, Modifier.weight(1f).testTag("open_settings"))
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
        ) { paddingValues ->
            if (mode == ScreenMode.CHAT && chatContent != null) {
                Box(Modifier.padding(paddingValues).fillMaxSize()) { chatContent() }
            } else {
                Column(
                    modifier = Modifier.padding(paddingValues)
                        .verticalScroll(if (showSettings) settingsScroll else homeScroll)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!showSettings) {
                        TextField(
                            value = state.config.task, onValueChange = onTaskChanged,
                            modifier = Modifier.fillMaxWidth().testTag("task_input"),
                            label = "自然语言任务", minLines = 3, maxLines = 5, enabled = !busy,
                        )
                        ToggleRow(
                            label = "允许上传当前屏幕截图",
                            checked = state.config.allowScreenUpload, enabled = !busy,
                            onCheckedChange = onScreenUploadChanged,
                        )
                        if (!apiReady) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(when {
                                        apiState.editing && apiState.configured -> "API 配置尚未保存，请前往设置保存或取消编辑。"
                                        !apiState.configured -> "请先在设置中完成 API 配置。"
                                        else -> "API 配置正在处理，请稍候。"
                                    })
                                    Button(onClick = { mode = ScreenMode.SETTINGS }, enabled = !chatGenerating,
                                        modifier = Modifier.testTag("api_setup_hint")) {
                                        Text("前往设置")
                                    }
                                }
                            }
                        }
                        Text(if (state.config.manualConfirmation) "执行方式：逐步确认" else "执行方式：自动点击/滑动",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onAutoExecute, enabled = !busy && !recoveryRequired && apiReady,
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f).testTag("start_task")) { Text("开始") }
                            if (taskBusy) Button(onClick = onStop, enabled = !stopping, modifier = Modifier.testTag("stop_task")) { Text("停止") }
                        }
                        Text("状态：${state.status.displayName()}", modifier = Modifier.testTag("task_status"))
                        Text("当前步骤：${state.step + 1} / $MAX_STEPS")
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
                            showDebug = showDebug, onToggleDebug = { showDebug = !showDebug },
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
                    }
                }
            }
        }
    }
}

private enum class ScreenMode { TASK, CHAT, SETTINGS }

@Composable
private fun NavigationButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.semantics { this.selected = selected },
        minWidth = 0.dp, minHeight = 48.dp,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
