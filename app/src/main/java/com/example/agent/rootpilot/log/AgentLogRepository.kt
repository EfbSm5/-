package com.example.agent.rootpilot.log

interface AgentLogRepository {
    fun append(message: String)

    fun list(): List<String>
}

class InMemoryAgentLogRepository(private val capacity: Int = 500) : AgentLogRepository {
    init { require(capacity > 0) }
    private val entries = ArrayDeque<String>()

    override fun append(message: String) {
        synchronized(entries) {
            entries += message
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    override fun list(): List<String> = synchronized(entries) { entries.toList() }
}
