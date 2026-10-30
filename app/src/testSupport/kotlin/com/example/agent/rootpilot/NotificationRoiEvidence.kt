package com.example.agent.rootpilot

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** Only test-side ROI admission/publication. Geometry is live input, never exported. */
internal object NotificationRoiEvidence {
    const val MAX_SCREEN_PIXELS = 8_388_608L
    const val MAX_ROI_PIXELS = 1_048_576L
    const val MAX_PNG_BYTES = 4_194_304
    const val MAX_MANIFEST_BYTES = 32_768
    const val DIRECTORY = "rootpilot_notification_roi"
    const val PNG_FILE = "notification.png"
    const val MANIFEST_FILE = "manifest.json"
    const val POLICY = "OWN_STRICT_ROW_MEMORY_CROP_V1"
    enum class Code { NONE, ROI_IDENTITY, ROI_BOUNDS, ROI_PRIVACY, ROI_LIMIT, ROI_CAPTURE, ROI_POST, ROI_WRITE,
        ROI_PRIVACY_API, ROI_PRIVACY_LOCK, ROI_PRIVACY_ROOT, ROI_PRIVACY_MATCH,
        ROI_PRIVACY_WINDOWS, ROI_PRIVACY_META, ROI_PRIVACY_OWN, ROI_PRIVACY_OVERLAP }
    class Failure(val code: Code) : RuntimeException(code.name)
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Long get() = right.toLong() - left
        val height: Long get() = bottom.toLong() - top
        fun nonempty() = width > 0 && height > 0
        fun contains(other: Bounds) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
        fun intersects(other: Bounds) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
    data class Window(val id: Int?, val displayId: Int?, val layer: Int?, val bounds: Bounds?, val active: Boolean?)
    data class Frame(
        val width: Int? = null, val height: Int? = null, val rotation: Int? = null, val displayId: Int? = null,
        val roi: Bounds? = null, val windowId: Int? = null, val windows: List<Window>? = null,
        val owned: Boolean? = null, val fullIdentity: Boolean? = null, val systemUi: Boolean? = null,
    )
    data class Proofs(
        val freshOwnRow: Boolean? = null, val fullIdentity: Boolean? = null, val boundsStable: Boolean? = null,
        val windowChecksPassed: Boolean? = null, val ownNotificationUnchanged: Boolean? = null,
        val noOwnActivityLaunch: Boolean? = null, val cleanupVerified: Boolean? = null,
        val fullScreenMemoryOnly: Boolean? = null, val independentRoiBitmap: Boolean? = null,
        val noModelRequests: Boolean? = null, val noExecutions: Boolean? = null,
    ) {
        fun fields() = linkedMapOf("freshOwnRow" to freshOwnRow, "fullIdentity" to fullIdentity,
            "boundsStable" to boundsStable, "windowChecksPassed" to windowChecksPassed,
            "ownNotificationUnchanged" to ownNotificationUnchanged, "noOwnActivityLaunch" to noOwnActivityLaunch,
            "cleanupVerified" to cleanupVerified, "fullScreenMemoryOnly" to fullScreenMemoryOnly,
            "independentRoiBitmap" to independentRoiBitmap, "noModelRequests" to noModelRequests, "noExecutions" to noExecutions)
    }

    fun admit(frame: Frame): Code {
        if (frame.owned != true || frame.fullIdentity != true || frame.systemUi != true) return Code.ROI_IDENTITY
        val w = frame.width ?: return Code.ROI_BOUNDS
        val h = frame.height ?: return Code.ROI_BOUNDS
        val roi = frame.roi ?: return Code.ROI_BOUNDS
        if (frame.displayId != 0 || frame.rotation !in 0..3 || w <= 0 || h <= 0 || !roi.nonempty() ||
            !Bounds(0, 0, w, h).contains(roi) || roi == Bounds(0, 0, w, h)) return Code.ROI_BOUNDS
        if (w.toLong() * h > MAX_SCREEN_PIXELS || roi.width * roi.height > MAX_ROI_PIXELS) return Code.ROI_LIMIT
        val windows = frame.windows ?: return Code.ROI_PRIVACY_WINDOWS
        if (windows.size !in 1..8) return Code.ROI_PRIVACY_WINDOWS
        if (windows.any { it.id == null || it.id < 0 || it.displayId == null || it.displayId < 0 ||
                it.layer == null || it.layer < 0 || it.bounds?.nonempty() != true || it.active == null } ||
            windows.map { it.id }.distinct().size != windows.size) return Code.ROI_PRIVACY_META
        val own = windows.singleOrNull { it.id == frame.windowId } ?: return Code.ROI_PRIVACY_OWN
        if (own.active != true || own.displayId != 0 || !own.bounds!!.contains(roi) || windows.count { it.active == true } != 1)
            return Code.ROI_PRIVACY_OWN
        // No status-bar, keyboard, system, or accessibility-overlay exception. Same-layer ambiguity closes too.
        if (windows.any { it.id != own.id && it.displayId == 0 && it.layer!! >= own.layer!! && it.bounds!!.intersects(roi) })
            return Code.ROI_PRIVACY_OVERLAP
        return Code.NONE
    }

