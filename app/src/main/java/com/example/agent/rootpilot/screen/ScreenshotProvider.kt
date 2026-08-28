package com.example.agent.rootpilot.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import java.io.ByteArrayOutputStream

data class ScreenshotFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val dataUrl: String,
    val physicalWidth: Int = width,
    val physicalHeight: Int = height,
)

sealed interface ScreenshotCaptureResult {
    data class Success(val frame: ScreenshotFrame) : ScreenshotCaptureResult

    data class Failure(val message: String) : ScreenshotCaptureResult
}

interface ScreenshotProvider {
    suspend fun capture(): ScreenshotCaptureResult
}

class RootScreenshotProvider(
    private val rootExecutor: RootExecutor,
    private val maxDimension: Int = 1_280,
    private val jpegQuality: Int = 72,
) : ScreenshotProvider {
    override suspend fun capture(): ScreenshotCaptureResult = when (
        val result = rootExecutor.captureScreen()
    ) {
        is RootScreenshotResult.Failure -> ScreenshotCaptureResult.Failure(result.message)
        is RootScreenshotResult.Success -> encode(result.pngBytes)
    }

    private fun encode(pngBytes: ByteArray): ScreenshotCaptureResult = try {
        val source = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: return ScreenshotCaptureResult.Failure("Root 截图无法解码")
        val physicalWidth = source.width
        val physicalHeight = source.height
        val scaled = scale(source)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
        val width = scaled.width
        val height = scaled.height
        if (scaled !== source) source.recycle()
        scaled.recycle()
        val bytes = output.toByteArray()
        ScreenshotCaptureResult.Success(
            ScreenshotFrame(
                bytes = bytes,
                width = width,
                height = height,
                dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                physicalWidth = physicalWidth,
                physicalHeight = physicalHeight,
            ),
        )
    } catch (_: Exception) {
        ScreenshotCaptureResult.Failure("截图压缩失败")
    }

    private fun scale(source: Bitmap): Bitmap {
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= maxDimension) return source
        val scale = maxDimension.toFloat() / largestSide
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { setScale(scale, scale) },
            true,
        )
    }
}
