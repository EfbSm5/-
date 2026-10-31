package com.example.agent.rootpilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.chat.EncryptedChatHistoryStore
import com.example.agent.rootpilot.chat.SavedChatMessage
import com.example.agent.rootpilot.chat.SavedChatSession
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryStoreInstrumentedTest {
    // Tests use their own alias and directory, never the app's saved chat history or key.
    private val alias = "rootpilot_chat_history_test_${UUID.randomUUID()}"
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "rootpilot-chat-test-").toFile()
    }

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun keystoreEncryptedSessionSurvivesNewInstancesAndClear() {
        fun store() = EncryptedChatHistoryStore(directory,
            AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider(alias)))
        val session = SavedChatSession(UUID.randomUUID().toString(), "仪器测试问题", 1, listOf(
            SavedChatMessage("user", "仪器测试问题"), SavedChatMessage("assistant", "INSTRUMENTATION_ONLY_ANSWER"),
        ))

        store().write(session)
        assertEquals(session, store().read(session.id))
        assertEquals(1, store().list().sessions.size)
        val bytes = directory.resolve("${session.id}.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("INSTRUMENTATION_ONLY_ANSWER"))

        store().clear()
        assertNull(store().read(session.id))
        assertEquals(0, store().list().sessions.size)
    }
}