    fun after(before: Frame, after: Frame): Code =
        if (admit(before) == Code.NONE && admit(after) == Code.NONE &&
            before.copy(windows = before.windows?.sortedBy { it.id }) == after.copy(windows = after.windows?.sortedBy { it.id }))
            Code.NONE else Code.ROI_POST

    fun bitmap(frame: Frame, width: Int, height: Int, argb8888: Boolean, allocationBytes: Int): Code = when {
        admit(frame) != Code.NONE -> admit(frame)
        width != frame.width || height != frame.height || !argb8888 -> Code.ROI_CAPTURE
        allocationBytes <= 0 || allocationBytes.toLong() > MAX_SCREEN_PIXELS * 4 -> Code.ROI_LIMIT
        else -> Code.NONE
    }

    /** Bitmap.compress receives this bounded sink only for the independent cropped bitmap. */
    class PngOutput : OutputStream() {
        private val output = ByteArrayOutputStream(8_192)
        override fun write(value: Int) { check(output.size() < MAX_PNG_BYTES, Code.ROI_LIMIT); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            check(length <= MAX_PNG_BYTES - output.size(), Code.ROI_LIMIT)
            output.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }

    fun pngValid(png: ByteArray, width: Int, height: Int): Boolean {
        if (png.size !in 45..MAX_PNG_BYTES || width <= 0 || height <= 0 || width.toLong() * height > MAX_ROI_PIXELS ||
            !png.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
        val input = ByteBuffer.wrap(png)
        var cursor = 8
        var header = false
        var image = false
        var channels = 0
        var srgb = false
        var significantBits = false
        while (cursor <= png.size - 12) {
            val size = input.getInt(cursor)
            if (size < 0 || size > png.size - cursor - 12) return false
            val type = String(png, cursor + 4, 4, Charsets.US_ASCII)
            val crc = CRC32().apply { update(png, cursor + 4, size + 4) }.value
            if (crc != (input.getInt(cursor + size + 8).toLong() and 0xffffffffL)) return false
            when (type) {
                "IHDR" -> {
                    if (header || cursor != 8 || size != 13 || input.getInt(cursor + 8) != width || input.getInt(cursor + 12) != height ||
                        png[cursor + 16].toInt() != 8 || png[cursor + 17].toInt() !in listOf(2, 6) ||
                        (18..20).any { png[cursor + it].toInt() != 0 }) return false
                    channels = if (png[cursor + 17].toInt() == 6) 4 else 3
                    header = true
                }
                "IDAT" -> { if (!header || size == 0) return false; image = true }
                "IEND" -> return header && image && size == 0 && cursor + 12 == png.size
                "sRGB" -> {
                    if (!header || image || srgb || size != 1 || png[cursor + 8].toInt() !in 0..3) return false
                    srgb = true
                }
                "sBIT" -> {
                    if (!header || image || significantBits || size != channels || (0 until size).any { png[cursor + 8 + it].toInt() != 8 }) return false
                    significantBits = true
                }
                else -> return false // No text, EXIF, unknown profiles, or other metadata payload.
            }
            cursor += size + 12
        }
        return false
    }

    fun manifest(receipt: String, pid: Int, width: Int, height: Int, png: ByteArray, screenshotCalls: Int, proofs: Proofs): ByteArray {
        check(receipt.length == 36 && runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false) && pid > 0,
            Code.ROI_IDENTITY)
        check(screenshotCalls == 1 && proofs.fields().values.all { it == true }, Code.ROI_POST)
        check(png.size <= MAX_PNG_BYTES, Code.ROI_LIMIT)
        check(pngValid(png, width, height), Code.ROI_CAPTURE)
        return buildString {
            append("{\"schemaVersion\":1,\"artifactKind\":\"OWN_NOTIFICATION_ROI\",\"receiptId\":\"").append(receipt)
            append("\",\"mainPid\":").append(pid).append(",\"pngFile\":\"notification.png\",\"encoding\":\"PNG\",\"roiWidth\":").append(width)
            append(",\"roiHeight\":").append(height).append(",\"pngBytes\":").append(png.size).append(",\"pngSha256\":\"").append(sha256(png))
            append("\",\"screenshotCalls\":1,\"privacyPolicy\":\"").append(POLICY).append("\",\"proofs\":{")
            proofs.fields().keys.forEachIndexed { index, key -> if (index > 0) append(','); append('"').append(key).append("\":true") }
            append("}}")
        }.toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_MANIFEST_BYTES, Code.ROI_LIMIT) }
    }

    /** The successful receipt is required too: CREATE_NEW/force/close is not filesystem-atomic publication. */
    fun write(base: Path, receipt: String, pid: Int, width: Int, height: Int, png: ByteArray, screenshotCalls: Int,
        proofs: Proofs, beforeManifest: () -> Unit = {}) {
        val json = manifest(receipt, pid, width, height, png, screenshotCalls, proofs)
        val absoluteBase = base.toAbsolutePath().normalize()
        val root = absoluteBase.resolve(DIRECTORY)
        val directory = root.resolve(receipt)
        val owned = mutableListOf<OwnedFile>()
        try {
            plainDirectory(absoluteBase)
            try { Files.createDirectory(root) } catch (_: FileAlreadyExistsException) { /* Validate without following below. */ }
            plainDirectory(root)
            Files.createDirectory(directory) // An existing receipt, even empty, is never reused.
            plainDirectory(directory)
            exclusive(directory.resolve(PNG_FILE), png, owned)
            beforeManifest()
            exclusive(directory.resolve(MANIFEST_FILE), json, owned)
        } catch (_: Throwable) {
            // Only our newly created exact paths, and only when their current identity/content still match.
            owned.asReversed().forEach { rollback(it, directory) }
            throw Failure(Code.ROI_WRITE)
        }
    }

    private data class OwnedFile(val path: Path, val key: Any?, val created: java.nio.file.attribute.FileTime, val bytes: ByteArray)
    private fun exclusive(path: Path, bytes: ByteArray, owned: MutableList<OwnedFile>) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attrs.isRegularFile && !attrs.isSymbolicLink, Code.ROI_WRITE)
            owned += OwnedFile(path, attrs.fileKey(), attrs.creationTime(), bytes)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0, Code.ROI_WRITE)
            channel.force(true)
        }
    }
    private fun rollback(owned: OwnedFile, directory: Path) {
        try {
            val path = owned.path.toAbsolutePath().normalize()
            if (path != owned.path || path.parent != directory || path.fileName.toString() !in listOf(PNG_FILE, MANIFEST_FILE)) return
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() > owned.bytes.size ||
                (owned.key != null && attrs.fileKey() != owned.key) || attrs.creationTime() != owned.created) return
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8_192)
                var offset = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > owned.bytes.size - offset || (0 until count).any { buffer[it] != owned.bytes[offset + it] }) return
                    offset += count
                }
            }
            // Recheck identity after the bounded read before deleting this exact owned absolute path.
            val final = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (final.isRegularFile && !final.isSymbolicLink && final.fileKey() == attrs.fileKey() &&
                final.creationTime() == attrs.creationTime() && final.size() == attrs.size()) Files.delete(path)
        } catch (_: Throwable) { /* No valid receipt on failure; never delete unverifiable or historical data. */ }
    }
    private fun plainDirectory(path: Path) {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attrs.isDirectory && !attrs.isSymbolicLink, Code.ROI_WRITE)
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun check(value: Boolean, code: Code) { if (!value) throw Failure(code) }
}
