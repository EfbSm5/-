package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.information.DeviceInfoResult
import com.example.agent.rootpilot.information.DeviceInfoSource
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only the fixed activity diagnostic is accepted; callers cannot supply commands or paths. */
internal class RootDeviceInfoProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 3_000,
    private val maxOutputBytes: Int = 512 * 1024,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it).redirectErrorStream(true).start() },
) {
    init {
        require(timeoutMillis > 0)
        require(maxOutputBytes in 1..1024 * 1024)
    }

    private val lock = Any()
    private val operations = mutableSetOf<Job>()

    fun cancel() {
        synchronized(lock) { operations.toList() }.forEach { it.cancel(CancellationException("Device information cancelled")) }
    }

    suspend fun activityStack(expected: ScreenObservation, session: DisplaySession? = null): DeviceInfoResult = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(lock) { operations.add(job) }
        val startedAt = clock()
        try {
            currentCoroutineContext().ensureActive()
            if (!ActivityStackParser.acceptsTarget(expected, session)) {
                return@coroutineScope DeviceInfoResult(DeviceInfoSource.ACTIVITY_DUMP, startedAt, clock(),
                    unavailable = DeviceInfoUnavailable.TARGET_NOT_READY)
            }
            withContext(dispatcher) {
                val collected = withTimeoutOrNull(timeoutMillis) {
                    var process: Process? = null
                    try {
                        currentCoroutineContext().ensureActive()
                        process = start("exec dumpsys activity activities")
                        currentCoroutineContext().ensureActive()
                        process.outputStream.close()
                        val input = process.inputStream
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (input.available() > 0) {
                                val count = input.read(buffer, 0, minOf(input.available(), buffer.size))
                                if (count < 0) break
                                if (count > maxOutputBytes - output.size()) {
                                    return@withTimeoutOrNull null to DeviceInfoUnavailable.OUTPUT_LIMIT
                                }
                                output.write(buffer, 0, count)
                            } else if (!process.isAlive) {
                                if (input.available() > 0) continue
                                if (process.exitValue() != 0) return@withTimeoutOrNull null to DeviceInfoUnavailable.COMMAND_FAILED
                                val stack = ActivityStackParser.parse(output.toString("UTF-8"), expected, session)
                                return@withTimeoutOrNull stack to DeviceInfoUnavailable.INVALID_FORMAT
                            } else {
                                delay(10)
                            }
                        }
                        null to DeviceInfoUnavailable.COMMAND_FAILED
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null to DeviceInfoUnavailable.COMMAND_FAILED
                    } finally {
                        process?.let { child ->
                            runCatching { child.destroyForcibly() }
                            runCatching { child.inputStream.close() }
                            runCatching { child.errorStream.close() }
                            runCatching { child.outputStream.close() }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val parsed = collected?.first
                if (parsed == null) {
                    DeviceInfoResult(DeviceInfoSource.ACTIVITY_DUMP, startedAt, clock(),
                        unavailable = collected?.second ?: DeviceInfoUnavailable.TIMEOUT)
                } else {
                    DeviceInfoResult(DeviceInfoSource.ACTIVITY_DUMP, startedAt, clock(), parsed.data, truncated = parsed.truncated)
                }
            }
        } finally {
            synchronized(lock) { operations.remove(job) }
        }
    }
}
