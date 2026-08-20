package com.example.agent.agent.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
    private val _uiState = MutableStateFlow<AgentRunState>(AgentRunState.Idle)
    val uiState: StateFlow<AgentRunState> = _uiState.asStateFlow()

    private val stateLock = Any()
    private var planningJob: Job? = null
    private var lastRequest: String? = null
    private var requestGeneration = 0L

    fun submit(userRequest: String) {
        val normalizedRequest = userRequest.trim()
        val generation: Long
        synchronized(stateLock) {
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
        val generation: Long
        synchronized(stateLock) {
            val confirmationState = _uiState.value as? AgentRunState.AwaitingConfirmation ?: return
            val plan = confirmationState.plan
            val confirmation = confirmationState.confirmation
            planningJob?.cancel()
            generation = ++requestGeneration
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
                synchronized(stateLock) {
                    if (generation == requestGeneration) {
                        _uiState.value = when (result) {
                            is ToolExecutionResult.Success -> AgentRunState.Completed(
                                plan = plan,
                                createdTodos = result.createdTodos,
                            )

                            is ToolExecutionResult.Failure -> AgentRunState.Failure(
                                message = result.message,
                                canRetry = false,
                            )
                        }
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
}
