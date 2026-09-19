package com.example.agent.rootpilot

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotApiConfigStoreInstrumentedTest {
    // Tests use their own alias and directory, never the app's saved configuration or key.
    private val alias = "rootpilot_api_config_test_${UUID.randomUUID()}"
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "rootpilot-api-test-").toFile()
    }

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun keystoreKeyAndEncryptedConfigSurviveNewInstances() {
        val file = directory.resolve(RootPilotApiConfigStore.FILE_NAME)
        val config = RootPilotApiConfig(apiKey = "instrumentation-test-only-token")
        fun store() = RootPilotApiConfigStore(file, AesGcmApiConfigCipher(provider()))

        store().save(config)
        val first = file.readBytes()
        assertEquals(config, store().read())
        store().save(config)
        val second = file.readBytes()

        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertFalse(second.toString(Charsets.ISO_8859_1).contains(config.apiKey))
        val key = provider().getKey()
        assertNull(key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contains(KeyProperties.BLOCK_MODE_GCM))
        assertTrue(info.encryptionPaddings.contains(KeyProperties.ENCRYPTION_PADDING_NONE))

        val damaged = second.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(GeneralSecurityException::class.java) { store().read() }
        assertArrayEquals(damaged, file.readBytes())

        store().clear()
        assertNull(store().read())
        store().save(config)
        assertEquals(config, store().read())
    }

    @Test
    fun concurrentProvidersKeepOneKeyForAllCiphertexts() {
        val executor = Executors.newFixedThreadPool(6)
        val plaintext = "non-sensitive-keystore-test".encodeToByteArray()
        try {
            val jobs = List(6) {
                Callable { AesGcmApiConfigCipher(provider()).encrypt(plaintext) }
            }
            val encrypted = executor.invokeAll(jobs, 30, TimeUnit.SECONDS).map { it.get() }
            val recreated = AesGcmApiConfigCipher(provider())
            encrypted.forEach { assertArrayEquals(plaintext, recreated.decrypt(it)) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun provider() = AndroidKeystoreApiConfigKeyProvider(alias)
}
