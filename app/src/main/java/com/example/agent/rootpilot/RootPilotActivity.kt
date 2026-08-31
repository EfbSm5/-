package com.example.agent.rootpilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.example.agent.ui.theme.AgentTheme
import com.example.agent.rootpilot.ui.RootPilotScreen

class RootPilotActivity : ComponentActivity() {
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
                RootPilotScreen(
                    state = state,
                    onApiKeyChanged = viewModel::updateApiKey,
                    onBaseUrlChanged = viewModel::updateBaseUrl,
                    onModelChanged = viewModel::updateModel,
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
                )
        }
    }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}
