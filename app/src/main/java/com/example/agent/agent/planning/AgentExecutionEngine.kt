package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
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

sealed interface ToolExecutionResult {
    data class Success(
        val createdTodos: List<CreateTodo>,
        val openedPackages: List<String> = emptyList(),
    ) : ToolExecutionResult

    data class Failure(
        val message: String,
        val openedPackages: List<String> = emptyList(),
    ) : ToolExecutionResult
}

private object UnconfiguredAppLauncher : AppLauncher {
    override fun preflight(packageName: String): AppLaunchPreflight =
        AppLaunchPreflight.Failure("OpenApp Tool 未配置")

    override suspend fun launch(packageName: String): AppLaunchResult =
        AppLaunchResult.Failure("OpenApp Tool 未配置")
}

class AgentExecutionEngine(
    private val todoRepository: TodoRepository = InMemoryTodoRepository(),
    private val appLauncher: AppLauncher = UnconfiguredAppLauncher,
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
        if (plan.actions.any { it !is CreateTodo && it !is OpenApp }) {
            return ToolExecutionResult.Failure("当前只允许执行 create_todo 和 open_app Tool")
        }

        plan.actions.forEach { action ->
            if (action is OpenApp) {
                when (val preflight = appLauncher.preflight(action.packageName)) {
                    AppLaunchPreflight.Ready -> Unit
                    is AppLaunchPreflight.Denied -> return ToolExecutionResult.Failure(
                        "未授权打开应用：${preflight.packageName}",
                    )

                    is AppLaunchPreflight.Failure -> return ToolExecutionResult.Failure(
                        preflight.message,
                    )
                }
            }
        }

        val createdTodos = mutableListOf<CreateTodo>()
        val openedPackages = mutableListOf<String>()
        plan.actions.forEachIndexed { index, action ->
            onActionStarted(index)
            when (action) {
                is CreateTodo -> createdTodos += action
                is OpenApp -> {
                    currentCoroutineContext().ensureActive()
                    val launchResult = try {
                        appLauncher.launch(action.packageName)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return ToolExecutionResult.Failure(
                            message = "打开应用失败",
                            openedPackages = openedPackages.toList(),
                        )
                    }
                    when (launchResult) {
                        AppLaunchResult.Launched -> openedPackages += action.packageName
                        is AppLaunchResult.Denied -> return ToolExecutionResult.Failure(
                            message = "未授权打开应用：${launchResult.packageName}",
                            openedPackages = openedPackages.toList(),
                        )

                        is AppLaunchResult.Failure -> return ToolExecutionResult.Failure(
                            message = launchResult.message,
                            openedPackages = openedPackages.toList(),
                        )
                    }
                }

                else -> return ToolExecutionResult.Failure(
                    message = "当前只允许执行 create_todo 和 open_app Tool",
                    openedPackages = openedPackages.toList(),
                )
            }
        }

        // 待办写入延后到所有 OpenApp 成功后，避免打开应用失败时留下半个计划。
        currentCoroutineContext().ensureActive()
        try {
            todoRepository.addAll(createdTodos)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ToolExecutionResult.Failure(
                message = "待办保存失败",
                openedPackages = openedPackages.toList(),
            )
        }
        return ToolExecutionResult.Success(
            createdTodos = createdTodos,
            openedPackages = openedPackages,
        )
    }
}
