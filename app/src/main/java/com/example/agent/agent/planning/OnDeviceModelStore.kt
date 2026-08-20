package com.example.agent.agent.planning

import android.content.Context
import java.io.File

class OnDeviceModelStore(context: Context) {
    private val appContext = context.applicationContext

    val modelFile: File
        get() = File(appContext.filesDir, "models/$MODEL_FILE_NAME")

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > 0

    companion object {
        const val MODEL_FILE_NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
    }
}
