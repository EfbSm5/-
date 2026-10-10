package com.example.agent.rootpilot.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 截图预览的解码状态：把“还没有帧”“后台解码中”“解码失败”“已就绪”分开，
 * 这样后台解码期间不会闪出失败文案，也不会丢掉上一帧。
 */
internal sealed interface FrameImageState {
    data object Empty : FrameImageState
    data object Decoding : FrameImageState
    data object Undecodable : FrameImageState
    data class Ready(val image: ImageBitmap) : FrameImageState
}

/**
 * 在后台线程解码截图 PNG（整屏图每步一次，不放主线程），解码期间保留上一帧。
 */
@Composable
internal fun rememberFrameImageState(bytes: ByteArray?): FrameImageState {
    val state = remember { mutableStateOf<FrameImageState>(FrameImageState.Empty) }
    LaunchedEffect(bytes) {
        if (bytes == null) {
            state.value = FrameImageState.Empty
            return@LaunchedEffect
        }
        if (state.value !is FrameImageState.Ready) state.value = FrameImageState.Decoding
        state.value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }?.let { FrameImageState.Ready(it) } ?: FrameImageState.Undecodable
    }
    return state.value
}
