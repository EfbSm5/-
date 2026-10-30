package com.example.agent.rootpilot

import java.io.InputStream

/** Wire format for the single-use androidTest provisioner; never packaged in the app. */
internal object RootPilotApiProvisionProtocol {
    const val MAX_KEY_BYTES = 4096
    const val CHALLENGE_BYTES = 32

    fun trustedPeer(uid: Int): Boolean = uid == 0 || uid == 2000

    fun hello(challenge: ByteArray): ByteArray {
        if (challenge.size != CHALLENGE_BYTES) throw Failure(Code.CHALLENGE)
        return "RPAP1\n".toByteArray(Charsets.US_ASCII) + challenge
    }

    // The caller sets the socket's remaining deadline before every potentially blocking read.
    fun readKey(input: InputStream, beforeRead: () -> Unit = {}): ByteArray {
        var payload: ByteArray? = null
        var accepted = false
        try {
            val header = ByteArray(4)
            readFully(input, header, beforeRead)
            var length = 0
            header.forEach { length = (length shl 8) or (it.toInt() and 0xff) }
            if (length !in 1..MAX_KEY_BYTES) throw Failure(Code.LENGTH)
            val bytes = ByteArray(length)
            payload = bytes
            readFully(input, bytes, beforeRead)
            beforeRead()
            if (input.read() != -1) throw Failure(Code.TRAILING)
            if (bytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) throw Failure(Code.KEY_FORMAT)
            accepted = true
            return bytes
        } catch (error: Failure) {
            throw error
        } catch (_: Throwable) {
            // Input implementations can include received content in their exception messages.
            throw Failure(Code.IO)
        } finally {
            if (!accepted) payload?.fill(0)
        }
    }

    private fun readFully(input: InputStream, bytes: ByteArray, beforeRead: () -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            beforeRead()
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) throw Failure(Code.TRUNCATED)
            offset += count
        }
    }

    enum class Code { CHALLENGE, LENGTH, TRUNCATED, TRAILING, KEY_FORMAT, IO }
    class Failure(val code: Code) : RuntimeException(code.name)
}
