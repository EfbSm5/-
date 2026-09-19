package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImeCommandRunnerTest {
    private class BlockingProcess(private val block: Boolean) : Process() {
        val waiting = CompletableDeferred<Unit>()
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun exitValue() = 0
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waiting.complete(Unit)
            if (block) CountDownLatch(1).await(timeout, unit)
            return false
        }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun timeoutTerminatesSwitchProcess() = runTest {
        val process = BlockingProcess(false)
        assertTrue(ImeCommandRunner(start = { process }).select("test.ime/.Service") is RootExecutionResult.Failure)
        assertTrue(process.destroyed)
    }

    @Test fun cancellationInterruptsBlockedSwitchAndTerminatesProcess() = runTest {
        val process = BlockingProcess(true)
        val job = async { ImeCommandRunner(timeoutMillis = 60_000, start = { process }).select("test.ime/.Service") }
        process.waiting.await()
        job.cancelAndJoin()
        assertTrue(process.destroyed)
    }
}
