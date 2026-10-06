package com.example.agent.rootpilot.virtualdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotKey
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * One-shot transport, not an authorization boundary. The caller must apply ActionPolicy,
 * the current app allowlist and the actual per-action confirmation before execute().
 * start() creates an empty display; it never launches an app or waits for its first frame.
 */
internal class VirtualDisplaySession(context: Context) {
    private val appContext = context.applicationContext
    private val uuid = UUID.randomUUID()
    private val transport = VirtualDisplayTransport(uuid, displayAbsent = {
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: throw Failure(Reason.RELEASE)
        manager.displays.none { it.name == VirtualDisplayProtocol.DISPLAY_PREFIX + uuid }
    }, helperExited = { pid ->
        // Signal 0 only checks existence. EPERM or any unknown result is not evidence of exit.
        try {
            Os.kill(pid, 0)
            false
        } catch (error: ErrnoException) {
            error.errno == OsConstants.ESRCH
        }
    }) {
        ProcessBuilder("su", "-c", VirtualDisplayProtocol.launchCommand(appContext.applicationInfo.sourceDir, uuid))
            .redirectError(File("/dev/null"))
            .start()
    }
    val sessionId: String = uuid.toString()
    val displayId: Int get() = transport.displayId

    suspend fun start(): RootExecutionResult = execution { transport.start() }

    suspend fun validate(): Boolean = try {
        transport.validate()
        true
    } catch (error: CancellationException) {
        cancel()
        throw error
    } catch (_: Exception) { false }

    suspend fun capture(): RootScreenshotResult = try {
        RootScreenshotResult.Success(transport.capture())
    } catch (error: CancellationException) {
        cancel()
        throw error
    } catch (error: Exception) {
        RootScreenshotResult.Failure(reason(error).code)
    }

    suspend fun execute(action: ExecutableRootAction): RootExecutionResult = execution {
        val request = when (action) {
            is ExecutableRootAction.OpenApp -> {
                VirtualDisplayProtocol.validateApp(action.app.packageName, action.app.activityName)
                Op.OPEN_APP to VirtualDisplayProtocol.payload {
                    writeUTF(action.app.packageName); writeUTF(action.app.activityName)
                }
            }
            is ExecutableRootAction.Tap -> {
                VirtualDisplayProtocol.tap(action.x, action.y)
                Op.TAP to VirtualDisplayProtocol.payload { writeInt(action.x); writeInt(action.y) }
            }
            is ExecutableRootAction.Wait -> {
                VirtualDisplayProtocol.waitDuration(action.durationMillis)
                Op.WAIT to VirtualDisplayProtocol.payload { writeInt(action.durationMillis) }
            }
            is ExecutableRootAction.Swipe -> {
                VirtualDisplayProtocol.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMillis)
                Op.SWIPE to VirtualDisplayProtocol.payload {
                    writeInt(action.x1); writeInt(action.y1); writeInt(action.x2); writeInt(action.y2); writeInt(action.durationMillis)
                }
            }
            is ExecutableRootAction.Key -> {
                val code = when (action.key) {
                    RootPilotKey.BACK -> 4
                    RootPilotKey.ENTER -> 66
                    RootPilotKey.HOME -> throw Failure(Reason.UNSUPPORTED)
                }
                Op.KEY to VirtualDisplayProtocol.payload { writeInt(code) }
            }
            is ExecutableRootAction.Type -> throw Failure(Reason.UNSUPPORTED)
        }
        transport.execute(request.first, request.second)
    }

    /** True means confirmed terminal release, process exit and absence of this session's display. */
    suspend fun close(): Boolean = transport.close()

    fun cancel() = transport.cancel()

    private suspend fun execution(block: suspend () -> Unit): RootExecutionResult = try {
        block()
        RootExecutionResult.Success()
    } catch (error: CancellationException) {
        cancel()
        throw error
    } catch (error: Exception) {
        RootExecutionResult.Failure(reason(error).code)
    }

    private fun reason(error: Exception): Reason = (error as? Failure)?.reason ?: Reason.IO
}
