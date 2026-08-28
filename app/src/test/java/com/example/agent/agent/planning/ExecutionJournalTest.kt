package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.CreateTodo
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
            plan = PersistedAgentPlan.fromDomain(
                AgentPlan(
                    goal = "恢复任务",
                    actions = listOf(CreateTodo("测试待办", dueAt = null)),
                ),
            ),
        )

        FileExecutionJournal(file).write(record)

        assertEquals(record, FileExecutionJournal(file).read("run-1"))
        assertEquals(
            AgentPlan(
                goal = "恢复任务",
                actions = listOf(CreateTodo("测试待办", dueAt = null)),
            ),
            FileExecutionJournal(file).read("run-1")?.plan?.toDomainOrNull(),
        )
    }

    @Test
    fun fileJournal_listsUnfinishedRecordsAndDeletesThem() = runTest {
        val directory = Files.createTempDirectory("agent-journal-test").toFile()
        val file = directory.resolve("execution_journal.json")
        val plan = AgentPlan(
            goal = "恢复任务",
            actions = listOf(CreateTodo("测试待办", dueAt = null)),
        )
        val journal = FileExecutionJournal(file)
        journal.write(
            ExecutionRecord(
                runId = "unfinished",
                status = ExecutionRunStatus.RUNNING,
                report = ToolExecutionReport(),
                plan = PersistedAgentPlan.fromDomain(plan),
            ),
        )
        journal.write(
            ExecutionRecord(
                runId = "finished",
                status = ExecutionRunStatus.SUCCEEDED,
                report = ToolExecutionReport(),
                plan = PersistedAgentPlan.fromDomain(plan),
            ),
        )

        assertEquals(listOf("unfinished"), journal.listUnfinished().map { it.runId })

        journal.delete("unfinished")

        assertEquals(emptyList<ExecutionRecord>(), journal.listUnfinished())
    }

    @Test
    fun journal_allowsOnlyOneRecoveryClaim() = runTest {
        val journal = InMemoryExecutionJournal()
        journal.write(
            ExecutionRecord(
                runId = "run-claim",
                status = ExecutionRunStatus.FAILED,
                report = ToolExecutionReport(),
            ),
        )

        val firstClaim = journal.claim("run-claim", "owner-1")
        val secondClaim = journal.claim("run-claim", "owner-2")

        assertEquals("owner-1", firstClaim?.ownerToken)
        assertEquals(null, secondClaim)
        assertEquals(false, journal.delete("run-claim"))
    }

    @Test
    fun staleRunningRecord_canBeClaimedAfterProcessOwnerIsReleased() = runTest {
        val journal = InMemoryExecutionJournal()
        journal.write(
            ExecutionRecord(
                runId = "run-stale",
                status = ExecutionRunStatus.RUNNING,
                report = ToolExecutionReport(),
                ownerToken = "old-owner",
            ),
        )

        journal.release("run-stale", "old-owner")

        assertEquals("new-owner", journal.claim("run-stale", "new-owner")?.ownerToken)
    }
}
