package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotKey
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface RootExecutionResult {
    data class Success(val output: String = "") : RootExecutionResult

    data class Failure(val message: String) : RootExecutionResult
}

sealed interface RootScreenshotResult {
    data class Success(val pngBytes: ByteArray) : RootScreenshotResult

    data class Failure(val message: String) : RootScreenshotResult
}

internal object RootCommandBuilder {
    fun openApp(app: RootPilotApp): String = when (app) {
        RootPilotApp.SETTINGS ->
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.android.settings"
    }
}

interface RootExecutor {
    suspend fun checkRoot(): RootExecutionResult

    suspend fun captureScreen(): RootScreenshotResult

    suspend fun execute(action: ExecutableRootAction): RootExecutionResult

    fun cancel()
}

class SuRootExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RootExecutor {
    private val processLock = Any()
    private var activeProcess: Process? = null
    private var cancellationGeneration = 0L

    override suspend fun checkRoot(): RootExecutionResult {
        return when (val result = runTextCommand("id")) {
            is RootExecutionResult.Success -> if (ROOT_ID_PATTERN.containsMatchIn(result.output)) {
                result
            } else {
                RootExecutionResult.Failure("su 可用，但没有获得 uid=0")
            }

            is RootExecutionResult.Failure -> result
        }
    }

    override suspend fun captureScreen(): RootScreenshotResult = when (
        val result = runBinaryCommand("screencap -p")
    ) {
        is BinaryCommandResult.Success -> RootScreenshotResult.Success(result.bytes)
        is BinaryCommandResult.Failure -> RootScreenshotResult.Failure(result.message)
    }

    override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = when (action) {
        is ExecutableRootAction.Tap -> if (action.x >= 0 && action.y >= 0) {
            runTextCommand("input tap ${action.x} ${action.y}")
        } else {
            RootExecutionResult.Failure("tap 坐标不合法")
        }

        is ExecutableRootAction.Swipe -> if (
            action.x1 >= 0 && action.y1 >= 0 && action.x2 >= 0 && action.y2 >= 0 &&
                action.durationMillis in 100..2_000
        ) {
            runTextCommand(
            "input swipe ${action.x1} ${action.y1} " +
                "${action.x2} ${action.y2} ${action.durationMillis}",
            )
        } else {
            RootExecutionResult.Failure("swipe 参数不合法")
        }

        is ExecutableRootAction.OpenApp -> when (action.app) {
            RootPilotApp.SETTINGS -> runTextCommand(RootCommandBuilder.openApp(action.app))
        }

        is ExecutableRootAction.Type -> if (TYPE_TEXT_PATTERN.matches(action.text)) {
            runTextCommand("input text ${action.text}")
        } else {
            RootExecutionResult.Failure("type 文本包含不安全字符")
        }
        is ExecutableRootAction.Key -> runTextCommand("input keyevent ${action.key.toKeyCode()}")
        is ExecutableRootAction.Wait -> if (action.durationMillis in 300..5_000) {
            delay(action.durationMillis.toLong())
            RootExecutionResult.Success()
        } else {
            RootExecutionResult.Failure("wait 时长不合法")
        }
    }

    override fun cancel() {
        synchronized(processLock) {
            cancellationGeneration++
            activeProcess?.destroyForcibly()
            activeProcess = null
        }
    }

    private suspend fun runTextCommand(command: String): RootExecutionResult = when (
        val result = runProcess(command, readBinary = false)
    ) {
        is BinaryCommandResult.Success -> RootExecutionResult.Success(
            result.bytes.toString(Charsets.UTF_8).trim(),
        )

        is BinaryCommandResult.Failure -> RootExecutionResult.Failure(result.message)
    }

    private suspend fun runBinaryCommand(command: String): BinaryCommandResult =
        runProcess(command, readBinary = true)

    private suspend fun runProcess(command: String, readBinary: Boolean): BinaryCommandResult =
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val operationGeneration = synchronized(processLock) { cancellationGeneration }
            val process = try {
                ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                return@withContext BinaryCommandResult.Failure("无法启动 su")
            }
            currentCoroutineContext().ensureActive()
            val registered = synchronized(processLock) {
                if (operationGeneration == cancellationGeneration) {
                    activeProcess = process
                    true
                } else {
                    false
                }
            }
            if (!registered) {
                process.destroyForcibly()
                return@withContext BinaryCommandResult.Failure("Root 命令已取消")
            }
            try {
                val bytes = process.inputStream.readBytes()
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                }
                if (process.exitValue() != 0) {
                    BinaryCommandResult.Failure(
                        bytes.toString(Charsets.UTF_8).trim().ifEmpty { "Root 命令执行失败" },
                    )
                } else {
                    BinaryCommandResult.Success(if (readBinary) bytes else bytes)
                }
            } catch (error: CancellationException) {
                process.destroyForcibly()
                throw error
            } finally {
                synchronized(processLock) {
                    if (activeProcess === process) {
                        activeProcess = null
                    }
                }
            }
        }

    private fun RootPilotKey.toKeyCode(): String = when (this) {
        RootPilotKey.BACK -> "KEYCODE_BACK"
        RootPilotKey.HOME -> "KEYCODE_HOME"
        RootPilotKey.ENTER -> "KEYCODE_ENTER"
    }

    private sealed interface BinaryCommandResult {
        data class Success(val bytes: ByteArray) : BinaryCommandResult

        data class Failure(val message: String) : BinaryCommandResult
    }

    private companion object {
        val ROOT_ID_PATTERN = Regex("uid=0(?:\\(| )")
        val TYPE_TEXT_PATTERN = Regex("^[A-Za-z0-9._@+\\-]+$")
    }
}
