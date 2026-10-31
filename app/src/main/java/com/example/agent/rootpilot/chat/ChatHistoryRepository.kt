package com.example.agent.rootpilot.chat

import android.content.Context
import com.example.agent.rootpilot.AesGcmApiConfigCipher
import com.example.agent.rootpilot.AndroidKeystoreApiConfigKeyProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ChatHistoryError { READ_FAILED, WRITE_FAILED, DELETE_FAILED, CLEAR_FAILED }

data class ChatHistoryState(
    val sessions: List<ChatSessionSummary> = emptyList(),
    val loading: Boolean = true,
    val unreadable: Int = 0,
    val error: ChatHistoryError? = null,
)

/** A process-owned worker serializes listing, saves, reads, deletes and clearing in submission order. */
class ChatHistoryRepository internal constructor(
    private val storage: ChatHistoryStorage,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ChatHistoryState())
    val state: StateFlow<ChatHistoryState> = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            mutableState.value = try {
                storage.list().let { ChatHistoryState(it.sessions, loading = false, unreadable = it.unreadable) }
            } catch (_: Exception) {
                ChatHistoryState(loading = false, error = ChatHistoryError.READ_FAILED)
            }
            for (command in commands) handle(command)
        }
    }

    fun save(session: SavedChatSession) { commands.trySend(Command.Save(session)) }

    /** Returns null when the session is missing or cannot be decrypted and validated. */
    suspend fun open(id: String): SavedChatSession? {
        val result = CompletableDeferred<SavedChatSession?>()
        commands.trySend(Command.Open(id, result))
        return result.await()
    }

    fun delete(id: String) { commands.trySend(Command.Delete(id)) }

    fun clear() { commands.trySend(Command.Clear) }

    private fun handle(command: Command) {
        val current = mutableState.value
        when (command) {
            is Command.Save -> {
                try {
                    storage.write(command.session)
                } catch (_: Exception) {
                    mutableState.value = current.copy(error = ChatHistoryError.WRITE_FAILED)
                    return
                }
                // The saved session is the most recent activity even if the wall clock moved backwards.
                val ordered = listOf(command.session.summary()) + current.sessions.filterNot { it.id == command.session.id }
                val kept = ordered.take(MAX_SESSIONS).toMutableList()
                var pruneFailed = false
                ordered.drop(MAX_SESSIONS).forEach { victim ->
                    try {
                        storage.delete(victim.id)
                    } catch (_: Exception) {
                        kept += victim
                        pruneFailed = true
                    }
                }
                mutableState.value = current.copy(sessions = kept,
                    error = if (pruneFailed) ChatHistoryError.DELETE_FAILED else current.error.afterSuccess())
            }
            is Command.Open -> command.result.complete(try {
                storage.read(command.id).also { session ->
                    if (session == null) {
                        mutableState.value = current.copy(sessions = current.sessions.filterNot { it.id == command.id })
                    }
                }
            } catch (_: Exception) {
                // Damaged files stay listed so the user can delete them explicitly.
                null
            })
            is Command.Delete -> mutableState.value = try {
                storage.delete(command.id)
                current.copy(sessions = current.sessions.filterNot { it.id == command.id }, error = current.error.afterSuccess())
            } catch (_: Exception) {
                current.copy(error = ChatHistoryError.DELETE_FAILED)
            }
            Command.Clear -> mutableState.value = try {
                storage.clear()
                ChatHistoryState(loading = false)
            } catch (_: Exception) {
                // Some files may already be gone; show what remains on disk.
                try {
                    storage.list().let { current.copy(sessions = it.sessions, unreadable = it.unreadable) }
                } catch (_: Exception) {
                    current
                }.copy(error = ChatHistoryError.CLEAR_FAILED)
            }
        }
    }

    // An unknown listing stays flagged until a successful clear rebuilds it.
    private fun ChatHistoryError?.afterSuccess() = takeIf { it == ChatHistoryError.READ_FAILED }

    private sealed interface Command {
        data class Save(val session: SavedChatSession) : Command
        data class Open(val id: String, val result: CompletableDeferred<SavedChatSession?>) : Command
        data class Delete(val id: String) : Command
        data object Clear : Command
    }

    companion object {
        const val MAX_SESSIONS = 30

        @Volatile
        private var shared: ChatHistoryRepository? = null

        fun shared(context: Context): ChatHistoryRepository = shared ?: synchronized(this) {
            shared ?: ChatHistoryRepository(
                EncryptedChatHistoryStore(
                    File(context.applicationContext.noBackupFilesDir, EncryptedChatHistoryStore.DIRECTORY),
                    AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider(EncryptedChatHistoryStore.KEY_ALIAS)),
                ),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { shared = it }
        }
    }
}
