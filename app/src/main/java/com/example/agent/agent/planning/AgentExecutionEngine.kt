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
        val actionResults = mutableListOf<ActionExecutionRecord>()
        preparedActions.forEach { preparedAction ->
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
                is ToolActionOutcome.Staged -> actionResults += ActionExecutionRecord(
                    actionIndex = preparedAction.index,
                    toolName = preparedAction.tool.name,
                    status = ActionExecutionStatus.STAGED,
                    detail = outcome.detail,
                )

                is ToolActionOutcome.Succeeded -> actionResults += ActionExecutionRecord(
                    actionIndex = preparedAction.index,
                    toolName = preparedAction.tool.name,
                    status = ActionExecutionStatus.SUCCEEDED,
                    detail = outcome.detail,
                )

                is ToolActionOutcome.Failed -> {
                    actionResults += ActionExecutionRecord(
                        actionIndex = preparedAction.index,
                        toolName = preparedAction.tool.name,
                        status = ActionExecutionStatus.FAILED,
                        detail = outcome.message,
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
            return ToolExecutionResult.Failure(
                message = "待办保存失败",
                report = ToolExecutionReport(actionResults.toList()),
            )
        }
        return ToolExecutionResult.Success(
            report = ToolExecutionReport(
                actionResults = actionResults.map { result ->
                    if (result.status == ActionExecutionStatus.STAGED) {
                        result.copy(status = ActionExecutionStatus.SUCCEEDED)
                    } else {
                        result
                    }
                },
            ),
        )
    }

    private data class PreparedAction(
        val index: Int,
        val action: com.example.agent.agent.model.AgentAction,
        val tool: AgentTool,
    )
}
