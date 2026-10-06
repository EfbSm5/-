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
            Op.SWIPE -> VirtualDisplayProtocol.parse(payload) {
                val x1 = readInt(); val y1 = readInt(); val x2 = readInt(); val y2 = readInt(); val duration = readInt()
                VirtualDisplayProtocol.swipe(x1, y1, x2, y2, duration)
                listOf("/system/bin/cmd", "input", "-d", displayId.toString(), "swipe",
                    x1.toString(), y1.toString(), x2.toString(), y2.toString(), duration.toString())
            }
            Op.KEY -> VirtualDisplayProtocol.parse(payload) {
                val code = readInt().also(VirtualDisplayProtocol::key)
                listOf("/system/bin/cmd", "input", "-d", displayId.toString(), "keyevent", code.toString())
            }
            else -> throw Failure(Reason.UNSUPPORTED)
        }
    }

    fun launchSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Status: ok" } &&
        output.lineSequence().none { it.trimStart().startsWith("Error:") || it.trim() == "Status: timeout" }
}
