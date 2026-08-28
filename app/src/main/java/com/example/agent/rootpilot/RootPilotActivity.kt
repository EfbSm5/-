package com.example.agent.rootpilot

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
        RootPilotViewModel.Factory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onManualConfirmationChanged = viewModel::setManualConfirmation,
                    onScreenUploadChanged = viewModel::setAllowScreenUpload,
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            viewModel.stop()
        }
        super.onDestroy()
    }
}
