package com.example.agent.rootpilot.files

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal object FileRules {
    const val MAX_BYTES = 64 * 1024
    const val MAX_ENTRIES = 100
    const val BACKUP_QUOTA = 5L * 1024 * 1024

    fun segments(path: String, allowRoot: Boolean = false): List<String> {
        if (path.isEmpty() && allowRoot) return emptyList()
        if (path.isEmpty() || path.length > 4096 || path.any { it == '\\' || it == ':' || it == '%' || it.isISOControl() }) {
            fail(FileErrorCode.INVALID_PATH)
        }
        return path.split('/').also { parts ->
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) fail(FileErrorCode.INVALID_PATH)
        }
    }

    fun encode(text: String): ByteArray {
        if (text.length > MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
        validateCharacters(text)
        val bytes = try {
            val buffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: Exception) {
            fail(FileErrorCode.INVALID_TEXT)
        }
        if (bytes.size > MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
        return bytes
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            fail(FileErrorCode.INVALID_TEXT)
        }
        validateCharacters(text)
        return text
    }

    private fun validateCharacters(text: String) {
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            fail(FileErrorCode.INVALID_TEXT)
        }
    }

    fun boundedRead(input: InputStream): ByteArray {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
        return bytes
    }

    fun edit(before: String, oldText: String, newText: String): String {
        encode(before)
        encode(oldText)
        encode(newText)
        if (oldText.isEmpty()) fail(FileErrorCode.EDIT_MATCH)
        val start = before.indexOf(oldText)
        if (start < 0 || before.indexOf(oldText, start + 1) >= 0) fail(FileErrorCode.EDIT_MATCH)
        return before.replaceRange(start, start + oldText.length, newText).also { encode(it) }
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun verify(expectedIdentity: List<String>, actualIdentity: List<String>, expectedHash: String?, actualHash: String?) {
        if (expectedIdentity != actualIdentity || expectedHash != actualHash) fail(FileErrorCode.CONFLICT)
    }
}
