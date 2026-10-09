package com.example.agent.rootpilot.parity

import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileOutputStream

/** Explicit root metadata diagnosis; no display creation, input, settings or page reads. */
internal object RootProbeEnvironmentDiagnostic {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = FileOutputStream(Os.dup(FileDescriptor.out))
        val sink = Os.open("/dev/null", OsConstants.O_RDWR, 0)
        try {
            Os.dup2(sink, OsConstants.STDOUT_FILENO)
            Os.dup2(sink, OsConstants.STDERR_FILENO)
        } finally { Os.close(sink) }
        if (Process.myUid() != 0 || args.isNotEmpty()) Runtime.getRuntime().halt(1)
        Thread({ Thread.sleep(8_000); Runtime.getRuntime().halt(1) }, "RootPilot-EnvironmentDeadline").apply {
            isDaemon = true
            start()
        }
        fun report(name: String, value: String) { output.write("$name=$value\n".toByteArray(Charsets.US_ASCII)); output.flush() }
        fun kind(error: Throwable) = when (error) {
            is NullPointerException -> "NULL_POINTER"
            is SecurityException -> "SECURITY"
            is UnsupportedOperationException -> "UNSUPPORTED"
            is ReflectiveOperationException -> "REFLECTION"
            is LinkageError -> "LINKAGE"
            else -> "OTHER"
        }
        fun <T> inspect(name: String, block: () -> T): T? = try {
            block().also { report(name, when (it) { null -> "UNAVAILABLE"; true -> "TRUE"; false -> "FALSE"; else -> "PRESENT" }) }
        } catch (error: Throwable) { report(name, kind(error)); null }
        val context = inspect("systemContext") {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(thread) as Context
        } ?: Runtime.getRuntime().halt(1).let { return }
        inspect("applicationContextPresent") { context.applicationContext != null }
        val keyguard = inspect("keyguardService") { context.getSystemService(KeyguardManager::class.java) }
        val power = inspect("powerService") { context.getSystemService(PowerManager::class.java) }
        if (keyguard != null) {
            inspect("deviceLocked") { keyguard.isDeviceLocked }
            inspect("keyguardLocked") { keyguard.isKeyguardLocked }
        }
        if (power != null) inspect("interactive") { power.isInteractive }
        fun proxy(name: String, type: String): Any {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
            return Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: error("environment_service_unavailable")
        }
        inspect("systemUserZero") { Context::class.java.getMethod("getUserId").invoke(context) == 0 }
        inspect("systemDeviceZero") { context.deviceId == 0 }
        inspect("binderDeviceLocked") {
            val type = "android.app.trust.ITrustManager"
            Class.forName(type).getMethod("isDeviceLocked", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(proxy("trust", type), 0, 0) as Boolean
        }
        inspect("binderKeyguardLocked") {
            val type = "android.view.IWindowManager"
            Class.forName(type).getMethod("isKeyguardLocked").invoke(proxy("window", type)) as Boolean
        }
        inspect("binderInteractive") {
            val type = "android.os.IPowerManager"
            Class.forName(type).getMethod("isInteractive").invoke(proxy("power", type)) as Boolean
        }
        Runtime.getRuntime().halt(0)
    }
}
