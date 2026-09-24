package com.example.agent.rootpilot.history

import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.RootPilotAction
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun typedActionHistoryRoundTripsWithoutSensitivePayloads() {
        val file = temporary.root.resolve("history/runs.json")
        val store = RunHistoryStore(file)
        assertTrue(store.read().isEmpty())
        val events = mutableListOf<RunTraceEvent>()
        val trace = RunTrace(observer = { events += it })
        listOf(
            RootPilotAction.Type("SECRET_INPUT", "SECRET_REASON"),
            RootPilotAction.OpenApp("SECRET_PACKAGE", "SECRET_REASON"),
            RootPilotAction.CreateTodo("SECRET_TITLE", "SECRET_DATE", "SECRET_REASON"),
        ).forEach {
            trace.action(it)
            trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)
        }
        val records = listOf(RunHistoryRecord(trace.runId, 1, events = events))
        store.write(records)
        assertEquals(records, store.read())
        assertFalse(file.readText().contains("SECRET"))
        assertFalse(file.readText().contains("physicalX"))
        store.clear()
        assertTrue(store.read().isEmpty())
    }

    @Test fun corruptUnknownFieldsInvalidIdsAndExcessRecordsAreRejected() {
        val file = temporary.newFile()
        val store = RunHistoryStore(file)
        listOf("broken", """{"version":2,"records":[]}""",
            """{"version":1,"records":[],"task":"SECRET"}""").forEach {
            file.writeText(it)
            assertThrows(Exception::class.java) { store.read() }
        }
        assertThrows(Exception::class.java) { store.write(listOf(RunHistoryRecord("SECRET", 1))) }
        assertThrows(Exception::class.java) {
            store.write(List(51) { RunHistoryRecord(UUID.randomUUID().toString(), 1) })
        }
    }

    @Test fun failedReplacementPreservesFileAndRemovesTemporaryFile() {
        val directory = temporary.newFolder()
        val target = directory.resolve("runs.json").apply { mkdir() }
        target.resolve("occupied").writeText("fixture")
        val store = RunHistoryStore(target)
        assertThrows(Exception::class.java) { store.write(emptyList()) }
        assertEquals(listOf("runs.json"), directory.listFiles()!!.map { it.name })
        assertTrue(target.resolve("occupied").isFile)
    }

    @Test fun clearRemovesOnlyOwnedTemporaryCopiesAndOfficialFile() {
        val directory = temporary.newFolder()
        val file = directory.resolve("runs.json")
        val store = RunHistoryStore(file)
        store.write(listOf(RunHistoryRecord(UUID.randomUUID().toString(), 1)))
        val abandoned = directory.resolve("history-123.tmp").apply { writeText(file.readText()) }
        val unrelated = listOf("other.tmp", "history-123.json", "other-history-123.tmp")
            .map { directory.resolve(it).apply { writeText("keep") } }
        val nested = directory.resolve("nested").apply { mkdir() }
            .resolve("history-456.tmp").apply { writeText("keep") }
        store.clear()
        assertFalse(file.exists())
        assertFalse(abandoned.exists())
        (unrelated + nested).forEach { assertEquals("keep", it.readText()) }
        store.clear()
    }

    @Test fun startupReadRemovesAbandonedCopyEvenWithoutOfficialFile() {
        val directory = temporary.newFolder()
        val abandoned = directory.resolve("history-123.tmp").apply { writeText("old history") }
        val store = RunHistoryStore(directory.resolve("runs.json"))
        assertTrue(store.read().isEmpty())
        assertFalse(abandoned.exists())
    }

    @Test fun writeRemovesAbandonedCopiesBeforeReplacingHistory() {
        val directory = temporary.newFolder()
        val file = directory.resolve("runs.json")
        val store = RunHistoryStore(file)
        store.write(listOf(RunHistoryRecord(UUID.randomUUID().toString(), 1)))
        val abandoned = directory.resolve("history-123.tmp").apply { writeText(file.readText()) }
        store.write(emptyList())
        assertFalse(abandoned.exists())
        assertTrue(store.read().isEmpty())
        assertEquals(listOf("runs.json"), directory.listFiles()!!.map { it.name })
    }

    @Test fun temporaryCleanupFailurePreservesOfficialHistoryAndDoesNotTraverseDirectory() {
        val directory = temporary.newFolder()
        val file = directory.resolve("runs.json")
        val store = RunHistoryStore(file)
        store.write(listOf(RunHistoryRecord(UUID.randomUUID().toString(), 1)))
        val original = file.readText()
        // A non-file at an owned temporary path gives a deterministic failure without permission tricks.
        val blocked = directory.resolve("history-123.tmp").apply { mkdir() }
        val child = blocked.resolve("keep").apply { writeText("keep") }
        assertThrows(Exception::class.java) { store.clear() }
        assertEquals(original, file.readText())
        assertThrows(Exception::class.java) { store.write(emptyList()) }
        assertEquals(original, file.readText())
        assertThrows(Exception::class.java) { store.read() }
        assertEquals("keep", child.readText())
        assertEquals(setOf("runs.json", "history-123.tmp"), directory.listFiles()!!.map { it.name }.toSet())
    }
}
