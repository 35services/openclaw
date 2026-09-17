package signalfaqbot.state

import signalfaqbot.model.IncomingMessage
import signalfaqbot.model.MessageRecord
import signalfaqbot.model.MessageStatus

/** No file I/O — used by tests and by the CLI's one-off `ask` command. */
class InMemoryStateStore : StateStore {
    private val records = linkedMapOf<String, MessageRecord>()

    override fun all(): List<MessageRecord> = records.values.toList()

    override fun find(id: String): MessageRecord? = records[id]

    override fun markReceived(message: IncomingMessage) {
        records.putIfAbsent(
            message.id,
            MessageRecord(message = message, status = MessageStatus.RECEIVED, updatedAt = System.currentTimeMillis()),
        )
    }

    override fun markProcessing(id: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(status = MessageStatus.PROCESSING, updatedAt = System.currentTimeMillis())
    }

    override fun markAnswered(id: String, answerText: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(
            status = MessageStatus.ANSWERED,
            answerText = answerText,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override fun markRedirected(id: String, text: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(status = MessageStatus.REDIRECTED, answerText = text, updatedAt = System.currentTimeMillis())
    }

    override fun markFailed(id: String, error: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(status = MessageStatus.FAILED, error = error, updatedAt = System.currentTimeMillis())
    }

    override fun markSkipped(id: String, reason: String) {
        val existing = records[id] ?: return
        records[id] = existing.copy(status = MessageStatus.SKIPPED, error = reason, updatedAt = System.currentTimeMillis())
    }
}
