package com.example.agent.agent.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RawAgentPlan(
    val goal: String,
    val actions: List<RawAgentAction>,
)

@Serializable
internal data class RawAgentAction(
    val type: String,
    val title: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    val question: String? = null,
)
