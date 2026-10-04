package com.example.agent.rootpilot.virtualdisplay

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.DPI
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.HEIGHT
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Identity
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.WIDTH
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owned only by the app_process helper. Hidden APIs are limited to creating/querying this display. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant")
internal class VirtualDisplayOwner(private val session: UUID, private val invalidated: () -> Unit) {
    private val imageLock = Object()
    private val invalid = AtomicBoolean(false)
    @Volatile private var closing = false
    private var manager: DisplayManager? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var imageThread: HandlerThread? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var listener: DisplayManager.DisplayListener? = null
    private var identity: Identity? = null
    private lateinit var displayService: Any
    private lateinit var displayInterface: Class<*>
    private lateinit var windowService: Any
    private lateinit var windowInterface: Class<*>
    private val name = VirtualDisplayProtocol.DISPLAY_PREFIX + session

    fun create(): Identity {
        if (Process.myUid() != 0) throw Failure(Reason.START)
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val shellContext = object : ContextWrapper(context) {
            override fun getPackageName() = "com.android.shell"
            override fun getOpPackageName() = "com.android.shell"
            override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(Process.SHELL_UID)
                .setPackageName("com.android.shell").build()
        }
        displayInterface = Class.forName("android.hardware.display.IDisplayManager")
        displayService = service("display", "android.hardware.display.IDisplayManager")
        windowInterface = Class.forName("android.view.IWindowManager")
        windowService = service("window", "android.view.IWindowManager")
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(shellContext)
        manager = dm
        // No adoption or cleanup of an earlier helper, even if its client has died.
        if (dedicatedDisplays().isNotEmpty()) throw Failure(Reason.DISPLAY_EXISTS)
        val callbacks = HandlerThread("RootPilot-VdImages").also { it.start(); imageThread = it }
        val handler = Handler(callbacks.looper)
        val images = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3).also { reader = it }
        images.setOnImageAvailableListener({ source ->
            try {
                synchronized(imageLock) {
                    if (!closing) {
                        source.acquireLatestImage()?.let { next ->
                            latest?.close()
                            latest = next
                            imageLock.notifyAll()
                        }
                    }
                }
            } catch (_: Throwable) { markInvalid() }
        }, handler)
        // Same DisplayManager construction pattern as scrcpy v4.1; no mirroring/secure flags.
        // Hidden flag values are stable AOSP constants: touch, destroy-content, trusted, group, focus.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or (1 shl 8) or (1 shl 10) or (1 shl 11) or (1 shl 14)
        val vd = dm.createVirtualDisplay(name, WIDTH, HEIGHT, DPI, images.surface, flags)
            ?: throw Failure(Reason.START)
        virtualDisplay = vd
        val id = vd.display.displayId
        if (id <= Display.DEFAULT_DISPLAY) throw Failure(Reason.DISPLAY_INVALID)
        val info = info(id) ?: throw Failure(Reason.DISPLAY_INVALID)
        val created = Identity(id, string(info, "uniqueId")).also { it.check() }
        identity = created
        // HIDE affects only this display; no connection to the user's active IME is established.
        windowInterface.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(windowService, id, IME_POLICY_HIDE)
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = changed()
            override fun onDisplayRemoved(displayId: Int) = changed()
            override fun onDisplayChanged(displayId: Int) = changed()
            private fun changed() {
                if (!closing) try { validate() } catch (_: Throwable) { markInvalid() }
            }
        }.also { dm.registerDisplayListener(it, handler) }
        validate()
        return created
    }

    /** Direct Binder query avoids using a cached logical display after removal or geometry changes. */
    fun validate(): Identity {
        if (closing || invalid.get()) throw Failure(Reason.DISPLAY_INVALID)
        val held = identity ?: throw Failure(Reason.DISPLAY_INVALID)
        val vd = virtualDisplay ?: throw Failure(Reason.DISPLAY_INVALID)
        val info = info(held.displayId) ?: throw Failure(Reason.DISPLAY_INVALID)
        val same = vd.display.displayId == held.displayId && string(info, "uniqueId") == held.uniqueId &&
            string(info, "name") == name && int(info, "logicalWidth") == WIDTH && int(info, "logicalHeight") == HEIGHT &&
            int(info, "logicalDensityDpi") == DPI && int(info, "rotation") == 0 && int(info, "type") == TYPE_VIRTUAL
        val imePolicy = windowInterface.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
            .invoke(windowService, held.displayId) as Int
        if (!same || imePolicy != IME_POLICY_HIDE || dedicatedDisplays() != listOf(held.displayId)) {
            throw Failure(Reason.DISPLAY_INVALID)
        }
        return held
    }

    fun capture(): ByteArray {
        validate()
        val until = SystemClock.elapsedRealtime() + VirtualDisplayProtocol.CAPTURE_TIMEOUT_MS
        val bitmap = synchronized(imageLock) {
            while (latest == null && !closing && !invalid.get()) {
                val remaining = until - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw Failure(Reason.CAPTURE)
                imageLock.wait(remaining)
            }
            val image = latest ?: throw Failure(Reason.CAPTURE)
            if (image.width != WIDTH || image.height != HEIGHT || image.format != PixelFormat.RGBA_8888 || image.planes.size != 1) {
                throw Failure(Reason.CAPTURE)
            }
            val plane = image.planes[0]
            if (plane.pixelStride != 4 || plane.rowStride !in WIDTH * 4..WIDTH * 4 + 4096) throw Failure(Reason.CAPTURE)
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val row = ByteArray(WIDTH * 4)
            val pixels = IntArray(WIDTH * HEIGHT)
            for (y in 0 until HEIGHT) {
                buffer.position(start + y * plane.rowStride)
                buffer.get(row)
                for (x in 0 until WIDTH) {
                    val offset = x * 4
                    pixels[y * WIDTH + x] = ((row[offset + 3].toInt() and 255) shl 24) or
                        ((row[offset].toInt() and 255) shl 16) or ((row[offset + 1].toInt() and 255) shl 8) or
                        (row[offset + 2].toInt() and 255)
                }
            }
            Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        }
        try {
            val png = LimitedPngStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw Failure(Reason.CAPTURE)
            validate()
            return png.toByteArray().also(VirtualDisplayProtocol::checkPng)
        } finally { bitmap.recycle() }
    }

    /** A successful release call alone is insufficient; the logical display must disappear. */
    fun release(): Boolean {
        closing = true
        synchronized(imageLock) { imageLock.notifyAll() }
        var clean = true
        fun attempt(block: () -> Unit) { try { block() } catch (_: Throwable) { clean = false } }
        attempt { listener?.let { manager?.unregisterDisplayListener(it) } }
        attempt { virtualDisplay?.release() }
        attempt {
            synchronized(imageLock) {
                reader?.setOnImageAvailableListener(null, null)
                latest?.close()
                latest = null
                reader?.close()
            }
        }
        attempt { imageThread?.quitSafely() }
        val held = identity
        if (held != null) {
            attempt {
                val until = SystemClock.elapsedRealtime() + 1_500
                while (info(held.displayId) != null && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
                if (info(held.displayId) != null) clean = false
            }
        } else if (virtualDisplay != null) {
            // Creation obtained a Binder resource but did not establish its identity.
            clean = false
        }
        return clean
    }

    private fun markInvalid() {
        if (!closing && invalid.compareAndSet(false, true)) invalidated()
    }

    private fun dedicatedDisplays(): List<Int> = manager!!.displays.mapNotNull { display ->
        val current = info(display.displayId) ?: throw Failure(Reason.DISPLAY_INVALID)
        display.displayId.takeIf { string(current, "name").startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }
    }.sorted()

    private fun info(id: Int): Any? = displayInterface.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        .invoke(displayService, id)
    private fun string(info: Any, field: String): String = info.javaClass.getField(field).get(info) as String
    private fun int(info: Any, field: String): Int = info.javaClass.getField(field).getInt(info)

    private fun service(name: String, type: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
        return Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw Failure(Reason.START)
    }

    private class LimitedPngStream : ByteArrayOutputStream(64 * 1024) {
        override fun write(value: Int) {
            if (count >= VirtualDisplayProtocol.MAX_PNG_BYTES) throw Failure(Reason.CAPTURE)
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > VirtualDisplayProtocol.MAX_PNG_BYTES - count) throw Failure(Reason.CAPTURE)
            super.write(bytes, offset, length)
        }
    }

    private companion object {
        const val IME_POLICY_HIDE = 2
        const val TYPE_VIRTUAL = 5
    }
}
