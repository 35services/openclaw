package signalfaqbot.state

import kotlinx.serialization.json.Json
import signalfaqbot.model.IncomingMessage
import signalfaqbot.model.MessageRecord
import signalfaqbot.model.MessageStatus
import java.io.File

/**
 * Persists [MessageRecord]s as a JSON array in a single file. Kept
 * deliberately simple (whole file rewritten on every change, read fully into
 * memory) — this bot handles one Signal group's traffic, not a firehose.
 *
 * Writes go through a temp file + rename so a crash mid-write can't leave a
 * half-written, unparseable state file behind.
 */
class JsonFileStateStore(private val path: String) : StateStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val records = linkedMapOf<String, MessageRecord>()

    init {
        val file = File(path)
        if (file.exists() && file.length() > 0) {
            val loaded = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MessageRecord.serializer()), file.readText())
            loaded.forEach { records[it.message.id] = it }
        }
    }

    override fun all(): List<MessageRecord> = synchronized(lock) { records.values.toList() }

    override fun find(id: String): MessageRecord? = synchronized(lock) { records[id] }

    override fun markReceived(message: IncomingMessage) = update {
        records.putIfAbsent(
            message.id,
            MessageRecord(message = message, status = MessageStatus.RECEIVED, updatedAt = System.currentTimeMillis()),
        )
    }

    override fun markProcessing(id: String) = update {
        val existing = records[id] ?: return@update
        records[id] = existing.copy(status = MessageStatus.PROCESSING, updatedAt = System.currentTimeMillis())
    }

    override fun markAnswered(id: String, answerText: String) = update {
        val existing = records[id] ?: return@update
        records[id] = existing.copy(
            status = MessageStatus.ANSWERED,
            answerText = answerText,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override fun markFailed(id: String, error: String) = update {
        val existing = records[id] ?: return@update
        records[id] = existing.copy(status = MessageStatus.FAILED, error = error, updatedAt = System.currentTimeMillis())
    }

    private fun update(block: () -> Unit) {
        synchronized(lock) {
            block()
            persist()
        }
    }

    private fun persist() {
        val tmp = File("$path.tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MessageRecord.serializer()), records.values.toList()))
        tmp.renameTo(File(path))
    }
}
