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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.agent.ui.theme.AgentTheme
import com.example.agent.rootpilot.ui.RootPilotScreen

class RootPilotActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)
    private var inputMethodEnabled by mutableStateOf(false)
    private val viewModel: RootPilotViewModel by viewModels {
        RootPilotViewModel.Factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                DisposableEffect(apiState.editing) {
                    if (apiState.editing) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
                RootPilotScreen(
                    onOpenLegacyAgent = {
                        startActivity(Intent(this@RootPilotActivity, com.example.agent.MainActivity::class.java))
                    },
                    state = state,
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

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        val ownId = ComponentName(this, RootPilotInputMethodService::class.java).flattenToShortString()
        inputMethodEnabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ownId }
        viewModel.recoverInputMethod()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}
