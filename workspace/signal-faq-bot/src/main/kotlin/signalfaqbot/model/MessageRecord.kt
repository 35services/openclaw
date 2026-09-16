package signalfaqbot.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle of a message as it moves through the bot. Written to the state
 * file *before* the (potentially slow) LLM call starts, so a crash mid-flight
 * leaves an honest `PROCESSING` record instead of silently losing the message
 * or answering it twice on restart.
 */
@Serializable
enum class MessageStatus { RECEIVED, PROCESSING, ANSWERED, FAILED }

/** One row of the state file: a message plus where it currently stands. */
@Serializable
data class MessageRecord(
    val message: IncomingMessage,
    val status: MessageStatus,
    /** Epoch millis of the last status change. */
    val updatedAt: Long,
    val answerText: String? = null,
    val error: String? = null,
)
