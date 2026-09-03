package com.example.agent.rootpilot.action

import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.ScreenSize

sealed interface ActionPolicyResult {
    data class Allowed(val action: ExecutableRootAction) : ActionPolicyResult

    data class Rejected(val message: String) : ActionPolicyResult
}

class ActionPolicy {
    fun requiresConfirmation(
        action: RootPilotAction,
        manualConfirmation: Boolean,
    ): Boolean = when (action) {
        is RootPilotAction.Tap,
        is RootPilotAction.Swipe,
        -> manualConfirmation

        is RootPilotAction.Type,
        is RootPilotAction.Key,
        -> true

        is RootPilotAction.Wait,
        is RootPilotAction.AskUser,
        is RootPilotAction.Finish,
        -> false
    }

    fun toExecutable(action: RootPilotAction, screenSize: ScreenSize): ActionPolicyResult {
        if (screenSize.width <= 0 || screenSize.height <= 0) {
            return ActionPolicyResult.Rejected("屏幕尺寸不合法")
        }
        return when (action) {
            is RootPilotAction.Tap -> ActionPolicyResult.Allowed(
                ExecutableRootAction.Tap(
                    x = action.x.toPixel(screenSize.width),
                    y = action.y.toPixel(screenSize.height),
                ),
            )

            is RootPilotAction.Swipe -> ActionPolicyResult.Allowed(
                ExecutableRootAction.Swipe(
                    x1 = action.x1.toPixel(screenSize.width),
                    y1 = action.y1.toPixel(screenSize.height),
                    x2 = action.x2.toPixel(screenSize.width),
                    y2 = action.y2.toPixel(screenSize.height),
                    durationMillis = action.durationMillis,
                ),
            )

            is RootPilotAction.Type -> ActionPolicyResult.Allowed(
                ExecutableRootAction.Type(action.text),
            )

            is RootPilotAction.Key -> ActionPolicyResult.Allowed(
                ExecutableRootAction.Key(action.key),
            )

            is RootPilotAction.Wait -> ActionPolicyResult.Allowed(
                ExecutableRootAction.Wait(action.durationMillis),
            )

            is RootPilotAction.AskUser -> ActionPolicyResult.Rejected("ask_user 需要先交给用户处理")
            is RootPilotAction.Finish -> ActionPolicyResult.Rejected("finish 不需要 Root 执行")
        }
    }

    private fun Int.toPixel(size: Int): Int = (this * (size - 1) / 1_000).coerceIn(0, size - 1)
}
