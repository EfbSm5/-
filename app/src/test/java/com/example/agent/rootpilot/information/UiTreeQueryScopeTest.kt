package com.example.agent.rootpilot.information

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class UiTreeQueryScopeTest {
    @Test fun virtualQueryRestoresExactFlags() = runBlocking {
        val fixture = Fixture()
        assertEquals("result", fixture.scope.query(true) {
            assertEquals(66, fixture.flags)
            "result"
        })
        assertEquals(64, fixture.flags)
        assertEquals(listOf(66, 64), fixture.writes)
    }

    @Test fun mainQueryNeverChangesFlags() = runBlocking {
        val fixture = Fixture()
        fixture.scope.query(false) { assertEquals(64, fixture.flags) }
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun alreadyEnabledLayoutFlagIsNotRewritten() = runBlocking {
        val fixture = Fixture(initialFlags = 66)
        fixture.scope.query(true) { assertEquals(66, fixture.flags) }
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun collectionFailureRestoresBeforePropagating() = runBlocking {
        val fixture = Fixture()
        val error = runCatching { fixture.scope.query(true) { error("collection_failed") } }.exceptionOrNull()
        assertEquals("collection_failed", error?.message)
        assertEquals(64, fixture.flags)
        fixture.scope.query(false) { assertEquals(64, fixture.flags) }
    }

    @Test fun cancellationRestoresBeforeReleasingLock() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val query = launch {
            fixture.scope.query(true) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        query.cancelAndJoin()
        assertEquals(64, fixture.flags)
        fixture.scope.query(false) { assertEquals(64, fixture.flags) }
    }

    @Test fun timeoutStillRestoresFlags() = runBlocking {
        val fixture = Fixture()
        assertNull(withTimeoutOrNull(50) { fixture.scope.query(true) { awaitCancellation() } })
        assertEquals(64, fixture.flags)
    }

    @Test fun failedApplyDoesNotCollectAndStillRestores() = runBlocking {
        val fixture = Fixture().apply { failApply = true }
        var collected = false
        assertTrue(runCatching { fixture.scope.query(true) { collected = true } }.isFailure)
        assertFalse(collected)
        assertEquals(listOf(66, 64), fixture.writes)
        fixture.scope.query(false) { assertEquals(64, fixture.flags) }
    }

    @Test fun unconfirmedRestorationDisablesLaterMainAndVirtualReads() = runBlocking {
        val fixture = Fixture().apply { failRestore = true }
        val error = runCatching { fixture.scope.query(true) { "result" } }.exceptionOrNull()
        assertEquals("ui_tree_layout_flag_restore_failed", error?.message)
        for (virtual in listOf(false, true)) {
            var collected = false
            val next = runCatching { fixture.scope.query(virtual) { collected = true } }.exceptionOrNull()
            assertEquals("ui_tree_scope_unavailable", next?.message)
            assertFalse(collected)
        }
    }

    @Test fun ignoredFlagApplicationCannotReturnCollectedData() = runBlocking {
        val fixture = Fixture().apply { ignoreApply = true }
        var collected = false
        val error = runCatching { fixture.scope.query(true) { collected = true } }.exceptionOrNull()
        assertEquals("ui_tree_layout_flag_unavailable", error?.message)
        assertFalse(collected)
        assertEquals(listOf(66, 64), fixture.writes)
    }

    @Test fun missingRestorationReadbackDoesNotReturnDataOrAllowMainRead() = runBlocking {
        val fixture = Fixture()
        val error = runCatching { fixture.scope.query(true) { fixture.infoAvailable = false; "result" } }.exceptionOrNull()
        assertEquals("ui_tree_layout_flag_restore_failed", error?.message)
        assertEquals(64, fixture.flags)
        fixture.infoAvailable = true
        assertTrue(runCatching { fixture.scope.query(false) { error("must_not_collect") } }.isFailure)
    }

    @Test fun cancellationIsNotSwallowedWhenRestorationAlsoFails() = runBlocking {
        val fixture = Fixture().apply { failRestore = true }
        val error = runCatching {
            fixture.scope.query(true) { throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals("ui_tree_layout_flag_restore_failed", error?.suppressed?.single()?.message)
        assertTrue(runCatching { fixture.scope.query(false) { error("must_not_collect") } }.isFailure)
    }

    @Test fun disconnectDoesNotWriteToAnotherConnection() = runBlocking {
        val fixture = Fixture()
        val error = runCatching { fixture.scope.query(true) { fixture.connected = false } }.exceptionOrNull()
        assertEquals("ui_tree_layout_flag_restore_failed", error?.message)
        assertEquals(listOf(66), fixture.writes)
        fixture.connected = true
        assertTrue(runCatching { fixture.scope.query(false) { error("must_not_collect") } }.isFailure)
    }

    @Test fun missingServiceInfoDoesNotChangeFlagsOrCollect() = runBlocking {
        val fixture = Fixture().apply { infoAvailable = false }
        var collected = false
        val error = runCatching { fixture.scope.query(true) { collected = true } }.exceptionOrNull()
        assertEquals("ui_tree_service_info_unavailable", error?.message)
        assertFalse(collected)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test fun mainReadWaitsForVirtualRestorationAndCancelledWaiterDoesNotWrite() = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val virtual = launch { fixture.scope.query(true) { entered.complete(Unit); release.await() } }
        entered.await()
        var mainEntered = false
        val main = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.scope.query(false) { mainEntered = true; assertEquals(64, fixture.flags) }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.scope.query(true) { error("cancelled_waiter_collected") }
        }
        cancelled.cancelAndJoin()
        assertFalse(mainEntered)
        assertEquals(listOf(66), fixture.writes)
        release.complete(Unit)
        virtual.join()
        main.await()
        assertTrue(mainEntered)
        assertEquals(listOf(66, 64), fixture.writes)
    }

    private class Fixture(initialFlags: Int = 64) {
        var flags = initialFlags
        var connected = true
        var infoAvailable = true
        var failApply = false
        var ignoreApply = false
        var failRestore = false
        val writes = mutableListOf<Int>()
        val scope = UiTreeQueryScope(2,
            readFlags = { flags.takeIf { infoAvailable } },
            writeFlags = { value ->
                writes += value
                if (value == 66 && failApply || value == 64 && failRestore) error("framework_failure")
                if (value != 66 || !ignoreApply) flags = value
            },
            isConnected = { connected },
        )
    }
}
