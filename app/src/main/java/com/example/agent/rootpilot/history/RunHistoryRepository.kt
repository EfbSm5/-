package com.example.agent.rootpilot.history

import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A process-owned worker serializes loading, events and clearing off the execution path. */
internal class RunHistoryRepository(
    private val storage: RunHistoryStorage,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(RunHistoryState())
    val state: StateFlow<RunHistoryState> = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                val loaded = storage.read()
                val interrupted = loaded.map {
                    // The last observed duration is a lower bound; no wall-clock death time is invented.
                    if (it.status == RunHistoryStatus.RUNNING) it.copy(status = RunHistoryStatus.INTERRUPTED) else it
                }
                mutableState.value = RunHistoryState(interrupted)
                if (loaded != interrupted) persist()
            } catch (_: Exception) {
                mutableState.value = RunHistoryState(error = RunHistoryError.READ_FAILED)
            }
            for (command in commands) handle(command)
        }
    }

    fun begin(id: String, startedAtEpochMs: Long = System.currentTimeMillis()) {
        commands.trySend(Command.Begin(id, startedAtEpochMs))
    }

    fun record(event: RunTraceEvent) { commands.trySend(Command.Event(event)) }

    fun finish(id: String, status: RunHistoryStatus, durationMs: Long, reason: TraceReason) {
        commands.trySend(Command.Finish(id, status, durationMs, reason))
    }

    fun clear() { commands.trySend(Command.Clear) }

    private fun handle(command: Command) {
        val records = mutableState.value.records
        val next = when (command) {
            is Command.Begin -> listOf(RunHistoryRecord(command.id, command.startedAtEpochMs))
                .plus(records.filterNot { it.id == command.id }).take(MAX_RECORDS)
            is Command.Event -> records.map { record ->
                if (record.id != command.event.runId || record.status != RunHistoryStatus.RUNNING) record else {
                    val event = command.event
                    record.copy(
                        durationMs = maxOf(record.durationMs, event.elapsedMs),
                        stepCount = maxOf(record.stepCount, event.step + 1),
                        events = (record.events + event).takeLast(MAX_EVENTS),
                        eventsTruncated = record.eventsTruncated || record.events.size >= MAX_EVENTS,
                    )
                }
            }
            is Command.Finish -> records.map { record ->
                if (record.id != command.id || record.status != RunHistoryStatus.RUNNING) record
                else record.copy(status = command.status, durationMs = maxOf(record.durationMs, command.durationMs),
                    reason = command.reason)
            }
            Command.Clear -> {
                try {
                    storage.clear()
                    mutableState.value = RunHistoryState()
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(error = RunHistoryError.CLEAR_FAILED)
                }
                return
            }
        }
        // Late events and completion after clear cannot create a record or rewrite the file.
        if (next == records) return
        mutableState.value = mutableState.value.copy(records = next)
        persist()
    }

    private fun persist() {
        try {
            storage.write(mutableState.value.records)
            if (mutableState.value.error == RunHistoryError.WRITE_FAILED) {
                mutableState.value = mutableState.value.copy(error = null)
            }
        } catch (_: Exception) {
            if (mutableState.value.error !in setOf(RunHistoryError.READ_FAILED, RunHistoryError.CLEAR_FAILED)) {
                mutableState.value = mutableState.value.copy(error = RunHistoryError.WRITE_FAILED)
            }
        }
    }

    private sealed interface Command {
        data class Begin(val id: String, val startedAtEpochMs: Long) : Command
        data class Event(val event: RunTraceEvent) : Command
        data class Finish(val id: String, val status: RunHistoryStatus, val durationMs: Long,
            val reason: TraceReason) : Command
        data object Clear : Command
    }

    companion object {
        const val MAX_RECORDS = 50
        const val MAX_EVENTS = 256
    }
}
