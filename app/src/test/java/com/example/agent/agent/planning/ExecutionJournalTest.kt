package com.example.agent.agent.planning

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionJournalTest {
    @Test
    fun fileJournal_survivesRepositoryRecreation() = runTest {
        val directory = Files.createTempDirectory("agent-journal-test").toFile()
        val file = directory.resolve("execution_journal.json")
        val record = ExecutionRecord(
            runId = "run-1",
            status = ExecutionRunStatus.SUCCEEDED,
            report = ToolExecutionReport(
                actionResults = listOf(
                    ActionExecutionRecord(
                        actionIndex = 0,
                        toolName = AgentToolNames.CREATE_TODO,
                        status = ActionExecutionStatus.SUCCEEDED,
                        detail = "测试待办",
                    ),
                ),
            ),
        )

        FileExecutionJournal(file).write(record)

        assertEquals(record, FileExecutionJournal(file).read("run-1"))
    }
}
