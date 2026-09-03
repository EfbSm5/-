package com.example.agent.rootpilot.model

sealed interface RootPilotAction {
    val reason: String

    data class Tap(
        val x: Int,
        val y: Int,
        override val reason: String,
    ) : RootPilotAction

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMillis: Int,
        override val reason: String,
    ) : RootPilotAction

    data class OpenApp(
        val packageName: String,
        override val reason: String,
    ) : RootPilotAction

    data class Type(
        val text: String,
        override val reason: String,
    ) : RootPilotAction

    data class Key(
        val key: RootPilotKey,
        override val reason: String,
    ) : RootPilotAction

    data class Wait(
        val durationMillis: Int,
        override val reason: String,
    ) : RootPilotAction

    data class AskUser(
        val message: String,
    ) : RootPilotAction {
        override val reason: String = message
    }

    data class Finish(
        val success: Boolean,
        val message: String,
    ) : RootPilotAction {
        override val reason: String = message
    }
}

enum class RootPilotKey {
    BACK,
    HOME,
    ENTER,
}

enum class RootPilotApp(val packageName: String) {
    SETTINGS("com.android.settings"),
    ;

    companion object {
        fun fromPackageName(packageName: String): RootPilotApp? = entries.firstOrNull {
            it.packageName == packageName
        }
    }
}

data class ScreenSize(
    val width: Int,
    val height: Int,
)

sealed interface ExecutableRootAction {
    data class Tap(val x: Int, val y: Int) : ExecutableRootAction

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMillis: Int,
    ) : ExecutableRootAction

    data class OpenApp(val app: RootPilotApp) : ExecutableRootAction

    data class Type(val text: String) : ExecutableRootAction

    data class Key(val key: RootPilotKey) : ExecutableRootAction

    data class Wait(val durationMillis: Int) : ExecutableRootAction
}
