package com.example.agent.rootpilot.log

interface AgentLogRepository {
    fun append(message: String)

    fun list(): List<String>
}

class InMemoryAgentLogRepository : AgentLogRepository {
    private val entries = mutableListOf<String>()

    override fun append(message: String) {
        synchronized(entries) {
            entries += message
        }
    }

    override fun list(): List<String> = synchronized(entries) { entries.toList() }
}
