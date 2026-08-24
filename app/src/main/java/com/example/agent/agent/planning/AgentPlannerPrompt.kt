package com.example.agent.agent.planning

class AgentPlannerPrompt(
    private val schemaText: String,
    private val toolRegistry: ToolRegistry,
) {
    fun build(userRequest: String): String = """
        $PLANNER_PROMPT

        Available executable Tools:
        ${toolRegistry.describeForModel()}

        JSON Schema:
        $schemaText

        User request:
        <user_request>
        $userRequest
        </user_request>
    """.trimIndent()

    private companion object {
        const val PLANNER_PROMPT = """
            Convert the user request into a safe, not-yet-executed Agent plan.
            Use ask_user when important information is missing.
            Use only the listed executable Tools and follow their constraints.
        """
    }
}
