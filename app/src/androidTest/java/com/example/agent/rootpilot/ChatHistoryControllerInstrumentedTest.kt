package com.example.agent.rootpilot

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.chat.ChatController
import com.example.agent.rootpilot.chat.ChatHistoryRepository
import com.example.agent.rootpilot.chat.EncryptedChatHistoryStore
import com.example.agent.rootpilot.deepseek.ChatTurn
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekChatClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the production chat controller and history worker against a real Keystore-encrypted store.
 * The model client is local, so no network request or API call is made.
 */
@RunWith(AndroidJUnit4::class)
class ChatHistoryControllerInstrumentedTest {
    // Tests use their own alias and directory, never the app's saved chat history, key or API config.
    private val alias = "rootpilot_chat_history_test_${UUID.randomUUID()}"
    private lateinit var directory: File
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(1_000)
    private val requests = CopyOnWriteArrayList<List<ChatTurn>>()
    private val client = object : DeepSeekChatClient {
        override suspend fun streamChat(
            config: RootPilotConfig,
            messages: List<ChatTurn>,
            effort: ThinkingEffort,
            onUpdate: suspend (ModelStreamSnapshot) -> Unit,
        ): DeepSeekActionResult {
            requests.add(messages)
            return DeepSeekActionResult.Success("本地回答 ${requests.size}")
        }
    }
    private val configA = RootPilotConfig(apiKey = "local-test-key-a", baseUrl = "https://api.deepseek.com", model = "deepseek-flash")
    private lateinit var repository: ChatHistoryRepository
    private lateinit var controller: ChatController

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "rootpilot-chat-controller-test-").toFile()
        repository = ChatHistoryRepository(store(), repositoryScope)
        controller = ChatController(chatScope, client, history = repository, clock = clock::incrementAndGet)
        await { !repository.state.value.loading }
    }

    @After
    fun tearDown() {
        chatScope.cancel()
        repositoryScope.cancel()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun thirtyFirstSessionPrunesOldestEncryptedFile() {
        val ids = (1..ChatHistoryRepository.MAX_SESSIONS + 1).map { index ->
            controller.newConversation()
            exchange(configA, "会话 $index")
        }

        assertEquals(ids.size, ids.toSet().size)
        await { repository.state.value.sessions.size == ChatHistoryRepository.MAX_SESSIONS && !sessionFile(ids.first()).exists() }
        assertEquals(ids.drop(1).reversed(), repository.state.value.sessions.map { it.id })
        assertEquals(ids.drop(1).toSet(), savedIds())
        assertEquals(ids.drop(1).toSet(), store().list().sessions.map { it.id }.toSet())
        assertNull(repository.state.value.error)
    }

    @Test
    fun changedApiConfigStartsNewSessionAndKeepsSavedOne() {
        val first = exchange(configA, "第一轮")
        assertEquals(first, exchange(configA, "同配置第二轮"))
        assertEquals(4, store().read(first)!!.messages.size)

        val changes = listOf(
            configA.copy(apiKey = "local-test-key-b"),
            configA.copy(apiKey = "local-test-key-b", baseUrl = "https://api.deepseek.com/v1"),
            configA.copy(apiKey = "local-test-key-b", baseUrl = "https://api.deepseek.com/v1", model = "deepseek-pro"),
        )
        val ids = mutableListOf(first)
        changes.forEachIndexed { index, config ->
            val id = exchange(config, "换配置 $index")
            assertFalse(id in ids)
            // A new session starts with only the new prompt, not the previous account's context.
            assertEquals(listOf(ChatTurn("user", "换配置 $index")), requests.last())
            assertEquals(2, controller.state.value.messages.size)
            ids += id
        }

        await { repository.state.value.sessions.size == ids.size }
        assertEquals(ids.toSet(), savedIds())
        assertEquals(4, store().read(first)!!.messages.size)
        ids.drop(1).forEach { assertEquals(2, store().read(it)!!.messages.size) }
    }

    @Test
    fun clearHistoryRemovesEveryEncryptedFileAndClosesOpenSession() {
        controller.newConversation()
        val older = exchange(configA, "旧会话")
        controller.newConversation()
        val open = exchange(configA, "当前会话")
        assertNotEquals(older, open)
        await { savedIds() == setOf(older, open) && repository.state.value.sessions.size == 2 }

        controller.clearHistory()

        await { repository.state.value.sessions.isEmpty() && savedIds().isEmpty() }
        assertNull(controller.state.value.sessionId)
        assertTrue(controller.state.value.messages.isEmpty())
        assertNull(repository.state.value.error)
        assertEquals(0, store().list().sessions.size)

        // A turn after clearing becomes a new saved session instead of restoring a cleared one.
        val after = exchange(configA, "清除后新会话")
        assertFalse(after == older || after == open)
        await { savedIds() == setOf(after) }
        assertEquals(2, store().read(after)!!.messages.size)
    }

    private fun store() = EncryptedChatHistoryStore(directory, AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider(alias)))

    /** Sends one prompt, waits until the completed exchange is saved, and returns its session id. */
    private fun exchange(config: RootPilotConfig, prompt: String): String {
        val sent = requests.size
        controller.updateDraft(prompt)
        controller.send(config, configured = true)
        await { requests.size == sent + 1 && !controller.state.value.generating && controller.state.value.sessionId != null }
        val id = controller.state.value.sessionId!!
        assertNull(controller.state.value.error)
        await { repository.state.value.sessions.firstOrNull()?.id == id && sessionFile(id).exists() }
        return id
    }

    private fun sessionFile(id: String) = directory.resolve("$id.enc")

    private fun savedIds() = directory.listFiles().orEmpty().map { it.name }
        .filter { it.endsWith(".enc") }.map { it.removeSuffix(".enc") }.toSet()

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for chat history state" }
            Thread.sleep(20)
        }
    }
}
