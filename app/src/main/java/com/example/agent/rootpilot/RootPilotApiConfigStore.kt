package com.example.agent.rootpilot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RootPilotApiConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
) {
    fun applyTo(config: RootPilotConfig): RootPilotConfig = config.copy(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
    )

    override fun toString(): String = "RootPilotApiConfig(<redacted>)"
}

interface ApiConfigCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

// All store instances and Keystore providers share this lock within the app process.
private val apiConfigLock = Any()

/** Blocking storage operations; callers are responsible for dispatching to IO. */
class RootPilotApiConfigStore(
    private val file: File,
    private val cipher: ApiConfigCipher,
) {
    private val json = Json { encodeDefaults = true }

    fun read(): RootPilotApiConfig? = synchronized(apiConfigLock) {
        val ciphertext = try {
            Files.readAllBytes(file.toPath())
        } catch (_: NoSuchFileException) {
            return@synchronized null
        }
        val plaintext = cipher.decrypt(ciphertext)
        try {
            json.decodeFromString<RootPilotApiConfig>(plaintext.decodeToString(throwOnInvalidSequence = true))
        } catch (_: SerializationException) {
            // Serialization exceptions can include the decrypted JSON, including credentials.
            throw IOException("Invalid RootPilot API configuration format")
        } finally {
            plaintext.fill(0)
        }
    }

    fun save(config: RootPilotApiConfig): Unit = synchronized(apiConfigLock) {
        val plaintext = json.encodeToString(config).encodeToByteArray()
        val ciphertext = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val destination = file.absoluteFile.toPath()
        val parent = destination.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "rootpilot-api-", ".enc.tmp")
        try {
            temporary.toFile().outputStream().use { output ->
                output.write(ciphertext)
                output.fd.sync()
            }
            // Keep the previous file intact if atomic replacement is unsupported or fails.
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun clear(): Unit = synchronized(apiConfigLock) {
        Files.deleteIfExists(file.toPath())
        Unit
    }

    companion object {
        const val FILE_NAME = "rootpilot_api_config.enc"

        fun create(context: Context): RootPilotApiConfigStore = RootPilotApiConfigStore(
            file = File(context.noBackupFilesDir, FILE_NAME),
            cipher = AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider()),
        )
    }
}

internal interface ApiConfigKeyProvider {
    fun getOrCreateKey(): SecretKey

    fun getKey(): SecretKey
}

internal class AesGcmApiConfigCipher(
    private val keyProvider: ApiConfigKeyProvider,
) : ApiConfigCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Let the provider generate a new random IV, as required by Android Keystore.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
        check(cipher.iv.size == IV_BYTES) { "Unexpected AES-GCM IV length" }
        cipher.updateAAD(byteArrayOf(VERSION))
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 1 + IV_BYTES + TAG_BITS / 8) { "Truncated API configuration" }
        require(ciphertext[0] == VERSION) { "Unsupported API configuration version" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.getKey(),
            GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES),
        )
        cipher.updateAAD(byteArrayOf(VERSION))
        return cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.size - 1 - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

internal class AndroidKeystoreApiConfigKeyProvider(
    private val alias: String = "rootpilot_api_config_aes_v1",
) : ApiConfigKeyProvider {
    override fun getOrCreateKey(): SecretKey = synchronized(apiConfigLock) {
        val existing = keyStore().getKey(alias, null)
        if (existing != null) return@synchronized existing as SecretKey
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    override fun getKey(): SecretKey = synchronized(apiConfigLock) {
        keyStore().getKey(alias, null) as SecretKey?
            ?: throw KeyStoreException("RootPilot API configuration key is missing")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
