package com.example.agent.rootpilot.chat

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryRepositoryTest {
    @Test
    fun loadsSummariesAndSavesNewestFirstKeepingThirtySessions() = runTest {
        val storage = MemoryChatHistory((1..30).map { session(it.toLong()) })
        val repository = ChatHistoryRepository(storage, backgroundScope)
        assertTrue(repository.state.value.loading)
        runCurrent()
        assertFalse(repository.state.value.loading)
        assertEquals(30, repository.state.value.sessions.size)
        val oldest = repository.state.value.sessions.last()

        val fresh = session(0)
        repository.save(fresh)
        runCurrent()
        // Ordering follows save order, not the stored clock value.
        assertEquals(fresh.id, repository.state.value.sessions.first().id)
        assertEquals(30, repository.state.value.sessions.size)
        assertFalse(storage.sessions.containsKey(oldest.id))
        assertEquals(30, storage.sessions.size)

        val resaved = storage.sessions.getValue(repository.state.value.sessions.last().id)
        repository.save(resaved.copy(messages = resaved.messages + resaved.messages))
        runCurrent()
        assertEquals(resaved.id, repository.state.value.sessions.first().id)
        assertEquals(4, repository.state.value.sessions.first().messageCount)
        assertEquals(30, storage.sessions.size)
    }

    @Test
    fun failedPruneKeepsVictimListedAndReportsDeleteFailure() = runTest {
        val storage = MemoryChatHistory((1..30).map { session(it.toLong()) })
        val repository = ChatHistoryRepository(storage, backgroundScope)
        runCurrent()
        storage.failDelete = true
        repository.save(session(99))
        runCurrent()
        assertEquals(31, repository.state.value.sessions.size)
        assertEquals(ChatHistoryError.DELETE_FAILED, repository.state.value.error)
        assertEquals(31, storage.sessions.size)
    }

    @Test
    fun writeFailureKeepsListingAndIsClearedByNextSuccessfulSave() = runTest {
        val storage = MemoryChatHistory()
        val repository = ChatHistoryRepository(storage, backgroundScope)
        runCurrent()
        storage.failWrite = true
        repository.save(session(1))
        runCurrent()
        assertEquals(ChatHistoryError.WRITE_FAILED, repository.state.value.error)
        assertTrue(repository.state.value.sessions.isEmpty())
        storage.failWrite = false
        repository.save(session(2))
        runCurrent()
        assertNull(repository.state.value.error)
        assertEquals(1, repository.state.value.sessions.size)
    }

    @Test
    fun readFailureStaysFlaggedUntilClearSucceeds() = runTest {
        val storage = MemoryChatHistory().apply { failList = true }
        val repository = ChatHistoryRepository(storage, backgroundScope)
        runCurrent()
        assertEquals(ChatHistoryError.READ_FAILED, repository.state.value.error)
        repository.save(session(1))
        runCurrent()
        assertEquals(ChatHistoryError.READ_FAILED, repository.state.value.error)
        repository.clear()
        runCurrent()
        assertEquals(ChatHistoryState(loading = false), repository.state.value)
        assertTrue(storage.sessions.isEmpty())
    }

    @Test
    fun openReturnsSessionOrNullAndDropsOnlyMissingEntries() = runTest {
        val kept = session(1)
        val damaged = session(2)
        val storage = MemoryChatHistory(listOf(kept, damaged))
        val repository = ChatHistoryRepository(storage, backgroundScope)
        runCurrent()
        assertEquals(kept, repository.open(kept.id))

        storage.damaged += damaged.id
        assertNull(repository.open(damaged.id))
        assertEquals(2, repository.state.value.sessions.size)

        storage.sessions.remove(kept.id)
        assertNull(repository.open(kept.id))
        assertEquals(listOf(damaged.id), repository.state.value.sessions.map { it.id })
    }

    @Test
    fun commandsRunInSubmissionOrderSoClearBeatsEarlierSaveAndLaterOpen() = runTest {
        val storage = MemoryChatHistory()
        val repository = ChatHistoryRepository(storage, backgroundScope)
        val saved = session(1)
        repository.save(saved)
        repository.clear()
        val opened = async { repository.open(saved.id) }
        runCurrent()
        assertNull(opened.await())
        assertTrue(storage.sessions.isEmpty())
        assertTrue(repository.state.value.sessions.isEmpty())
    }

    @Test
    fun deleteAndClearFailuresAreReportedAndClearShowsRemainingFiles() = runTest {
        val first = session(1)
        val second = session(2)
        val storage = MemoryChatHistory(listOf(first, second))
        val repository = ChatHistoryRepository(storage, backgroundScope)
        runCurrent()
        storage.failDelete = true
        repository.delete(first.id)
        runCurrent()
        assertEquals(ChatHistoryError.DELETE_FAILED, repository.state.value.error)
        assertEquals(2, repository.state.value.sessions.size)
        storage.failDelete = false
        repository.delete(first.id)
        runCurrent()
        assertNull(repository.state.value.error)
        assertEquals(listOf(second.id), repository.state.value.sessions.map { it.id })

        storage.failClearAfterFirst = true
        storage.sessions[first.id] = first
        repository.clear()
        runCurrent()
        assertEquals(ChatHistoryError.CLEAR_FAILED, repository.state.value.error)
        assertEquals(1, repository.state.value.sessions.size)
    }

    private fun session(updated: Long) = SavedChatSession(
        id = UUID.randomUUID().toString(), title = "q$updated", updatedAtEpochMs = updated,
        messages = listOf(SavedChatMessage("user", "q$updated"), SavedChatMessage("assistant", "a")),
    )

    private class MemoryChatHistory(initial: List<SavedChatSession> = emptyList()) : ChatHistoryStorage {
        val sessions = linkedMapOf<String, SavedChatSession>().apply { initial.forEach { put(it.id, it) } }
        val damaged = mutableSetOf<String>()
        var failList = false
        var failWrite = false
        var failDelete = false
        var failClearAfterFirst = false

        override fun list(): ChatHistoryListing {
            if (failList) throw IOException("list")
            return ChatHistoryListing(sessions.values.map { it.summary() }.sortedByDescending { it.updatedAtEpochMs }, 0)
        }

        override fun read(id: String): SavedChatSession? {
            if (id in damaged) throw IOException("damaged")
            return sessions[id]
        }

        override fun write(session: SavedChatSession) {
            if (failWrite) throw IOException("write")
            sessions[session.id] = session
        }

        override fun delete(id: String) {
            if (failDelete) throw IOException("delete")
            sessions.remove(id)
        }

        override fun clear() {
            failList = false
            if (failClearAfterFirst) {
                sessions.remove(sessions.keys.first())
                throw IOException("clear")
            }
            sessions.clear()
        }
    }
}
