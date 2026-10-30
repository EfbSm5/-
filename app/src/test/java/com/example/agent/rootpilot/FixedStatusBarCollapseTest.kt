package com.example.agent.rootpilot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class FixedStatusBarCollapseTest {
    private class Child(bytes: ByteArray = byteArrayOf(), private val code: Int = 0,
        var alive: Boolean = false, private val stubborn: Boolean = false) : Process() {
        var destroys = 0; var forces = 0; var inputClosed = false; var outputClosed = false
        private val input = object : ByteArrayInputStream(bytes) { override fun close() { inputClosed = true; super.close() } }
        private val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = output
        override fun waitFor(): Int = code
        override fun exitValue(): Int { check(!alive); return code }
        override fun isAlive() = alive
        override fun destroy() { destroys++; if (!stubborn) alive = false }
        override fun destroyForcibly(): Process { forces++; if (!stubborn) alive = false; return this }
    }
    @Test fun fixedApplicationSuCommandOnceAndNoRawOutputRetained() = runBlocking {
        val child = Child("discarded".toByteArray()); val commands = mutableListOf<List<String>>()
        val runner = FixedStatusBarCollapse { commands += it; child }
        runner.run()
        assertEquals(listOf(listOf("su", "-c", "cmd statusbar collapse")), commands)
        assertEquals("SUCCESS", runner.result.outcome); assertEquals(9, runner.result.outputBytes)
        assertEquals(true, runner.result.exitConfirmed); assertTrue(runner.result.terminationRequested)
        assertTrue(child.inputClosed && child.outputClosed)
        assertThrows(IllegalStateException::class.java) { runBlocking { runner.run() } }
        assertEquals(1, commands.size)
    }
    @Test fun startupNonzeroAndOutputOverflowNeverSucceedOrRetry() = runBlocking {
        var attempts = 0
        val failed = FixedStatusBarCollapse { attempts++; throw java.io.IOException("not exported") }
        failed.run(); assertEquals("START_FAILED", failed.result.outcome); assertEquals(1, attempts)
        assertNull(failed.result.exitConfirmed); assertFalse(failed.result.terminationRequested)
        val nonzero = FixedStatusBarCollapse { Child(code = 7) }
        nonzero.run(); assertEquals("NONZERO", nonzero.result.outcome); assertEquals(7, nonzero.result.exitCode)
        val child = Child(ByteArray(2000), alive = true)
        val overflow = FixedStatusBarCollapse { child }
        overflow.run(); assertEquals("OUTPUT_LIMIT", overflow.result.outcome); assertEquals(1025, overflow.result.outputBytes)
        assertTrue(child.destroys > 0); assertEquals(true, overflow.result.exitConfirmed)
        val boundary = FixedStatusBarCollapse { Child(ByteArray(1024)) }
        boundary.run(); assertEquals("SUCCESS", boundary.result.outcome)
    }
    @Test fun sharedCallerTimeoutTerminatesChildWithoutPretendingExitConfirmed() = runBlocking {
        val child = Child(alive = true, stubborn = true)
        val runner = FixedStatusBarCollapse { child }
        try { withTimeout(100) { runner.run() }; fail("timeout required") }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertEquals("TIMED_OUT", runner.result.outcome); assertEquals(1, runner.result.attempts)
        assertTrue(runner.result.terminationRequested); assertEquals(false, runner.result.exitConfirmed)
        assertTrue(child.destroys > 0 && child.forces > 0 && child.inputClosed && child.outputClosed)
    }
    @Test fun callerCancellationDestroysAndClosesChildAndCannotTurnIntoLateSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>(); val child = Child(alive = true)
        val runner = FixedStatusBarCollapse { started.complete(Unit); child }
        val job = launch { runner.run() }
        started.await(); job.cancelAndJoin()
        assertEquals("CANCELLED", runner.result.outcome); assertEquals(true, runner.result.exitConfirmed)
        assertTrue(child.destroys > 0 && child.inputClosed && child.outputClosed)
        child.alive = false
        assertEquals("CANCELLED", runner.result.outcome)
    }
}
