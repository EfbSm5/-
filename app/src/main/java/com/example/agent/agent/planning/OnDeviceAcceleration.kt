package com.example.agent.agent.planning

import android.content.Context
import com.google.ai.edge.litertlm.Backend

enum class OnDeviceAcceleration {
    CPU_ONLY,
    GPU_PREFERRED,
    NPU_PREFERRED,
}

fun OnDeviceAcceleration.backends(context: Context): List<Backend> = when (this) {
    OnDeviceAcceleration.CPU_ONLY -> listOf(Backend.CPU())
    OnDeviceAcceleration.GPU_PREFERRED -> listOf(Backend.GPU(), Backend.CPU())
    OnDeviceAcceleration.NPU_PREFERRED -> listOf(
        Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
        Backend.GPU(),
        Backend.CPU(),
    )
}
