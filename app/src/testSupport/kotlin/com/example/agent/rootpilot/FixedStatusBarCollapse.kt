package com.example.agent.rootpilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Test-only fixed command. The caller owns the original dispatch deadline; no retry/deadline extension. */
internal class FixedStatusBarCollapse(private val start: (List<String>) -> Process = {
    ProcessBuilder(it).redirectErrorStream(true).start()
}) {
    data class Result(val attempts: Int, val outcome: String, val exitCode: Int?, val outputBytes: Int,
        val terminationRequested: Boolean = false, val exitConfirmed: Boolean? = null)
    var result = Result(0, "NOT_STARTED", null, 0)
        private set

    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        check(result.attempts == 0)
        result = Result(1, "STARTING", null, 0)
        var process: Process? = null
        try {
            currentCoroutineContext().ensureActive()
            process = try { start(listOf("su", "-c", "cmd statusbar collapse")) }
            catch (_: Exception) { result = result.copy(outcome = "START_FAILED"); return@withContext }
            val input = process.inputStream
            val buffer = ByteArray(256)
            var count = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                while (input.available() > 0) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size, input.available(), 1025 - count))
                    if (read <= 0) break
                    count += read
                    result = result.copy(outputBytes = count)
                    if (count > 1024) { result = result.copy(outcome = "OUTPUT_LIMIT"); return@withContext }
                }
                if (!process.isAlive) {
                    currentCoroutineContext().ensureActive()
                    if (input.available() > 0) continue
                    val code = process.exitValue()
                    result = result.copy(outcome = if (code == 0) "SUCCESS" else "NONZERO", exitCode = code)
                    return@withContext
                }
                // Process pipe/exit polling only, never UI/focus polling or a second attempt.
                delay(10)
            }
        } catch (error: CancellationException) {
            result = result.copy(outcome = if (error is TimeoutCancellationException) "TIMED_OUT" else "CANCELLED")
            throw error
        } catch (_: Exception) {
            result = result.copy(outcome = "IO_ERROR")
        } finally {
            process?.let { child ->
                result = result.copy(terminationRequested = true)
                runCatching { child.destroy() }
                runCatching { if (child.isAlive) child.destroyForcibly() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
                result = result.copy(exitConfirmed = runCatching { !child.isAlive }.getOrNull())
            }
        }
    }
}
