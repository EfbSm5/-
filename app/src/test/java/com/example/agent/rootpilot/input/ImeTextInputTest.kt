package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImeTextInputTest {
    private class Store : ImeRestoreStore {
        var value: String? = null
        var writable = true
        override fun read() = value
        override fun save(id: String): Boolean { if (!writable) return false; value = id; return true }
        override fun clear(): Boolean { value = null; return true }
    }
    private class Environment : ImeEnvironment {
        override val ownId = "own/.Ime"
        var current: String? = "original/.Ime"
        val enabled = mutableSetOf(ownId, "original/.Ime", "user/.Ime")
        val selections = mutableListOf<String>()
        var failRestore = false
        var afterSelect: suspend (String) -> Unit = {}
        var onCommit: suspend (String) -> RootExecutionResult = { RootExecutionResult.Success() }
        override fun currentId() = current
        override fun isEnabled(id: String) = id in enabled
        override suspend fun select(id: String): RootExecutionResult {
            selections += id
            if (failRestore && id == "original/.Ime") return RootExecutionResult.Failure("restore failed")
            current = id
            afterSelect(id)
            return RootExecutionResult.Success()
        }
        override suspend fun commit(text: String, confirm: suspend (String) -> Boolean) =
            if (confirm("target.app")) onCommit(text) else RootExecutionResult.Failure("rejected")
    }

    @Test fun savesBeforeSwitchAndRestoresAfterLiteralCommit() = runTest {
        val env = Environment(); val store = Store()
        env.onCommit = {
            assertEquals("中文 😀", it)
            assertEquals(env.ownId, env.current)
            assertEquals("original/.Ime", store.value)
            RootExecutionResult.Success()
        }
        assertTrue(ImeTextInput(env, store).type("中文 😀") is RootExecutionResult.Success)
        assertEquals(listOf(env.ownId, "original/.Ime"), env.selections)
        assertEquals("original/.Ime", env.current)
        assertNull(store.value)
    }

    @Test fun failedCommitAndCancellationStillRestore() = runTest {
        val env = Environment(); val store = Store(); val input = ImeTextInput(env, store)
        env.onCommit = { RootExecutionResult.Failure("no editor") }
        assertTrue(input.type("输入") is RootExecutionResult.Failure)
        assertEquals("original/.Ime", env.current)
        val ready = CompletableDeferred<Unit>()
        env.onCommit = { ready.complete(Unit); awaitCancellation() }
        val job = async { input.type("输入") }
        ready.await(); job.cancelAndJoin()
        assertEquals("original/.Ime", env.current)
        assertNull(store.value)
    }

    @Test fun neverSwitchesWithoutEnabledImeAndDurableRestoreRecord() = runTest {
        val env = Environment(); val store = Store(); val input = ImeTextInput(env, store)
        store.writable = false
        assertTrue(input.type("输入") is RootExecutionResult.Failure)
        assertTrue(env.selections.isEmpty())
        store.writable = true; env.enabled.remove(env.ownId)
        assertTrue(input.type("输入") is RootExecutionResult.Failure)
        assertTrue(env.selections.isEmpty())
        assertTrue(input.type("\u0000") is RootExecutionResult.Failure)
    }

    @Test fun restorationFailureRetainsRecordAndDoesNotRepeatCommit() = runTest {
        val env = Environment(); val store = Store(); var commits = 0
        env.failRestore = true
        env.onCommit = { commits++; RootExecutionResult.Success() }
        val input = ImeTextInput(env, store)
        assertTrue(input.type("一次") is RootExecutionResult.Failure)
        assertEquals("original/.Ime", store.value)
        assertTrue(input.type("不要重放") is RootExecutionResult.Failure)
        assertEquals(1, commits)
        env.failRestore = false
        assertTrue(ImeTextInput(env, store).recover() is RootExecutionResult.Success)
        assertEquals("original/.Ime", env.current)
        assertNull(store.value)
    }

    @Test fun recoveryDoesNotOverrideUserSelectedIme() = runTest {
        val env = Environment(); val store = Store()
        store.value = "original/.Ime"; env.current = "user/.Ime"
        assertTrue(ImeTextInput(env, store).recover() is RootExecutionResult.Success)
        assertTrue(env.selections.isEmpty())
        assertEquals("user/.Ime", env.current)
        assertNull(store.value)
    }

    @Test fun missingOriginalImeRequiresManualRecovery() = runTest {
        val env = Environment(); val store = Store()
        store.value = "missing/.Ime"; env.current = env.ownId
        assertTrue(ImeTextInput(env, store).recover() is RootExecutionResult.Failure)
        assertEquals("missing/.Ime", store.value)
        assertTrue(env.selections.isEmpty())
    }

    @Test fun cancellingSwitchRestoresAndUnlocksSession() = runTest {
        val env = Environment(); val store = Store(); val ready = CompletableDeferred<Unit>()
        env.afterSelect = { if (it == env.ownId) { ready.complete(Unit); awaitCancellation() } }
        val input = ImeTextInput(env, store)
        val job = async { input.type("不应输入") }
        ready.await(); job.cancelAndJoin()
        assertEquals("original/.Ime", env.current)
        assertNull(store.value)
        assertTrue(input.recover() is RootExecutionResult.Success)
    }

    @Test fun rejectingApprovalRestoresWithoutCommit() = runTest {
        val env = Environment(); val store = Store(); var committed = false
        env.onCommit = { committed = true; RootExecutionResult.Success() }
        assertTrue(ImeTextInput(env, store).type("不应输入") { false } is RootExecutionResult.Failure)
        assertFalse(committed)
        assertEquals("original/.Ime", env.current)
        assertNull(store.value)
    }
}
