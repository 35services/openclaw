package signalfaqbot.chatanalysis

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists [MessageAnalysis] results as a JSON array, keyed by
 * [MessageAnalysis.messageIndex] — same temp-file-then-rename write pattern
 * as [signalfaqbot.state.JsonFileStateStore], so a crash mid-write can't
 * leave a half-written, unparseable file behind.
 *
 * Unlike that store, there's no PROCESSING marker: [ChatAnalyzer] runs one
 * message at a time, sequentially, and nothing it does is externally
 * visible (no message is ever sent) — so on resume, any [messageIndex] not
 * yet present is simply safe to (re)do from scratch. No "still running vs.
 * crashed mid-flight" ambiguity to resolve, unlike the live bot's state
 * (see its CLAUDE.md note on PROCESSING).
 *
 * A [MessageAnalysis] already saved — FAILED included — is never retried
 * automatically, matching how the live bot's FAILED records aren't
 * auto-retried either. Delete that entry from the output file (or the whole
 * file) to force a redo.
 */
class ChatAnalysisStore(private val path: String) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val records = linkedMapOf<Int, MessageAnalysis>()

    init {
        val file = File(path)
        if (file.exists() && file.length() > 0) {
            val loaded = json.decodeFromString(ListSerializer(MessageAnalysis.serializer()), file.readText())
            loaded.forEach { records[it.messageIndex] = it }
        }
    }

    fun isDone(messageIndex: Int): Boolean = synchronized(lock) { records.containsKey(messageIndex) }

    fun all(): List<MessageAnalysis> = synchronized(lock) { records.values.sortedBy { it.messageIndex } }

    fun save(analysis: MessageAnalysis) = synchronized(lock) {
        records[analysis.messageIndex] = analysis
        persist()
    }

    private fun persist() {
        val tmp = File("$path.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(MessageAnalysis.serializer()), records.values.sortedBy { it.messageIndex }))
        tmp.renameTo(File(path))
    }
}
