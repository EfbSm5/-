package com.example.agent.rootpilot.virtualdisplay

import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason

internal object VirtualDisplayCommands {
    /** These are cmd service arguments, never shell text. The display comes only from the owner. */
    fun arguments(op: Op, payload: ByteArray, displayId: Int): List<String> {
        if (displayId <= 0) throw Failure(Reason.DISPLAY_INVALID)
        return when (op) {
            Op.OPEN_APP -> VirtualDisplayProtocol.parse(payload) {
                val packageName = readUTF()
                val activityName = readUTF()
                VirtualDisplayProtocol.validateApp(packageName, activityName)
                listOf("/system/bin/cmd", "activity", "start-activity", "-W", "--user", "current",
                    "--display", displayId.toString(), "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", "-n", "$packageName/$activityName")
            }
            Op.TAP -> VirtualDisplayProtocol.parse(payload) {
                val x = readInt()
                val y = readInt()
                VirtualDisplayProtocol.tap(x, y)
                listOf("/system/bin/cmd", "input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
            }
            else -> throw Failure(Reason.UNSUPPORTED)
        }
    }

    fun launchSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Status: ok" } &&
        output.lineSequence().none { it.trimStart().startsWith("Error:") || it.trim() == "Status: timeout" }
}
