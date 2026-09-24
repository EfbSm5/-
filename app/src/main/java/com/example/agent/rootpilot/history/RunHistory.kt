package com.example.agent.rootpilot.history

import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.log.TraceReason
import kotlinx.serialization.Serializable

@Serializable
enum class RunHistoryStatus { RUNNING, COMPLETED, FAILED, STOPPED, INTERRUPTED }

enum class RunHistoryError { READ_FAILED, WRITE_FAILED, CLEAR_FAILED }

@Serializable
data class RunHistoryRecord(
    val id: String,
    val startedAtEpochMs: Long,
    val durationMs: Long = 0,
    val status: RunHistoryStatus = RunHistoryStatus.RUNNING,
    val stepCount: Int = 0,
    val events: List<RunTraceEvent> = emptyList(),
    val eventsTruncated: Boolean = false,
    val reason: TraceReason = TraceReason.NONE,
)

data class RunHistoryState(
    val records: List<RunHistoryRecord> = emptyList(),
    val error: RunHistoryError? = null,
)
