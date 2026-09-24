package com.example.agent.rootpilot.files

import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileBackupExportInstrumentedTest {
    @Test fun rejectsNonDocumentTargetsBeforeOpeningOutput() {
        var resolverAccesses = 0
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getContentResolver(): ContentResolver {
                resolverAccesses++
                error("Must not open output")
            }
        }
        listOf("file:///data/user/0/com.example.agent/files/private", "https://example.com/document",
            "content://invalid.rootpilot.fixture/plain").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                exportBackupDocument(context, Uri.parse(value), "backup".toByteArray())
            }
        }
        assertEquals(0, resolverAccesses)
    }
}
