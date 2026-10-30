package com.example.agent.rootpilot

/** Test-only framework literal classification; never retain messages or object identities. */
internal enum class UiAutomationFailure {
    UI_AUTOMATION_CONNECTION_SHUTDOWN,
    UI_AUTOMATION_ALREADY_CONNECTED,
    UI_AUTOMATION_ALREADY_REGISTERED,
    UI_AUTOMATION_REGISTRATION_FAILED,
    UI_AUTOMATION_DISCONNECT_CONNECTING;

    companion object {
        fun classify(error: Throwable): UiAutomationFailure? {
            if (error !is IllegalStateException) return null
            val message = error.message.orEmpty()
            return when {
                message == "Connection shutdown!" -> UI_AUTOMATION_CONNECTION_SHUTDOWN
                message == "Already connected." || message.startsWith("UiAutomation connected, ") ->
                    UI_AUTOMATION_ALREADY_CONNECTED
                message.startsWith("UiAutomationService ") && message.endsWith("already registered!") ->
                    UI_AUTOMATION_ALREADY_REGISTERED
                message.startsWith("Error while registering UiTestAutomationService") ->
                    UI_AUTOMATION_REGISTRATION_FAILED
                message.startsWith("Cannot call disconnect() while connecting ") ->
                    UI_AUTOMATION_DISCONNECT_CONNECTING
                else -> null
            }
        }
    }
}
