package com.example.agent.rootpilot.history

import com.example.agent.rootpilot.log.*
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunHistoryRepositoryTest {
    @Test fun loopEndRemainsRunningUntilControllerCompletion() = runTest {
        val storage = MemoryStorage()
        val repository = RunHistoryRepository(storage, backgroundScope)
        val id = UUID.randomUUID().toString()
        repository.begin(id, 123)
        repository.record(event(id, TraceEvent.RUN_END))
        runCurrent()
        assertEquals(RunHistoryStatus.RUNNING, repository.state.value.records.single().status)
        repository.finish(id, RunHistoryStatus.COMPLETED, 200, TraceReason.NONE)
        runCurrent()
        val record = repository.state.value.records.single()
        assertEquals(RunHistoryStatus.COMPLETED, record.status)
        assertEquals(123L, record.startedAtEpochMs)
        assertEquals(200L, record.durationMs)
        assertEquals(1, record.stepCount)
    }

    @Test fun clearBeforeInitialLoadFinishesAndLateEventsCannotResurrectOldRun() = runTest {
        val oldId = UUID.randomUUID().toString()
        val currentId = UUID.randomUUID().toString()
        val storage = MemoryStorage(listOf(RunHistoryRecord(oldId, 1)))
        val repository = RunHistoryRepository(storage, backgroundScope)
        repository.begin(currentId)
        repository.record(event(currentId))
        repository.clear()
        repository.record(event(currentId))
        repository.finish(currentId, RunHistoryStatus.STOPPED, 1, TraceReason.CANCELLED)
        runCurrent()
        assertTrue(repository.state.value.records.isEmpty())
        assertTrue(storage.records.isEmpty())
        val writes = storage.writes
        repository.record(event(oldId))
        repository.record(event(currentId))
        runCurrent()
        assertEquals(writes, storage.writes)
        repository.begin(UUID.randomUUID().toString())
        runCurrent()
        assertEquals(1, storage.records.size)
    }

    @Test fun unfinishedPersistedRunBecomesInterruptedWithLastObservedDuration() = runTest {
        val running = RunHistoryRecord(UUID.randomUUID().toString(), 1, durationMs = 72)
        val completed = running.copy(id = UUID.randomUUID().toString(), status = RunHistoryStatus.COMPLETED)
        val storage = MemoryStorage(listOf(running, completed))
        val repository = RunHistoryRepository(storage, backgroundScope)
        runCurrent()
        assertEquals(listOf(RunHistoryStatus.INTERRUPTED, RunHistoryStatus.COMPLETED),
            repository.state.value.records.map { it.status })
        assertEquals(72L, storage.records.first().durationMs)
        assertEquals(RunHistoryStatus.INTERRUPTED, storage.records.first().status)
    }

    @Test fun recordsAndEventTailAreBoundedWhileTotalStepCountSurvives() = runTest {
        val repository = RunHistoryRepository(MemoryStorage(), backgroundScope)
        val ids = List(51) { UUID.randomUUID().toString() }
        ids.forEachIndexed { index, id -> repository.begin(id, index.toLong()) }
        val id = ids.last()
        repository.record(event(id).copy(step = 19))
        repeat(300) { repository.record(event(id).copy(elapsedMs = it.toLong())) }
        runCurrent()
        val records = repository.state.value.records
        assertEquals(50, records.size)
        assertEquals(ids.drop(1).reversed(), records.map { it.id })
        assertEquals(256, records.first().events.size)
        assertEquals(20, records.first().stepCount)
        assertEquals(299L, records.first().durationMs)
        assertTrue(records.first().eventsTruncated)
    }

    @Test fun storageFailuresAreFixedVisibleAndWorkerContinuesWithoutAutomaticRetry() = runTest {
        val storage = MemoryStorage().apply { failRead = true; failWrite = true }
        val repository = RunHistoryRepository(storage, backgroundScope)
        runCurrent()
        assertEquals(RunHistoryError.READ_FAILED, repository.state.value.error)
        val id = UUID.randomUUID().toString()
        repository.begin(id)
        repository.finish(id, RunHistoryStatus.COMPLETED, 10, TraceReason.NONE)
        runCurrent()
        assertEquals(RunHistoryError.READ_FAILED, repository.state.value.error)
        assertEquals(RunHistoryStatus.COMPLETED, repository.state.value.records.single().status)
        assertEquals(2, storage.writes)
        runCurrent()
        assertEquals(2, storage.writes)
        storage.failClear = true
        repository.clear()
        repository.record(event(id))
        runCurrent()
        assertEquals(RunHistoryError.CLEAR_FAILED, repository.state.value.error)
        assertEquals(1, repository.state.value.records.size)
        storage.failClear = false
        repository.clear()
        runCurrent()
        assertNull(repository.state.value.error)
    }

    @Test fun failedClearPreservesRecordsAndSuccessfulRetryIgnoresActiveRunEvents() = runTest {
        val storage = MemoryStorage()
        val repository = RunHistoryRepository(storage, backgroundScope)
        val id = UUID.randomUUID().toString()
        repository.begin(id)
        runCurrent()
        storage.failClear = true
        repository.clear()
        repository.record(event(id))
        runCurrent()
        assertEquals(RunHistoryError.CLEAR_FAILED, repository.state.value.error)
        assertEquals(1, storage.records.single().events.size)
        assertEquals(storage.records, repository.state.value.records)
        storage.failClear = false
        repository.clear()
        repository.record(event(id))
        repository.finish(id, RunHistoryStatus.COMPLETED, 20, TraceReason.NONE)
        runCurrent()
        assertNull(repository.state.value.error)
        assertTrue(repository.state.value.records.isEmpty())
        assertTrue(storage.records.isEmpty())
    }

    @Test fun successfulWriteClearsWriteError() = runTest {
        val storage = MemoryStorage().apply { failWrite = true }
        val repository = RunHistoryRepository(storage, backgroundScope)
        val id = UUID.randomUUID().toString()
        repository.begin(id)
        runCurrent()
        assertEquals(RunHistoryError.WRITE_FAILED, repository.state.value.error)
        storage.failWrite = false
        repository.record(event(id))
        runCurrent()
        assertNull(repository.state.value.error)
    }

    private fun event(id: String, event: TraceEvent = TraceEvent.RESULT) = RunTraceEvent(
        id, 0, 10, TraceActionType.TAP, TraceStage.EXECUTION, event, TraceStatus.SUCCESS, TraceReason.NONE,
    )

    private class MemoryStorage(var records: List<RunHistoryRecord> = emptyList()) : RunHistoryStorage {
        var failRead = false
        var failWrite = false
        var failClear = false
        var writes = 0
        override fun read(): List<RunHistoryRecord> {
            if (failRead) error("SECRET")
            return records
        }
        override fun write(records: List<RunHistoryRecord>) {
            writes++
            if (failWrite) error("SECRET")
            this.records = records
        }
        override fun clear() {
            if (failClear) error("SECRET")
            records = emptyList()
        }
    }
}
