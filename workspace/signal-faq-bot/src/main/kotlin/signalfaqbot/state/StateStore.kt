package signalfaqbot.state

import signalfaqbot.model.IncomingMessage
import signalfaqbot.model.MessageRecord
import signalfaqbot.model.MessageStatus

/**
 * Tracks every message the bot has seen. The bot loop consults this before
 * doing any work, so a message already `PROCESSING` or `ANSWERED` is never
 * picked up twice — including across a restart.
 */
interface StateStore {
    fun all(): List<MessageRecord>
    fun find(id: String): MessageRecord?
    fun markReceived(message: IncomingMessage)
    fun markProcessing(id: String)
    fun markAnswered(id: String, answerText: String)
    /** A practical question got the static redirect message instead of an LLM-crafted answer. */
    fun markRedirected(id: String, text: String)
    fun markFailed(id: String, error: String)
    /** Intentionally not answered — a member's message, or not classified as a question. */
    fun markSkipped(id: String, reason: String)
}
