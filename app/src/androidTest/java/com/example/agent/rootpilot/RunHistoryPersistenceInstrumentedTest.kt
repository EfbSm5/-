package com.example.agent.rootpilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.history.RunHistoryRepository
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.history.RunHistoryStore
import com.example.agent.rootpilot.log.TraceReason
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run each phase in a separate instrumentation process; never modify the user's history. */
@RunWith(AndroidJUnit4::class)
class RunHistoryPersistenceInstrumentedTest {
    @Test fun crossProcessPersistence() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("historyPhase")
        assumeTrue(phase in listOf("save", "read_clear", "verify_clear"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "history_acceptance_fixture/runs.json")
        val store = RunHistoryStore(file)
        if (phase == "save") assertFalse("Existing fixture must not be overwritten", file.exists())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RunHistoryRepository(store, scope)
            when (phase) {
                "save" -> {
                    repository.begin(COMPLETED_ID, 1_700_000_000_000L)
                    repository.finish(COMPLETED_ID, RunHistoryStatus.FAILED, 1234, TraceReason.MODEL_FAILED)
                    repository.begin(INTERRUPTED_ID, 1_700_000_000_100L)
                    withTimeout(5000) { repository.state.first { it.records.size == 2 } }
                }
                "read_clear" -> {
                    val loaded = withTimeout(5000) { repository.state.first { it.records.size == 2 } }
                    assertEquals(RunHistoryStatus.INTERRUPTED, loaded.records[0].status)
                    assertEquals(INTERRUPTED_ID, loaded.records[0].id)
                    assertEquals(RunHistoryStatus.FAILED, loaded.records[1].status)
                    assertEquals(TraceReason.MODEL_FAILED, loaded.records[1].reason)
                    assertEquals(1234L, loaded.records[1].durationMs)
                    repository.clear()
                    withTimeout(5000) { repository.state.first { it.records.isEmpty() && it.error == null } }
                    assertFalse(file.exists())
                }
                "verify_clear" -> {
                    assertFalse(file.exists())
                    assertTrue(store.read().isEmpty())
                }
            }
        } finally {
            // Joining the worker completes synchronous file IO already in progress before phase exit.
            val job = scope.coroutineContext[kotlinx.coroutines.Job]!!
            scope.cancel()
            job.join()
        }
        if (phase == "save") assertEquals(2, store.read().size)
    }

    private companion object {
        const val COMPLETED_ID = "00000000-0000-0000-0000-000000000101"
        const val INTERRUPTED_ID = "00000000-0000-0000-0000-000000000102"
    }
}
