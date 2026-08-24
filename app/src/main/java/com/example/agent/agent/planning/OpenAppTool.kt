package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.OpenApp

class OpenAppTool(
    private val appLauncher: AppLauncher,
) : AgentTool {
    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = AgentToolNames.OPEN_APP,
        description = "打开一个已安装且被允许的 Android 应用。",
        parameters = listOf(
            ToolParameterDescriptor(
                name = "package_name",
                description = "Android 应用包名。",
                required = true,
            ),
        ),
        constraints = listOf(
            if (appLauncher.allowedPackageNames.isEmpty()) {
                "package_name 必须通过运行时白名单校验。"
            } else {
                "package_name 只能是：${appLauncher.allowedPackageNames.sorted().joinToString()}。"
            },
        ),
    )

    override fun supports(action: AgentAction): Boolean = action is OpenApp

    override fun preflight(action: AgentAction): ToolPreflightResult {
        val openApp = action as? OpenApp
            ?: return ToolPreflightResult.Rejected("$name Tool 收到了不支持的 Action")
        return when (val result = appLauncher.preflight(openApp.packageName)) {
            AppLaunchPreflight.Ready -> ToolPreflightResult.Ready
            is AppLaunchPreflight.Denied -> ToolPreflightResult.Rejected(
                "未授权打开应用：${result.packageName}",
            )

            is AppLaunchPreflight.Failure -> ToolPreflightResult.Rejected(result.message)
        }
    }

    override suspend fun execute(
        action: AgentAction,
        context: ToolExecutionContext,
    ): ToolActionOutcome {
        val openApp = action as? OpenApp
            ?: return ToolActionOutcome.Failed("$name Tool 收到了不支持的 Action")
        return when (val result = appLauncher.launch(openApp.packageName)) {
            AppLaunchResult.Launched -> ToolActionOutcome.Succeeded(openApp.packageName)
            is AppLaunchResult.Denied -> ToolActionOutcome.Failed(
                "未授权打开应用：${result.packageName}",
            )

            is AppLaunchResult.Failure -> ToolActionOutcome.Failed(result.message)
        }
    }
}
