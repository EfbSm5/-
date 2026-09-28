package com.example.agent.rootpilot.root

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootScreenObserverTest {
    private class FakeProcess(
        text: String = "",
        private val running: Boolean = false,
        private val code: Int = 0,
    ) : Process() {
        var destroyed = false
        var inputClosed = false
        var errorClosed = false
        var outputClosed = false
        private val input = object : ByteArrayInputStream(text.toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
        private val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true; super.close() }
        }
        private val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun isAlive() = running && !destroyed
        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException()
            return code
        }
        override fun waitFor() = exitValue()
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
        fun assertCleaned() {
            assertTrue(destroyed)
            assertTrue(inputClosed)
            assertTrue(errorClosed)
            assertTrue(outputClosed)
        }
    }

    private fun TestScope.observer(limit: Int = 1024, start: (String) -> Process) = RootScreenObserver(
        StandardTestDispatcher(testScheduler), 100, limit, { 987L }, start,
    )

    @Test fun onlyFixedCommandsAreUsedAndAllStreamsAreClosed() = runTest {
        val commands = mutableListOf<String>()
        val processes = mutableListOf<FakeProcess>()
        val observer = observer { command ->
            commands += command
            FakeProcess(when (commands.size) {
                1 -> "mResumedActivity: ActivityRecord{abcd u0 com.example.app/.Main t1}"
                2 -> ""
                3 -> "mCurrentFocus=Window{abcd u0 private title}"
                else -> "Current Input Method Manager state:\n  mInputShown=false"
            }).also { processes += it }
        }
        val result = observer.observe()
        assertEquals(listOf("exec dumpsys activity activities", "exec dumpsys window windows", "exec dumpsys window displays", "exec dumpsys input_method --dump-priority CRITICAL"), commands)
        assertEquals("com.example.app.Main", result.foregroundActivity)
        assertEquals("abcd", result.focusedWindowId)
        assertEquals(false, result.keyboardVisible)
        assertEquals(987L, result.observedAtMillis)
        processes.forEach { it.assertCleaned() }
    }

    @Test fun silentProcessTimesOutAndIsCleanedWithoutStartingMoreCommands() = runTest {
        val process = FakeProcess(running = true)
        var starts = 0
        val result = observer { starts++; process }.observe()
        assertEquals(1, starts)
        assertEquals(100L, testScheduler.currentTime)
        assertNull(result.foregroundActivity)
        assertNull(result.focusedWindowId)
        assertEquals(987L, result.observedAtMillis)
        process.assertCleaned()
    }

    @Test fun callerCancellationCleansProcessAndPropagatesCancellation() = runTest {
        val process = FakeProcess(running = true)
        val job = async { observer { process }.observe() }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        process.assertCleaned()
    }

    @Test fun explicitCancelCancelsActiveOperationsButAllowsReuse() = runTest {
        val processes = mutableListOf<FakeProcess>()
        val observer = observer { FakeProcess(running = true).also { processes += it } }
        val first = async { observer.observe() }
        val second = async { observer.observe() }
        runCurrent()
        observer.cancel()
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        processes.forEach { it.assertCleaned() }
        observer.cancel()
        assertEquals(987L, observer.observe().observedAtMillis)
        assertEquals(3, processes.size)
        processes.last().assertCleaned()
    }

    @Test fun cancellationDuringStartStillCleansReturnedProcess() = runTest {
        val process = FakeProcess(running = true)
        lateinit var observer: RootScreenObserver
        observer = observer { observer.cancel(); process }
        val job = async { observer.observe() }
        job.join()
        assertTrue(job.isCancelled)
        process.assertCleaned()
    }

    @Test fun outputOverBudgetIsDiscardedRatherThanParsedAsAPrefix() = runTest {
        val valid = "Current Input Method Manager state:\n  mInputShown=true"
        val processes = mutableListOf<FakeProcess>()
        val result = observer(limit = valid.length) {
            FakeProcess(valid + "\nprivate title").also { processes += it }
        }.observe()
        assertNull(result.keyboardVisible)
        processes.forEach { it.assertCleaned() }
    }

    @Test fun outputExactlyAtBudgetIsAccepted() = runTest {
        val valid = "Current Input Method Manager state:\n  mInputShown=true"
        assertEquals(true, observer(limit = valid.length) { FakeProcess(valid) }.observe().keyboardVisible)
    }

    @Test fun failedCommandsAndStartErrorsNeverExposeSensitiveText() = runTest {
        val process = FakeProcess("mInputShown=true\nprivate title", code = 1)
        val failed = observer { process }.observe()
        assertNull(failed.keyboardVisible)
        assertFalse(failed.toString().contains("private title"))
        process.assertCleaned()
        val unavailable = observer { throw IOException("private title") }.observe()
        assertNull(unavailable.keyboardVisible)
        assertFalse(unavailable.toString().contains("private title"))
    }

    @Test fun cancellationExceptionIsNotConvertedToUnknown() = runTest {
        val job = async { observer { throw CancellationException("cancelled") }.observe() }
        job.join()
        assertTrue(job.isCancelled)
    }
}
