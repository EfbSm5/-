package com.example.agent.rootpilot

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ComponentName
import android.view.inputmethod.InputMethodManager
import com.example.agent.rootpilot.input.RootPilotInputMethodService
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.agent.ui.theme.AgentTheme
import com.example.agent.rootpilot.ui.RootPilotScreen
import com.example.agent.rootpilot.ui.ChatScreen
import com.example.agent.rootpilot.ui.RunHistoryScreen
import com.example.agent.rootpilot.ui.FileAgentPanel
import com.example.agent.rootpilot.ui.WorkspaceBrowserDialog
import com.example.agent.rootpilot.ui.FileWriteConfirmation

class RootPilotActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)
    private var inputMethodEnabled by mutableStateOf(false)
    private val viewModel: RootPilotViewModel by viewModels {
        RootPilotViewModel.Factory(applicationContext)
    }
    private var exportingBackupId: String? = null
    private val chooseWorkspace = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            data.data?.let { viewModel.selectFileWorkspace(it, data.flags) }
        }
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val id = exportingBackupId
        exportingBackupId = null
        if (uri != null && id != null) viewModel.exportFileBackup(id, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportingBackupId = savedInstanceState?.getString("file_backup_export_id")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE,
            )
        }
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                val state by viewModel.uiState.collectAsState()
                val apiState by viewModel.apiState.collectAsState()
                val inputMessage by viewModel.inputMessage.collectAsState()
                val appLaunchState by viewModel.appLaunchState.collectAsState()
                val chatState by viewModel.chatState.collectAsState()
                val fileState by viewModel.fileAgentState.collectAsState()
                val browserState by viewModel.workspaceBrowserState.collectAsState()
                val fileWorkspaceBusy by viewModel.fileWorkspaceBusy.collectAsState()
                val historyState by viewModel.historyState.collectAsState()
                DisposableEffect(apiState.editing) {
                    if (apiState.editing) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                RootPilotScreen(
                    historyContent = { onBack ->
                        RunHistoryScreen(historyState, viewModel::clearHistory, onBack)
                    },
                    chatGenerating = chatState.generating,
                    chatContent = {
                        ChatScreen(
                            state = chatState,
                            configured = state.apiConfigured && !apiState.busy && !apiState.editing && !fileWorkspaceBusy,
                            onDraftChange = viewModel::updateChatDraft,
                            onEffortChange = viewModel::setChatEffort,
                            onSend = viewModel::sendChat,
                            onStop = viewModel::stopChat,
                            onNewConversation = viewModel::newChat,
                            fileControls = {
                                FileAgentPanel(fileState.copy(busy = fileState.busy || fileWorkspaceBusy), chatState.generating,
                                    onChooseDirectory = {
                                        chooseWorkspace.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                                        ))
                                    },
                                    onClearDirectory = viewModel::clearFileWorkspace,
                                    onBrowse = viewModel::openWorkspaceBrowser,
                                    onEnabledChange = viewModel::setFileAgentEnabled,
                                    onExportBackup = { id ->
                                        exportingBackupId = id
                                        exportBackup.launch("rootpilot-original.txt")
                                    },
                                )
                            },
                        )
                        FileWriteConfirmation(fileState, viewModel::decideFileWrite)
                        WorkspaceBrowserDialog(browserState, viewModel::selectWorkspaceEntry,
                            viewModel::workspaceBrowserBack, viewModel::refreshWorkspaceBrowser, viewModel::closeWorkspaceBrowser)
                    },
                    onOpenLegacyAgent = {
                        startActivity(Intent(this@RootPilotActivity, com.example.agent.MainActivity::class.java))
                    },
                    state = state,
                    appLaunchState = appLaunchState,
                    onRefreshLaunchApps = viewModel::refreshLaunchApps,
                    onAppLaunchAllowedChanged = viewModel::setAppLaunchAllowed,
                    onClearLaunchApps = viewModel::clearAppLaunchSelection,
                    apiState = apiState,
                    onApiKeyChanged = viewModel::updateApiKey,
                    onBaseUrlChanged = viewModel::updateBaseUrl,
                    onModelChanged = viewModel::updateModel,
                    onSaveApiConfig = viewModel::saveApiConfig,
                    onEditApiConfig = viewModel::editApiConfig,
                    onCancelApiConfigEdit = viewModel::cancelApiConfigEdit,
                    onClearApiConfig = viewModel::clearApiConfig,
                    onTestConnection = viewModel::testConnection,
                    onTaskChanged = viewModel::updateTask,
                    onTestRoot = viewModel::testRoot,
                    onCaptureScreen = viewModel::captureScreen,
                    onSingleStep = viewModel::singleStep,
                    onAutoExecute = viewModel::autoExecute,
                    onStop = viewModel::stop,
                    onConfirmAction = viewModel::confirmAction,
                    onRecoverInterruptedRun = viewModel::recoverInterruptedRun,
                    onDiscardInterruptedRun = viewModel::discardInterruptedRun,
                    onManualConfirmationChanged = viewModel::setManualConfirmation,
                    onScreenUploadChanged = viewModel::setAllowScreenUpload,
                    overlayAllowed = overlayAllowed,
                    inputMethodEnabled = inputMethodEnabled,
                    inputMessage = inputMessage,
                    onInputMethodSettings = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onOverlayPermission = {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        )
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exportingBackupId?.let { outState.putString("file_backup_export_id", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        val ownId = ComponentName(this, RootPilotInputMethodService::class.java).flattenToShortString()
        inputMethodEnabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ownId }
        viewModel.recoverInputMethod()
        viewModel.refreshLaunchApps()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}
