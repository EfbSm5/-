package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootCommandBuilder
import com.example.agent.rootpilot.root.RootExecutionResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** IME selection must finish even when the agent's normal root executor is stopped. */
internal class ImeCommandRunner(
    private val timeoutMillis: Long = 5_000,
    private val start: (String) -> Process = { command ->
        ProcessBuilder("su", "-c", command).redirectErrorStream(true)
            .redirectOutput(File("/dev/null")).start()
    },
) {
    suspend fun select(id: String): RootExecutionResult = runInterruptible(Dispatchers.IO) {
        val process = start(RootCommandBuilder.selectInputMethod(id))
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                RootExecutionResult.Failure("输入法切换超时")
            } else if (process.exitValue() != 0) {
                RootExecutionResult.Failure("系统拒绝切换输入法")
            } else {
                RootExecutionResult.Success()
            }
        } finally {
            process.destroyForcibly()
        }
    }
}
