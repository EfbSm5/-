package com.example.agent.rootpilot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Test

class NotificationRoiEvidenceTest {
    private val receipt = "01234567-89ab-4cde-8fab-0123456789ab"
    private val roi = NotificationRoiEvidence.Bounds(10, 20, 14, 23)
    private val own = NotificationRoiEvidence.Window(7, 0, 2, NotificationRoiEvidence.Bounds(0, 0, 100, 200), true)
    private fun frame() = NotificationRoiEvidence.Frame(100, 200, 0, 0, roi, 7, listOf(own), true, true, true)
    private fun proofs() = NotificationRoiEvidence.Proofs(true, true, true, true, true, true, true, true, true, true, true)
    private fun failure(code: NotificationRoiEvidence.Code, block: () -> Unit) {
        try { block(); fail("missing fixed failure") } catch (error: NotificationRoiEvidence.Failure) { assertEquals(code, error.code) }
    }
    private fun chunk(type: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(payload.size)
            val name = type.toByteArray(Charsets.US_ASCII)
            data.write(name); data.write(payload)
            data.writeInt(CRC32().apply { update(name); update(payload) }.value.toInt())
        }
    }.toByteArray()
    private fun png(extra: ByteArray = byteArrayOf(), width: Int = 4, height: Int = 3): ByteArray {
        val header = ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
            it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0))
        } }.toByteArray()
        val pixels = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use {
            repeat(height.coerceIn(0, 100)) { _ -> it.write(ByteArray(1 + width.coerceIn(0, 100) * 4)) }
        } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + extra + chunk("IDAT", pixels) + chunk("IEND", byteArrayOf())
    }

    @Test fun acceptsOnlyVerifiedVisibleOwnRow() { assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.admit(frame())) }
    @Test fun identityRequiresEveryProofAvailableAndTrue() {
        listOf(frame().copy(owned = false), frame().copy(owned = null), frame().copy(fullIdentity = false),
            frame().copy(fullIdentity = null), frame().copy(systemUi = false), frame().copy(systemUi = null)).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_IDENTITY, NotificationRoiEvidence.admit(it))
        }
    }
    @Test fun missingGeometryFailsClosed() {
        listOf(frame().copy(width = null), frame().copy(height = null), frame().copy(rotation = null),
            frame().copy(displayId = null), frame().copy(roi = null)).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_BOUNDS, NotificationRoiEvidence.admit(it))
        }
    }
    @Test fun emptyNegativeOutsideAndFullScreenBoundsFail() {
        listOf(roi.copy(right = 10), roi.copy(bottom = 20), roi.copy(left = -1), roi.copy(top = -1),
            roi.copy(right = 101), roi.copy(bottom = 201), NotificationRoiEvidence.Bounds(0, 0, 100, 200)).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_BOUNDS, NotificationRoiEvidence.admit(frame().copy(roi = it)))
        }
    }
    @Test fun invalidDisplayDimensionsAndRotationFail() {
        listOf(frame().copy(displayId = 1), frame().copy(width = 0), frame().copy(height = -1),
            frame().copy(rotation = -1), frame().copy(rotation = 4)).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_BOUNDS, NotificationRoiEvidence.admit(it))
        }
    }
    @Test fun screenAndRoiPixelLimitsUseLongArithmetic() {
        assertEquals(NotificationRoiEvidence.Code.ROI_LIMIT, NotificationRoiEvidence.admit(frame().copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE)))
        val huge = frame().copy(width = 2000, height = 2000, roi = NotificationRoiEvidence.Bounds(0, 0, 1025, 1024),
            windows = listOf(own.copy(bounds = NotificationRoiEvidence.Bounds(0, 0, 2000, 2000))))
        assertEquals(NotificationRoiEvidence.Code.ROI_LIMIT, NotificationRoiEvidence.admit(huge))
        assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.admit(huge.copy(roi = NotificationRoiEvidence.Bounds(0, 0, 1024, 1024))))
    }
    @Test fun absentEmptyOverBudgetOrDuplicateWindowsFail() {
        listOf(null, emptyList(), List(9) { own }).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_WINDOWS, NotificationRoiEvidence.admit(frame().copy(windows = it)))
        }
        assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_META, NotificationRoiEvidence.admit(frame().copy(windows = listOf(own, own))))
    }
    @Test fun everyWindowMetadataFieldMustBeAvailableAndValid() {
        listOf(own.copy(id = null), own.copy(displayId = null), own.copy(layer = null), own.copy(bounds = null), own.copy(active = null),
            own.copy(id = -1), own.copy(displayId = -1), own.copy(layer = -1), own.copy(bounds = roi.copy(right = 0))).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_META, NotificationRoiEvidence.admit(frame().copy(windows = listOf(it))))
        }
    }
    @Test fun targetWindowMustBeUniqueActiveDefaultDisplayAndContainWholeRoi() {
        listOf(frame().copy(windowId = null), frame().copy(windowId = 8), frame().copy(windows = listOf(own.copy(active = false))),
            frame().copy(windows = listOf(own.copy(displayId = 1))), frame().copy(windows = listOf(own.copy(bounds = roi.copy(left = 11))))).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_OWN, NotificationRoiEvidence.admit(it))
        }
    }
    @Test fun overlaysIncludingSameLayerSystemOrKeyboardReceiveNoExemption() {
        listOf(2, 3, 100).forEach { layer ->
            assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_OVERLAP,
                NotificationRoiEvidence.admit(frame().copy(windows = listOf(own, own.copy(id = 8, active = false, layer = layer, bounds = roi)))))
        }
    }
    @Test fun secondActiveWindowFailsEvenWhenNonintersecting() {
        assertEquals(NotificationRoiEvidence.Code.ROI_PRIVACY_OWN, NotificationRoiEvidence.admit(frame().copy(windows = listOf(own,
            own.copy(id = 8, active = true, bounds = NotificationRoiEvidence.Bounds(0, 0, 1, 1))))))
    }
    @Test fun lowerLayerOtherDisplayAndNonintersectingBoundsAreNotOcclusion() {
        listOf(own.copy(id = 8, active = false, layer = 1), own.copy(id = 8, active = false, layer = 3, displayId = 1),
            own.copy(id = 8, active = false, layer = 3, bounds = roi.copy(left = 14, right = 20))).forEach {
            assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.admit(frame().copy(windows = listOf(own, it))))
        }
    }
    @Test fun postcheckRequiresAllGeometryIdentityAndWindowsToRemainEqual() {
        val before = frame()
        listOf(before.copy(rotation = 1), before.copy(width = 101), before.copy(roi = roi.copy(left = 11)),
            before.copy(owned = false), before.copy(fullIdentity = null), before.copy(windows = listOf(own.copy(layer = 3))),
            before.copy(windows = listOf(own, own.copy(id = 8, active = false, layer = 1)))).forEach {
            assertEquals(NotificationRoiEvidence.Code.ROI_POST, NotificationRoiEvidence.after(before, it))
        }
        assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.after(before, before))
    }
    @Test fun windowEnumerationOrderingAloneDoesNotCountAsGeometryChange() {
        val other = own.copy(id = 8, active = false, layer = 1)
        assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.after(frame().copy(windows = listOf(own, other)),
            frame().copy(windows = listOf(other, own))))
    }
    @Test fun screenshotBitmapMustMatchDimensionsConfigAndAllocationLimit() {
        assertEquals(NotificationRoiEvidence.Code.NONE, NotificationRoiEvidence.bitmap(frame(), 100, 200, true, 80_000))
        assertEquals(NotificationRoiEvidence.Code.ROI_CAPTURE, NotificationRoiEvidence.bitmap(frame(), 101, 200, true, 80_000))
        assertEquals(NotificationRoiEvidence.Code.ROI_CAPTURE, NotificationRoiEvidence.bitmap(frame(), 100, 200, false, 80_000))
        assertEquals(NotificationRoiEvidence.Code.ROI_LIMIT, NotificationRoiEvidence.bitmap(frame(), 100, 200, true, 0))
        assertEquals(NotificationRoiEvidence.Code.ROI_LIMIT, NotificationRoiEvidence.bitmap(frame(), 100, 200, true, 33_554_433))
    }
    @Test fun pngSinkStopsBeforeWritingBeyondHardLimit() {
        val output = NotificationRoiEvidence.PngOutput()
        output.write(ByteArray(NotificationRoiEvidence.MAX_PNG_BYTES))
        failure(NotificationRoiEvidence.Code.ROI_LIMIT) { output.write(0) }
        failure(NotificationRoiEvidence.Code.ROI_LIMIT) { output.write(byteArrayOf(1), 0, 1) }
        assertEquals(NotificationRoiEvidence.MAX_PNG_BYTES, output.bytes().size)
    }
    @Test fun acceptsOnlyExpectedRoiDimensionsAndPngIntegrity() {
        assertTrue(NotificationRoiEvidence.pngValid(png(), 4, 3))
        assertFalse(NotificationRoiEvidence.pngValid(png(), 5, 3))
        assertFalse(NotificationRoiEvidence.pngValid(png().dropLast(1).toByteArray(), 4, 3))
        assertFalse(NotificationRoiEvidence.pngValid(png() + byteArrayOf(0), 4, 3))
        assertFalse(NotificationRoiEvidence.pngValid(png().also { it[40] = (it[40].toInt() xor 1).toByte() }, 4, 3))
    }
    @Test fun pngRejectsPrivateOrUnknownAncillaryPayload() {
        listOf("tEXt", "iTXt", "zTXt", "eXIf", "iCCP", "gAMA", "zzzz").forEach {
            assertFalse(NotificationRoiEvidence.pngValid(png(chunk(it, byteArrayOf(1, 2, 3))), 4, 3))
        }
    }
    @Test fun pngAllowsOnlyLegalFixedSrgbAndSignificantBits() {
        assertTrue(NotificationRoiEvidence.pngValid(png(chunk("sRGB", byteArrayOf(0)) + chunk("sBIT", byteArrayOf(8, 8, 8, 8))), 4, 3))
        listOf(chunk("sRGB", byteArrayOf(4)), chunk("sBIT", byteArrayOf(8, 8, 8)), chunk("sBIT", byteArrayOf(0, 8, 8, 8)),
            chunk("sRGB", byteArrayOf(0)) + chunk("sRGB", byteArrayOf(0))).forEach {
            assertFalse(NotificationRoiEvidence.pngValid(png(it), 4, 3))
        }
    }
    @Test fun pngRejectsSubEightSignificantBitsInEveryChannel() {
        for (channel in 0..3) for (bits in 1..7) {
            val values = ByteArray(4) { 8 }.also { it[channel] = bits.toByte() }
            assertFalse(NotificationRoiEvidence.pngValid(png(chunk("sBIT", values)), 4, 3))
        }
    }
    @Test fun manifestBindsExactReceiptPidFileEncodingDimensionsSizeAndDigest() {
        val image = png()
        val json = NotificationRoiEvidence.manifest(receipt, 123, 4, 3, image, 1, proofs()).toString(Charsets.UTF_8)
        listOf("\"schemaVersion\":1", "\"artifactKind\":\"OWN_NOTIFICATION_ROI\"", "\"receiptId\":\"$receipt\"", "\"mainPid\":123",
            "\"pngFile\":\"notification.png\"", "\"encoding\":\"PNG\"", "\"roiWidth\":4", "\"roiHeight\":3", "\"pngBytes\":${image.size}",
            "\"pngSha256\":\"${NotificationRoiEvidence.sha256(image)}\"", "\"screenshotCalls\":1",
            "\"privacyPolicy\":\"OWN_STRICT_ROW_MEMORY_CROP_V1\"").forEach { assertTrue(json.contains(it)) }
        assertTrue(json.toByteArray().size <= NotificationRoiEvidence.MAX_MANIFEST_BYTES)
        assertFalse(json.contains("left")); assertFalse(json.contains("top")); assertFalse(json.contains("title"))
        assertEquals(11, proofs().fields().size)
    }
    @Test fun everyProofMustBeNonnullableVerifiedTrueAndCountExactlyOne() {
        val good = proofs()
        listOf(good.copy(freshOwnRow = false), good.copy(fullIdentity = null), good.copy(boundsStable = false),
            good.copy(windowChecksPassed = null), good.copy(ownNotificationUnchanged = false), good.copy(noOwnActivityLaunch = false),
            good.copy(cleanupVerified = false), good.copy(fullScreenMemoryOnly = false), good.copy(independentRoiBitmap = false),
            good.copy(noModelRequests = false), good.copy(noExecutions = false)).forEach {
            failure(NotificationRoiEvidence.Code.ROI_POST) { NotificationRoiEvidence.manifest(receipt, 123, 4, 3, png(), 1, it) }
        }
        listOf(0, 2).forEach { calls -> failure(NotificationRoiEvidence.Code.ROI_POST) {
            NotificationRoiEvidence.manifest(receipt, 123, 4, 3, png(), calls, good)
        } }
    }
    @Test fun invalidReceiptOrPidFailsBeforeIo() {
        listOf("../bad", receipt.uppercase(), "").forEach { value -> failure(NotificationRoiEvidence.Code.ROI_IDENTITY) {
            NotificationRoiEvidence.manifest(value, 123, 4, 3, png(), 1, proofs())
        } }
        failure(NotificationRoiEvidence.Code.ROI_IDENTITY) { NotificationRoiEvidence.manifest(receipt, 0, 4, 3, png(), 1, proofs()) }
    }
    @Test fun invalidOrOversizedPngNeverCreatesDirectory() {
        val base = Files.createTempDirectory("roi-invalid-")
        failure(NotificationRoiEvidence.Code.ROI_CAPTURE) { NotificationRoiEvidence.write(base, receipt, 123, 4, 3, byteArrayOf(1), 1, proofs()) }
        failure(NotificationRoiEvidence.Code.ROI_LIMIT) {
            NotificationRoiEvidence.write(base, receipt, 123, 4, 3, ByteArray(NotificationRoiEvidence.MAX_PNG_BYTES + 1), 1, proofs())
        }
        assertFalse(Files.exists(base.resolve(NotificationRoiEvidence.DIRECTORY)))
    }
    @Test fun publishesPngBeforeManifestWithoutOverwriting() {
        val base = Files.createTempDirectory("roi-publish-")
        val directory = base.resolve(NotificationRoiEvidence.DIRECTORY).resolve(receipt)
        val image = png()
        NotificationRoiEvidence.write(base, receipt, 123, 4, 3, image, 1, proofs()) {
            assertArrayEquals(image, Files.readAllBytes(directory.resolve(NotificationRoiEvidence.PNG_FILE)))
            assertFalse(Files.exists(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE)))
        }
        val manifest = Files.readAllBytes(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE))
        failure(NotificationRoiEvidence.Code.ROI_WRITE) { NotificationRoiEvidence.write(base, receipt, 123, 4, 3, image, 1, proofs()) }
        assertArrayEquals(image, Files.readAllBytes(directory.resolve(NotificationRoiEvidence.PNG_FILE)))
        assertArrayEquals(manifest, Files.readAllBytes(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE)))
    }
    @Test fun writeFailureCleansOnlyNewOwnedPngAndLeavesNoManifest() {
        val base = Files.createTempDirectory("roi-rollback-")
        val directory = base.resolve(NotificationRoiEvidence.DIRECTORY).resolve(receipt)
        failure(NotificationRoiEvidence.Code.ROI_WRITE) {
            NotificationRoiEvidence.write(base, receipt, 123, 4, 3, png(), 1, proofs()) { throw IllegalStateException() }
        }
        assertFalse(Files.exists(directory.resolve(NotificationRoiEvidence.PNG_FILE)))
        assertFalse(Files.exists(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE)))
    }
    @Test fun foreignManifestCollisionIsNeverRemovedByRollback() {
        val base = Files.createTempDirectory("roi-foreign-")
        val directory = base.resolve(NotificationRoiEvidence.DIRECTORY).resolve(receipt)
        failure(NotificationRoiEvidence.Code.ROI_WRITE) {
            NotificationRoiEvidence.write(base, receipt, 123, 4, 3, png(), 1, proofs()) {
                Files.write(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE), byteArrayOf(7), StandardOpenOption.CREATE_NEW)
            }
        }
        assertFalse(Files.exists(directory.resolve(NotificationRoiEvidence.PNG_FILE)))
        assertArrayEquals(byteArrayOf(7), Files.readAllBytes(directory.resolve(NotificationRoiEvidence.MANIFEST_FILE)))
    }
    @Test fun preexistingReceiptDirectoryAndHistoricalFilesRemainUntouched() {
        val base = Files.createTempDirectory("roi-existing-")
        val directory = Files.createDirectories(base.resolve(NotificationRoiEvidence.DIRECTORY).resolve(receipt))
        val historical = Files.write(directory.resolve("historical"), byteArrayOf(9), StandardOpenOption.CREATE_NEW)
        failure(NotificationRoiEvidence.Code.ROI_WRITE) { NotificationRoiEvidence.write(base, receipt, 123, 4, 3, png(), 1, proofs()) }
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(historical))
    }
    @Test fun symlinkBaseRootAndReceiptAreNeverFollowed() {
        for (site in 0..2) {
            val parent = Files.createTempDirectory("roi-symlink-")
            val foreign = Files.createTempDirectory("roi-foreign-target-")
            val base = if (site == 0) parent.resolve("base") else parent
            val link = when (site) {
                0 -> base
                1 -> base.resolve(NotificationRoiEvidence.DIRECTORY)
                else -> Files.createDirectory(base.resolve(NotificationRoiEvidence.DIRECTORY)).resolve(receipt)
            }
            Files.createSymbolicLink(link, foreign)
            failure(NotificationRoiEvidence.Code.ROI_WRITE) { NotificationRoiEvidence.write(base, receipt, 123, 4, 3, png(), 1, proofs()) }
            assertTrue(Files.isSymbolicLink(link))
            Files.list(foreign).use { assertEquals(0, it.count()) }
        }
    }
    @Test fun nonDirectoryBaseAndRootFailWithoutOverwrite() {
        val parent = Files.createTempDirectory("roi-not-directory-")
        val baseFile = Files.write(parent.resolve("base"), byteArrayOf(8), StandardOpenOption.CREATE_NEW)
        failure(NotificationRoiEvidence.Code.ROI_WRITE) { NotificationRoiEvidence.write(baseFile, receipt, 123, 4, 3, png(), 1, proofs()) }
        assertArrayEquals(byteArrayOf(8), Files.readAllBytes(baseFile))
        val root = Files.write(parent.resolve(NotificationRoiEvidence.DIRECTORY), byteArrayOf(9), StandardOpenOption.CREATE_NEW)
        failure(NotificationRoiEvidence.Code.ROI_WRITE) { NotificationRoiEvidence.write(parent, receipt, 123, 4, 3, png(), 1, proofs()) }
        assertArrayEquals(byteArrayOf(9), Files.readAllBytes(root))
    }
}
