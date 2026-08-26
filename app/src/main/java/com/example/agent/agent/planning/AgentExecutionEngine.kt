package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface TodoRepository {
    suspend fun add(todo: CreateTodo) = addAll(listOf(todo))

    suspend fun addAll(todos: List<CreateTodo>)

    suspend fun list(): List<CreateTodo>
}

class InMemoryTodoRepository : TodoRepository {
    private val todos = mutableListOf<CreateTodo>()

    override suspend fun addAll(todos: List<CreateTodo>) {
        synchronized(this.todos) {
            this.todos += todos
        }
    }

    override suspend fun list(): List<CreateTodo> = synchronized(todos) { todos.toList() }
}

class AgentExecutionEngine(
    private val todoRepository: TodoRepository = InMemoryTodoRepository(),
    private val toolRegistry: ToolRegistry = ToolRegistry.default(),
    private val executionJournal: ExecutionJournal = InMemoryExecutionJournal(),
) {
    suspend fun execute(
        confirmation: ExecutionConfirmation,
        onActionStarted: suspend (Int) -> Unit = {},
    ): ToolExecutionResult {
        val plan = confirmation.plan
        if (plan.assessReadiness() != PlanReadiness.ReadyToExecute) {
            return ToolExecutionResult.Failure("计划仍需要用户补充信息")
        }
        if (plan.actions.isEmpty()) {
            return ToolExecutionResult.Failure("计划没有可执行 Action")
        }
        val existingRecord = try {
            executionJournal.read(confirmation.runId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ToolExecutionResult.Failure("执行记录读取失败")
        }
        if (existingRecord?.status == ExecutionRunStatus.SUCCEEDED) {
            return ToolExecutionResult.Success(existingRecord.report)
        }
        if (existingRecord?.status == ExecutionRunStatus.RUNNING) {
            return ToolExecutionResult.Failure(
                message = "上一次执行状态不确定，请人工确认后再试",
                report = existingRecord.report,
            )
        }

        val preparedActions = mutableListOf<PreparedAction>()
        plan.actions.forEachIndexed { index, action ->
            val tool = toolRegistry.resolve(action)
                ?: return ToolExecutionResult.Failure(
                    "没有注册可以执行 actions[$index] 的 Tool",
                )
            val preflight = try {
                currentCoroutineContext().ensureActive()
                tool.preflight(action)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return ToolExecutionResult.Failure("${tool.name} Tool 预检失败")
            }
            when (preflight) {
                ToolPreflightResult.Ready -> preparedActions += PreparedAction(index, action, tool)
                is ToolPreflightResult.Rejected -> return ToolExecutionResult.Failure(
                    preflight.message,
                )
            }
        }

        val context = ToolExecutionContext()
        val actionResults = existingRecord?.report?.actionResults
            ?.filterNot { it.status == ActionExecutionStatus.RUNNING }
            ?.toMutableList()
            ?: mutableListOf()
        if (!persist(
                runId = confirmation.runId,
                status = ExecutionRunStatus.RUNNING,
                actionResults = actionResults,
            )
        ) {
            return ToolExecutionResult.Failure(
                message = "执行记录保存失败",
                report = ToolExecutionReport(actionResults.toList()),
            )
        }

        preparedActions.forEach { preparedAction ->
            if (actionResults.any {
                    it.actionIndex == preparedAction.index &&
                        it.status == ActionExecutionStatus.SUCCEEDED
                }
            ) {
                return@forEach
            }

            actionResults.removeAll { it.actionIndex == preparedAction.index }
            actionResults += ActionExecutionRecord(
                actionIndex = preparedAction.index,
                toolName = preparedAction.tool.name,
                status = ActionExecutionStatus.RUNNING,
            )
            if (!persist(
                    runId = confirmation.runId,
                    status = ExecutionRunStatus.RUNNING,
                    actionResults = actionResults,
                )
            ) {
                return ToolExecutionResult.Failure(
                    message = "执行记录保存失败",
                    report = ToolExecutionReport(actionResults.toList()),
                )
            }
            onActionStarted(preparedAction.index)
            currentCoroutineContext().ensureActive()
            val outcome = try {
                preparedAction.tool.execute(preparedAction.action, context)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ToolActionOutcome.Failed("${preparedAction.tool.name} Tool 执行失败")
            }
            when (outcome) {
                is ToolActionOutcome.Staged -> actionResults.replace(
                    ActionExecutionRecord(
                        actionIndex = preparedAction.index,
                        toolName = preparedAction.tool.name,
                        status = ActionExecutionStatus.STAGED,
                        detail = outcome.detail,
                    ),
                )

                is ToolActionOutcome.Succeeded -> actionResults.replace(
                    ActionExecutionRecord(
                        actionIndex = preparedAction.index,
                        toolName = preparedAction.tool.name,
                        status = ActionExecutionStatus.SUCCEEDED,
                        detail = outcome.detail,
                    ),
                )

                is ToolActionOutcome.Failed -> {
                    actionResults.replace(
                        ActionExecutionRecord(
                            actionIndex = preparedAction.index,
                            toolName = preparedAction.tool.name,
                            status = ActionExecutionStatus.FAILED,
                            detail = outcome.message,
                        ),
                    )
                    persist(
                        runId = confirmation.runId,
                        status = ExecutionRunStatus.FAILED,
                        actionResults = actionResults,
                        failureMessage = outcome.message,
                    )
                    return ToolExecutionResult.Failure(
                        message = outcome.message,
                        report = ToolExecutionReport(actionResults.toList()),
                    )
                }
            }
        }

        // 待办写入延后到所有 OpenApp 成功后，避免打开应用失败时留下半个计划。
        currentCoroutineContext().ensureActive()
        try {
            todoRepository.addAll(context.pendingTodos())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            persist(
                runId = confirmation.runId,
                status = ExecutionRunStatus.FAILED,
                actionResults = actionResults,
                failureMessage = "待办保存失败",
            )
            return ToolExecutionResult.Failure(
                message = "待办保存失败",
                report = ToolExecutionReport(actionResults.toList()),
            )
        }
        val completedReport = ToolExecutionReport(
            actionResults = actionResults.map { result ->
                if (result.status == ActionExecutionStatus.STAGED) {
                    result.copy(status = ActionExecutionStatus.SUCCEEDED)
                } else {
                    result
                }
            },
        )
        if (!persist(
                runId = confirmation.runId,
                status = ExecutionRunStatus.SUCCEEDED,
                actionResults = completedReport.actionResults,
            )
        ) {
            return ToolExecutionResult.Failure(
                message = "执行记录保存失败",
                report = completedReport,
            )
        }
        return ToolExecutionResult.Success(completedReport)
    }

    private suspend fun persist(
        runId: String,
        status: ExecutionRunStatus,
        actionResults: List<ActionExecutionRecord>,
        failureMessage: String? = null,
    ): Boolean = try {
        executionJournal.write(
            ExecutionRecord(
                runId = runId,
                status = status,
                report = ToolExecutionReport(actionResults.toList()),
                failureMessage = failureMessage,
            ),
        )
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun MutableList<ActionExecutionRecord>.replace(record: ActionExecutionRecord) {
        removeAll { it.actionIndex == record.actionIndex }
        add(record)
    }

    private data class PreparedAction(
        val index: Int,
        val action: com.example.agent.agent.model.AgentAction,
        val tool: AgentTool,
    )
}
