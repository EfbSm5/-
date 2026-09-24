package com.example.agent.rootpilot.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Picker results are untrusted; never open file:// targets with the app's private-file authority. */
internal fun exportBackupDocument(context: Context, target: Uri, bytes: ByteArray) {
    require(target.scheme == "content" && DocumentsContract.isDocumentUri(context, target)) {
        "Document target required"
    }
    val output = context.contentResolver.openOutputStream(target, "wt")
        ?: error("Output unavailable")
    output.use { it.write(bytes); it.flush() }
}
