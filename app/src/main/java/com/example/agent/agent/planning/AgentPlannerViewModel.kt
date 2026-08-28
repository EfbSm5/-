package com.example.agent.agent.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.agent.model.AgentPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentPlannerViewModel(
    private val planner: AgentPlanner,
    private val executionEngine: AgentExecutionEngine = AgentExecutionEngine(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow<AgentRunState>(AgentRunState.RecoveryScanning)
    val uiState: StateFlow<AgentRunState> = _uiState.asStateFlow()

    private val stateLock = Any()
    private var planningJob: Job? = null
    private var lastRequest: String? = null
    private var requestGeneration = 0L
    private var pendingRequest: String? = null

    init {
        loadRecoverableExecutions()
    }

    fun submit(userRequest: String) {
        val normalizedRequest = userRequest.trim()
        val generation: Long
        synchronized(stateLock) {
            if (_uiState.value is AgentRunState.RecoveryScanning) {
                if (normalizedRequest.isEmpty()) {
                    pendingRequest = null
                    _uiState.value = AgentRunState.Failure(
                        message = "请输入你想让助手完成的目标",
                        canRetry = false,
                    )
                } else {
                    pendingRequest = normalizedRequest
                }
                return
            }
            if (_uiState.value is AgentRunState.RecoveryRequired) return
            planningJob?.cancel()
            generation = ++requestGeneration
            if (normalizedRequest.isEmpty()) {
                lastRequest = null
                _uiState.value = AgentRunState.Failure(
                    message = "请输入你想让助手完成的目标",
                    canRetry = false,
                )
                return
            }

            lastRequest = normalizedRequest
            _uiState.value = AgentRunState.Planning(normalizedRequest)
            planningJob = viewModelScope.launch(dispatcher) {
                val result = try {
                    planner.buildPlan(normalizedRequest)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                // A cancelled model call may still complete; prevent its result from replacing a newer request.
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (generation == requestGeneration) {
                        _uiState.value = result?.toRunState(normalizedRequest) ?: AgentRunState.Failure(
                            message = "助手暂时不可用，请稍后重试",
                            canRetry = true,
                        )
                    }
                }
            }
        }
    }

    fun answerClarification(answer: String) {
        val currentState = synchronized(stateLock) {
            _uiState.value as? AgentRunState.NeedsClarification
        } ?: return
        val normalizedAnswer = answer.trim()
        if (normalizedAnswer.isEmpty()) {
            synchronized(stateLock) {
                _uiState.value = AgentRunState.Failure(
                    message = "请先回答助手的问题",
                    canRetry = false,
                )
            }
            return
        }

        submit(
            """
            ${currentState.request}

            用户补充信息：$normalizedAnswer
            """.trimIndent(),
        )
    }

    fun retry() {
        synchronized(stateLock) { lastRequest }?.let(::submit)
    }

    fun confirmExecution() {
        synchronized(stateLock) {
            val confirmationState = _uiState.value as? AgentRunState.AwaitingConfirmation ?: return
            startExecutionLocked(confirmationState.plan, confirmationState.confirmation)
        }
    }

    fun resumeRecovery(runId: String) {
        synchronized(stateLock) {
            val recoveryState = _uiState.value as? AgentRunState.RecoveryRequired ?: return
            val execution = recoveryState.executions.firstOrNull { it.record.runId == runId }
                ?: return
            val plan = execution.plan ?: return
            startExecutionLocked(plan, ExecutionConfirmation.recover(execution))
        }
    }

    fun discardRecovery(runId: String) {
        val generation: Long
        synchronized(stateLock) {
            val recoveryState = _uiState.value as? AgentRunState.RecoveryRequired ?: return
            if (recoveryState.executions.none { it.record.runId == runId }) return
            planningJob?.cancel()
            generation = ++requestGeneration
            _uiState.value = recoveryState.copy(busyRunId = runId, message = null)
        }
        viewModelScope.launch(dispatcher) {
            try {
                val deleted = executionEngine.discardExecution(runId)
                val remaining = executionEngine.listRecoverableExecutions()
                synchronized(stateLock) {
                    if (generation == requestGeneration) {
                        _uiState.value = if (!deleted) {
                            AgentRunState.RecoveryRequired(
                                executions = remaining,
                                message = "任务正在执行，暂时不能放弃",
                            )
                        } else if (remaining.isEmpty()) {
                            AgentRunState.Idle
                        } else {
                            AgentRunState.RecoveryRequired(remaining)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                synchronized(stateLock) {
                    if (generation == requestGeneration) {
                        _uiState.value = AgentRunState.Failure(
                            message = "放弃任务失败",
                            canRetry = false,
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        synchronized(stateLock) {
            requestGeneration += 1
            planningJob?.cancel()
        }
        planner.close()
        super.onCleared()
    }

    private fun PlanBuildResult.toRunState(request: String): AgentRunState = when (this) {
        is PlanBuildResult.Success -> when (val readiness = plan.assessReadiness()) {
            is PlanReadiness.NeedsClarification -> AgentRunState.NeedsClarification(
                request = request,
                plan = plan,
                question = readiness.question,
            )

            PlanReadiness.ReadyToExecute -> AgentRunState.AwaitingConfirmation(
                plan = plan,
                confirmation = ExecutionConfirmation.issue(plan),
            )
        }

        is PlanBuildResult.DecodeFailure -> AgentRunState.Failure(
            message = "助手返回的计划无法理解，请重试",
            canRetry = true,
        )

        is PlanBuildResult.ModelFailure -> AgentRunState.Failure(
            message = error.toUserMessage(),
            canRetry = error is AgentModelFailure.Network ||
                error is AgentModelFailure.RateLimited ||
                error is AgentModelFailure.Server,
        )
    }

    private fun AgentModelFailure.toUserMessage(): String = when (this) {
        is AgentModelFailure.Network -> "网络连接失败，请重试"
        is AgentModelFailure.RateLimited -> "请求过于频繁，请稍后重试"
        is AgentModelFailure.Server -> "助手服务暂时不可用，请重试"
        is AgentModelFailure.Authentication -> "助手服务配置异常，请联系开发者"
        is AgentModelFailure.InvalidRequest -> "请求不合法，请修改后重试"
        is AgentModelFailure.Unknown -> "助手暂时不可用，请稍后重试"
    }

    class Factory(
        private val planner: AgentPlanner,
        private val executionEngine: AgentExecutionEngine = AgentExecutionEngine(),
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AgentPlannerViewModel::class.java)) {
                "不支持的 ViewModel 类型：${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return AgentPlannerViewModel(
                planner = planner,
                executionEngine = executionEngine,
            ) as T
        }
    }

    private fun loadRecoverableExecutions() {
        val generation = synchronized(stateLock) { requestGeneration }
        viewModelScope.launch(dispatcher) {
            try {
                val executions = executionEngine.listRecoverableExecutions()
                var requestToStart: String? = null
                synchronized(stateLock) {
                    if (generation == requestGeneration &&
                        _uiState.value is AgentRunState.RecoveryScanning
                    ) {
                        if (executions.isNotEmpty()) {
                            pendingRequest = null
                            _uiState.value = AgentRunState.RecoveryRequired(executions)
                        } else {
                            _uiState.value = AgentRunState.Idle
                            requestToStart = pendingRequest
                            pendingRequest = null
                        }
                    }
                }
                requestToStart?.let(::submit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                synchronized(stateLock) {
                    if (generation == requestGeneration &&
                        _uiState.value is AgentRunState.RecoveryScanning
                    ) {
                        _uiState.value = AgentRunState.Failure(
                            message = "执行记录读取失败",
                            canRetry = false,
                        )
                    }
                }
            }
        }
    }

    private fun startExecutionLocked(
        plan: AgentPlan,
        confirmation: ExecutionConfirmation,
    ) {
        planningJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value = AgentRunState.Executing(plan, actionIndex = 0)
        planningJob = viewModelScope.launch(dispatcher) {
            val result = try {
                executionEngine.execute(confirmation) { actionIndex ->
                    synchronized(stateLock) {
                        if (generation == requestGeneration) {
                            _uiState.value = AgentRunState.Executing(plan, actionIndex)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ToolExecutionResult.Failure("Tool 执行失败")
            }
            currentCoroutineContext().ensureActive()
            val recoveryExecutions = if (result is ToolExecutionResult.Failure &&
                confirmation.isRecovery
            ) {
                try {
                    executionEngine.listRecoverableExecutions()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            synchronized(stateLock) {
                if (generation == requestGeneration) {
                    _uiState.value = when (result) {
                        is ToolExecutionResult.Success -> AgentRunState.Completed(
                            plan = plan,
                            report = result.report,
                        )

                        is ToolExecutionResult.Failure -> if (confirmation.isRecovery) {
                            if (recoveryExecutions.any { it.record.runId == confirmation.runId }) {
                                AgentRunState.RecoveryRequired(
                                    executions = recoveryExecutions,
                                    message = result.message,
                                )
                            } else {
                                AgentRunState.Failure(
                                    message = result.message,
                                    canRetry = false,
                                    report = result.report,
                                )
                            }
                        } else {
                            AgentRunState.Failure(
                                message = result.message,
                                canRetry = false,
                                report = result.report,
                            )
                        }
                    }
                }
            }
        }
    }
}
