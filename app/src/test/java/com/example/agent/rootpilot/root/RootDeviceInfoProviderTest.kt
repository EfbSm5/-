package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootDeviceInfoProviderTest {
    private val expected = ScreenObservation("com.test.app", "com.test.app.Top", "com.test.app", "abcd", false, 1)
    private val valid = ActivityStackParserTest().dump()
    private val owned = DisplaySession(42, "00000000-0000-0000-0000-000000000042")
    private val virtual = expected.copy(displayId = owned.displayId, sessionId = owned.sessionId)

    private class FakeProcess(text: String = "", private val running: Boolean = false, private val code: Int = 0) : Process() {
        var destroyed = false
        var inputClosed = false
        var errorClosed = false
        var outputClosed = false
        private val input = object : ByteArrayInputStream(text.toByteArray()) { override fun close() { inputClosed = true } }
        private val error = object : ByteArrayInputStream(byteArrayOf()) { override fun close() { errorClosed = true } }
        private val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun isAlive() = running && !destroyed
        override fun exitValue() = if (isAlive) throw IllegalThreadStateException() else code
        override fun waitFor() = exitValue()
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun assertCleaned() { assertTrue(destroyed && inputClosed && errorClosed && outputClosed) }
    }

    private fun TestScope.provider(limit: Int = 4096, start: (String) -> Process) = RootDeviceInfoProvider(
        StandardTestDispatcher(testScheduler), 100, limit, { 987L }, start,
    )

    @Test fun fixedCommandCollectsCurrentTaskAndAlwaysCleansProcess() = runTest {
        val process = FakeProcess(valid)
        val commands = mutableListOf<String>()
        val result = provider { commands += it; process }.activityStack(expected)
        assertEquals(listOf("exec dumpsys activity activities"), commands)
        assertNotNull(result.data)
        assertNull(result.unavailable)
        assertEquals(987L, result.startedAtMillis)
        process.assertCleaned()
    }

    @Test fun timeoutUsesCoroutineOutcomeRatherThanSampleClock() = runTest {
        val process = FakeProcess(running = true)
        val result = provider { process }.activityStack(expected)
        assertEquals(DeviceInfoUnavailable.TIMEOUT, result.unavailable)
        assertEquals(100L, testScheduler.currentTime)
        process.assertCleaned()
    }

    @Test fun oversizeMalformedAndFailedCommandsUseFixedReasons() = runTest {
        val tooBig = FakeProcess(valid)
        assertEquals(DeviceInfoUnavailable.OUTPUT_LIMIT, provider(10) { tooBig }.activityStack(expected).unavailable)
        tooBig.assertCleaned()
        val malformed = FakeProcess("private-content")
        val result = provider { malformed }.activityStack(expected)
        assertEquals(DeviceInfoUnavailable.INVALID_FORMAT, result.unavailable)
        assertFalse(result.toString().contains("private-content"))
        malformed.assertCleaned()
        val failed = FakeProcess(valid, code = 1)
        assertEquals(DeviceInfoUnavailable.COMMAND_FAILED, provider { failed }.activityStack(expected).unavailable)
        failed.assertCleaned()
        assertEquals(DeviceInfoUnavailable.COMMAND_FAILED, provider { throw IOException("private-content") }.activityStack(expected).unavailable)
    }

    @Test fun cancelOwnsCurrentQueriesAndAllowsFutureQueries() = runTest {
        val processes = mutableListOf<FakeProcess>()
        val provider = provider { FakeProcess(running = true).also { processes += it } }
        val first = async { provider.activityStack(expected) }
        val second = async { provider.activityStack(expected) }
        runCurrent()
        provider.cancel()
        first.join(); second.join()
        assertTrue(first.isCancelled && second.isCancelled)
        processes.forEach { it.assertCleaned() }
        assertEquals(DeviceInfoUnavailable.TIMEOUT, provider.activityStack(expected).unavailable)
        processes.last().assertCleaned()
    }

    @Test fun cancellationRacingWithStartStillCleansReturnedProcess() = runTest {
        val process = FakeProcess(running = true)
        lateinit var query: RootDeviceInfoProvider
        query = provider { query.cancel(); process }
        val job = async { query.activityStack(expected) }
        job.join()
        assertTrue(job.isCancelled)
        process.assertCleaned()
    }

    @Test fun ownedQueryUsesFixedCommandAndOnlyItsDisplay() = runTest {
        val process = FakeProcess(valid + "\n" + ActivityStackParserTest().ownedDump().replace(".Base", ".OwnedBase"))
        val commands = mutableListOf<String>()
        val result = provider { commands += it; process }.activityStack(virtual, owned)
        assertEquals(listOf("exec dumpsys activity activities"), commands)
        assertNull(result.unavailable)
        assertTrue(result.data.toString().contains("\"display_id\":42"))
        assertTrue(result.data.toString().contains("OwnedBase"))
        assertFalse(result.data.toString().contains("private"))
        process.assertCleaned()
    }

    @Test fun mismatchedOrUnavailableOwnedTargetDoesNotStartDiagnostic() = runTest {
        var starts = 0
        val query = provider { starts++; FakeProcess(valid) }
        for (target in listOf(
            virtual.copy(displayId = 43),
            virtual.copy(sessionId = "00000000-0000-0000-0000-000000000043"),
            virtual.copy(foregroundActivity = null),
            virtual.copy(focusedPackage = "com.other.app"),
            virtual.copy(focusedWindowId = null),
            virtual.copy(keyboardVisible = null),
            expected,
        )) {
            val result = query.activityStack(target, owned)
            assertEquals(DeviceInfoUnavailable.TARGET_NOT_READY, result.unavailable)
            assertNull(result.data)
        }
        assertEquals(DeviceInfoUnavailable.TARGET_NOT_READY, query.activityStack(virtual).unavailable)
        assertEquals(0, starts)
    }

    @Test fun missingOwnedDisplayDoesNotUseMatchingMainTask() = runTest {
        val process = FakeProcess(valid)
        val result = provider { process }.activityStack(virtual, owned)
        assertEquals(DeviceInfoUnavailable.INVALID_FORMAT, result.unavailable)
        assertNull(result.data)
        process.assertCleaned()
    }

    @Test fun ownedTimeoutAndOutputLimitDiscardPartialDumpAndCleanProcess() = runTest {
        val partial = FakeProcess(ActivityStackParserTest().ownedDump(), running = true)
        val timedOut = provider { partial }.activityStack(virtual, owned)
        assertEquals(DeviceInfoUnavailable.TIMEOUT, timedOut.unavailable)
        assertNull(timedOut.data)
        partial.assertCleaned()
        val tooBig = FakeProcess(ActivityStackParserTest().ownedDump())
        val limited = provider(10) { tooBig }.activityStack(virtual, owned)
        assertEquals(DeviceInfoUnavailable.OUTPUT_LIMIT, limited.unavailable)
        assertNull(limited.data)
        tooBig.assertCleaned()
    }

    @Test fun cancellationCleansOwnedQueriesAndDoesNotCancelFutureMainQueries() = runTest {
        val processes = mutableListOf<FakeProcess>()
        val query = provider { FakeProcess(ActivityStackParserTest().ownedDump(), running = true).also { processes += it } }
        val first = async { query.activityStack(virtual, owned) }
        val second = async { query.activityStack(virtual, owned) }
        runCurrent()
        query.cancel()
        first.join(); second.join()
        assertTrue(first.isCancelled && second.isCancelled)
        processes.forEach { it.assertCleaned() }
        assertEquals(DeviceInfoUnavailable.TIMEOUT, query.activityStack(expected).unavailable)
        processes.last().assertCleaned()
    }

    @Test fun ownedCancellationRacingWithStartDiscardsResultAndCleansProcess() = runTest {
        val process = FakeProcess(ActivityStackParserTest().ownedDump())
        lateinit var query: RootDeviceInfoProvider
        query = provider { query.cancel(); process }
        val job = async { query.activityStack(virtual, owned) }
        job.join()
        assertTrue(job.isCancelled)
        process.assertCleaned()
    }
}
