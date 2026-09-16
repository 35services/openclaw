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
    fun markFailed(id: String, error: String)
}
