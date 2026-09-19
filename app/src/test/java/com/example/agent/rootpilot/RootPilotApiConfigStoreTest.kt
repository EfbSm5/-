package com.example.agent.rootpilot

import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootPilotApiConfigStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val config = RootPilotApiConfig(
        apiKey = "test-only-token-1234567890",
        baseUrl = "https://test-user:test-password@example.invalid/v1?credential=test-secret",
        model = "test-model",
    )

    @Test
    fun configDefaultsAndRedactedToString() {
        assertEquals("", RootPilotApiConfig().apiKey)
        assertEquals("https://api.deepseek.com", RootPilotApiConfig().baseUrl)
        assertEquals("deepseek-flash", RootPilotApiConfig().model)
        assertEquals("RootPilotApiConfig(<redacted>)", config.toString())
    }

    @Test
    fun applyToChangesOnlyApiFields() {
        val original = RootPilotConfig(
            apiKey = "old-test-key",
            baseUrl = "https://old.invalid",
            model = "old-model",
            task = "测试任务",
            manualConfirmation = false,
            allowScreenUpload = true,
        )

        assertEquals(
            original.copy(apiKey = config.apiKey, baseUrl = config.baseUrl, model = config.model),
            config.applyTo(original),
        )
    }

    @Test
    fun missingFileReturnsNullWithoutAccessingKeyProvider() {
        val provider = object : ApiConfigKeyProvider {
            override fun getOrCreateKey(): SecretKey = error("Unexpected key creation")
            override fun getKey(): SecretKey = error("Unexpected key access")
        }
        assertNull(RootPilotApiConfigStore(file(), AesGcmApiConfigCipher(provider)).read())
    }

    @Test
    fun saveReadAndRecreateStorePreserveAllApiFields() {
        val file = file()
        val store = RootPilotApiConfigStore(file, cipher())

        store.save(config)

        assertEquals(config, store.read())
        assertEquals(config, RootPilotApiConfigStore(file, cipher()).read())
    }

    @Test
    fun defaultConfigAndUnicodeValuesRoundTrip() {
        val store = RootPilotApiConfigStore(file(), cipher())
        listOf(RootPilotApiConfig(), config.copy(apiKey = "测试令牌🔐\n\"\\")).forEach {
            store.save(it)
            assertEquals(it, store.read())
        }
    }

    @Test
    fun saveReplacesExistingConfigAndCreatesMissingDirectory() {
        val file = temporaryFolder.root.resolve("nested/config.enc")
        val store = RootPilotApiConfigStore(file, cipher())
        store.save(config)
        val replacement = RootPilotApiConfig("second-test-token", "https://second.invalid", "other")

        store.save(replacement)

        assertEquals(replacement, RootPilotApiConfigStore(file, cipher()).read())
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun ciphertextDoesNotContainCredentialsAndUsesFreshIvForEverySave() {
        val file = file()
        val store = RootPilotApiConfigStore(file, cipher())
        store.save(config)
        val first = file.readBytes()
        store.save(config)
        val second = file.readBytes()

        for (encrypted in listOf(first, second)) {
            val text = encrypted.toString(Charsets.ISO_8859_1)
            assertFalse(text.contains(config.apiKey))
            assertFalse(text.contains(config.baseUrl))
            assertFalse(text.contains(config.model))
            assertFalse(text.contains("apiKey"))
        }
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertEquals(listOf(file.name), temporaryFolder.root.list()!!.toList())
    }

    @Test
    fun clearIsIdempotentAndConfigCanBeSavedAgain() {
        val file = file()
        val store = RootPilotApiConfigStore(file, cipher())
        store.clear()
        store.save(config)
        RootPilotApiConfigStore(file, cipher()).clear()
        store.clear()

        assertFalse(file.exists())
        assertNull(store.read())
        store.save(config)
        assertEquals(config, store.read())
    }

    @Test
    fun damagedEnvelopeThrowsAndIsNotSilentlyReplaced() {
        val file = file()
        val store = RootPilotApiConfigStore(file, cipher())
        val unsupported = cipher().encrypt("{}".encodeToByteArray()).apply { this[0] = 2 }
        for (damaged in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), unsupported)) {
            file.writeBytes(damaged)

            assertThrows(IllegalArgumentException::class.java) { store.read() }

            assertArrayEquals(damaged, file.readBytes())
        }
    }

    @Test
    fun modifiedIvCiphertextOrTagFailsAuthenticationWithoutChangingFile() {
        val file = file()
        val store = RootPilotApiConfigStore(file, cipher())
        store.save(config)
        val original = file.readBytes()
        for (index in listOf(1, 13, original.lastIndex)) {
            val damaged = original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            file.writeBytes(damaged)

            assertThrows(AEADBadTagException::class.java) { store.read() }

            assertArrayEquals(damaged, file.readBytes())
        }
    }

    @Test
    fun wrongKeyFailsWithoutChangingFile() {
        val file = file()
        RootPilotApiConfigStore(file, cipher()).save(config)
        val encrypted = file.readBytes()
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertThrows(AEADBadTagException::class.java) {
            RootPilotApiConfigStore(file, cipher(otherKey)).read()
        }

        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test
    fun missingKeyOnReadIsReportedWithoutGeneratingReplacement() {
        val file = file()
        RootPilotApiConfigStore(file, cipher()).save(config)
        val encrypted = file.readBytes()
        val provider = object : ApiConfigKeyProvider {
            override fun getOrCreateKey(): SecretKey = error("Must not replace a missing key on read")
            override fun getKey(): SecretKey = throw KeyStoreException("Missing test key")
        }

        assertThrows(KeyStoreException::class.java) {
            RootPilotApiConfigStore(file, AesGcmApiConfigCipher(provider)).read()
        }

        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test
    fun malformedDecryptedJsonThrowsWithoutLeakingCredentialsOrChangingFile() {
        val file = file()
        val invalid = """{"apiKey":"${config.apiKey}","baseUrl":{}}"""
        val encrypted = cipher().encrypt(invalid.encodeToByteArray())
        file.writeBytes(encrypted)

        val error = assertThrows(IOException::class.java) {
            RootPilotApiConfigStore(file, cipher()).read()
        }

        assertFalse(error.stackTraceToString().contains(config.apiKey))
        assertArrayEquals(encrypted, file.readBytes())
    }

    @Test
    fun encryptionFailureKeepsPreviouslySavedConfigAndLeavesNoTemporaryFile() {
        val file = file()
        RootPilotApiConfigStore(file, cipher()).save(config)
        val original = file.readBytes()
        val failingCipher = object : ApiConfigCipher by cipher() {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IOException("Test failure")
        }

        assertThrows(IOException::class.java) {
            RootPilotApiConfigStore(file, failingCipher).save(config.copy(apiKey = "replacement"))
        }

        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), temporaryFolder.root.list()!!.toList())
    }

    @Test
    fun failedAtomicReplacementCleansUpTemporaryCiphertext() {
        val destination = temporaryFolder.newFolder("existing-directory")
        val existing = destination.resolve("keep").apply { writeText("test data") }

        assertThrows(IOException::class.java) {
            RootPilotApiConfigStore(destination, cipher()).save(config)
        }

        assertEquals("test data", existing.readText())
        assertEquals(listOf(destination.name), temporaryFolder.root.list()!!.toList())
    }

    @Test
    fun directoryReadAndFailedClearAreReportedAsIoFailures() {
        val directory = temporaryFolder.newFolder("not-a-file")
        directory.resolve("keep").writeText("test data")
        val store = RootPilotApiConfigStore(directory, cipher())

        assertThrows(IOException::class.java) { store.read() }
        assertThrows(IOException::class.java) { store.clear() }
    }

    @Test
    fun multipleInstancesSerializeIoAndCipherOperations() {
        val file = file()
        val activeOperations = AtomicInteger()
        val maximumConcurrency = AtomicInteger()
        val delegate = cipher()
        val trackingCipher = object : ApiConfigCipher {
            private fun track(block: () -> ByteArray): ByteArray {
                val active = activeOperations.incrementAndGet()
                maximumConcurrency.updateAndGet { maxOf(it, active) }
                return try {
                    Thread.yield()
                    block()
                } finally {
                    activeOperations.decrementAndGet()
                }
            }

            override fun encrypt(plaintext: ByteArray): ByteArray = track { delegate.encrypt(plaintext) }
            override fun decrypt(ciphertext: ByteArray): ByteArray = track { delegate.decrypt(ciphertext) }
        }
        val stores = List(6) { RootPilotApiConfigStore(file, trackingCipher) }
        val executor = Executors.newFixedThreadPool(stores.size)
        try {
            val jobs = stores.map { store ->
                Callable {
                    repeat(10) {
                        store.save(config)
                        val result = store.read()
                        assertTrue(result == null || result == config)
                        store.clear()
                    }
                }
            }
            executor.invokeAll(jobs, 15, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, maximumConcurrency.get())
        assertNull(stores.first().read())
        assertTrue(temporaryFolder.root.list()!!.isEmpty())
    }

    private fun file(): File = temporaryFolder.root.resolve(RootPilotApiConfigStore.FILE_NAME)

    private fun cipher(secretKey: SecretKey = key): ApiConfigCipher = AesGcmApiConfigCipher(
        object : ApiConfigKeyProvider {
            override fun getOrCreateKey(): SecretKey = secretKey
            override fun getKey(): SecretKey = secretKey
        },
    )
}
