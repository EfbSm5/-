package com.example.agent.rootpilot.deepseek

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
enum class ModelFailureCategory { REQUEST_CONTRACT, HTTP, NETWORK, TIMEOUT, RESPONSE_PROTOCOL, CONFIGURATION, UNKNOWN }

@Serializable
enum class ModelProtocolReason {
    INVALID_JSON, INVALID_SHAPE, INCOMPLETE_STREAM, STREAM_LIMIT, NULL_TOOL_CALLS, EMPTY_TOOL_CALLS,
    TOOL_IDENTITY, TOOL_ARGUMENTS, OUTPUT_LIMIT, UNSUPPORTED_FINISH, FINISH_MISMATCH, MISSING_FINISH,
    EMPTY_OUTPUT, SERVER_ERROR_CHUNK, POST_FINISH_CHUNK,
}

internal class ModelProtocolException(val reason: ModelProtocolReason) : SerializationException("Invalid model tool stream")

/** Fixed transport diagnostics; never include response bodies, URLs or exception messages. */
@Serializable
data class ModelFailure(
    val category: ModelFailureCategory = ModelFailureCategory.UNKNOWN,
    val httpStatus: Int? = null,
    val protocolReason: ModelProtocolReason? = null,
) {
    init {
        require(if (category == ModelFailureCategory.HTTP) httpStatus in 100..599 else httpStatus == null)
        require(category == ModelFailureCategory.RESPONSE_PROTOCOL || protocolReason == null)
    }
}
