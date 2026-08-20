package com.example.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import com.example.agent.agent.planning.AgentPlanner
import com.example.agent.agent.planning.AgentPlannerViewModel
import com.example.agent.agent.planning.AgentExecutionEngine
import com.example.agent.agent.planning.AgentRunState
import com.example.agent.agent.planning.DemoAgentModelClient
import com.example.agent.agent.planning.FileTodoRepository
import com.example.agent.agent.planning.LiteRtLmAgentModelClient
import com.example.agent.agent.planning.OnDeviceAcceleration
import com.example.agent.agent.planning.OnDeviceModelStore
import com.example.agent.agent.planning.backends
import com.example.agent.agent.planning.RetryingAgentModelClient
import com.example.agent.ui.theme.AgentTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val onDeviceModelStore by lazy { OnDeviceModelStore(applicationContext) }
    private val modelClient by lazy {
        if (onDeviceModelStore.isInstalled()) {
            LiteRtLmAgentModelClient(
                context = applicationContext,
                modelFile = onDeviceModelStore.modelFile,
                backends = OnDeviceAcceleration.GPU_PREFERRED.backends(applicationContext),
            )
        } else {
            DemoAgentModelClient()
        }
    }

    private val plannerViewModel: AgentPlannerViewModel by viewModels {
        AgentPlannerViewModel.Factory(
            planner = AgentPlanner(
                modelClient = RetryingAgentModelClient(modelClient),
            ),
            executionEngine = AgentExecutionEngine(
                FileTodoRepository(File(applicationContext.filesDir, "agent_todos.json")),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentTheme {
                val uiState by plannerViewModel.uiState.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AgentScreen(
                        uiState = uiState,
                        modelLabel = if (onDeviceModelStore.isInstalled()) {
                            "端侧 LiteRT-LM 模型"
                        } else {
                            "本地 Demo 模型；模型文件未安装"
                        },
                        onSubmit = plannerViewModel::submit,
                        onAnswer = plannerViewModel::answerClarification,
                        onConfirmExecution = plannerViewModel::confirmExecution,
                        onRetry = plannerViewModel::retry,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun AgentScreen(
    uiState: AgentRunState,
    onSubmit: (String) -> Unit,
    onAnswer: (String) -> Unit,
    onConfirmExecution: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    modelLabel: String = "本地 Demo 模型",
) {
    var request by rememberSaveable { mutableStateOf("") }
    val isBusy = uiState is AgentRunState.Planning || uiState is AgentRunState.Executing
    val needsClarification = uiState is AgentRunState.NeedsClarification

    LaunchedEffect(needsClarification) {
        if (needsClarification) {
            request = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Agent Planner", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "先生成计划，再由后续 Tool 执行。当前使用 $modelLabel。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBusy,
            label = {
                Text(if (needsClarification) "补充信息" else "你想让助手完成什么？")
            },
            minLines = 3,
        )
        Button(
            onClick = {
                if (needsClarification) onAnswer(request) else onSubmit(request)
            },
            enabled = request.isNotBlank() && !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (needsClarification) "继续规划" else "生成计划")
        }

        when (val state = uiState) {
            AgentRunState.Idle -> Text("等待输入目标")
            is AgentRunState.Planning -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("正在生成计划…")
            }

            is AgentRunState.NeedsClarification -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("需要补充信息", style = MaterialTheme.typography.titleMedium)
                Text(state.question)
                Text("回答前不会执行任何 Tool。")
            }

            is AgentRunState.AwaitingConfirmation -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlanCard(state.plan)
                Text("计划已通过校验，确认后才会执行 Tool。")
                Button(onClick = onConfirmExecution) {
                    Text("确认执行")
                }
            }

            is AgentRunState.Executing -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text("正在执行第 ${state.actionIndex + 1} 个 Action…")
                PlanCard(state.plan)
            }

            is AgentRunState.Completed -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlanCard(state.plan)
                Text("执行完成：创建了 ${state.createdTodos.size} 个待办。")
            }

            is AgentRunState.Failure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                if (state.canRetry) {
                    TextButton(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: AgentPlan) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("计划目标", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(plan.goal)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            plan.actions.forEachIndexed { index, action ->
                Text("${index + 1}. ${action.describe()}")
            }
        }
    }
}

private fun AgentAction.describe(): String = when (this) {
    is CreateTodo -> if (dueAt == null) {
        "创建待办：$title"
    } else {
        "创建待办：$title，截止 $dueAt"
    }

    is OpenApp -> "打开应用：$packageName"
    is AskUser -> "询问用户：$question"
}

@Preview(showBackground = true)
@Composable
private fun AgentScreenPreview() {
    AgentTheme {
        AgentScreen(
            uiState = AgentRunState.Idle,
            modelLabel = "本地 Demo 模型",
            onSubmit = {},
            onAnswer = {},
            onConfirmExecution = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlanCardPreview() {
    AgentTheme {
        PlanCard(
            plan = AgentPlan(
                goal = "提醒我投递岗位",
                actions = listOf(AskUser("你希望几点提醒？")),
            ),
        )
    }
}
