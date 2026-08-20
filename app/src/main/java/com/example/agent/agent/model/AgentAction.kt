package com.example.agent.agent.model

sealed interface AgentAction

data class CreateTodo(
    val title: String,
    val dueAt: String?,
) : AgentAction

data class OpenApp(
    val packageName: String,
) : AgentAction

data class AskUser(
    val question: String,
) : AgentAction