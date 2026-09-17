package signalfaqbot.chatanalysis

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import signalfaqbot.chatimport.ChatEvent
import signalfaqbot.chatimport.ChatEventType
import signalfaqbot.chatimport.ChatMessage

/**
 * Two contextual signals computed from the full chat history, meant to
 * enrich classifier prompts (see [signalfaqbot.model.IncomingMessage] and
 * `templates/classify_practical.txt`): how recently the sender joined the
 * group, and how active they've been in the last 24 hours. Both are
 * heuristics for "this looks like a newcomer asking a basic, FAQ-answerable
 * question" rather than a practical how-to question.
 *
 * Both are necessarily approximate — [ChatEvent] only carries a join *date*,
 * not a time, so "minutes since joined" is computed against midnight of
 * that date, and a message with no parseable date/time yields no signal.
 */
class MessageContext(private val messages: List<ChatMessage>, events: List<ChatEvent>) {
    private val timestamps: List<Long?> = messages.map { parseTimestamp(it.date, it.time) }

    private val joinTimestampsBySender: Map<String, List<Long>> = events
        .filter { it.type == ChatEventType.JOINED && it.member != null }
        .mapNotNull { event -> parseDate(event.date)?.let { event.member!! to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, joinTimes) -> joinTimes.sorted() }

    /** Minutes between the sender's most recent join at/before this message, or null if no join event is known. */
    fun minutesSinceJoined(index: Int): Long? {
        val sender = messages[index].sender ?: return null
        val messageTime = timestamps[index] ?: return null
        val joins = joinTimestampsBySender[sender] ?: return null
        val mostRecentJoin = joins.lastOrNull { it <= messageTime } ?: return null
        return (messageTime - mostRecentJoin) / 60_000
    }

    /** How many other messages this sender sent in the 24h window strictly before this message. */
    fun messagesLast24h(index: Int): Int {
        val sender = messages[index].sender ?: return 0
        val messageTime = timestamps[index] ?: return 0
        val windowStart = messageTime - 24L * 60 * 60_000
        return messages.indices.count { i ->
            i != index && messages[i].sender == sender && timestamps[i]?.let { it in windowStart until messageTime } == true
        }
    }

    private companion object {
        val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        fun parseDate(date: String?): Long? {
            if (date == null) return null
            return runCatching {
                LocalDate.parse(date, dateFormatter).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }

        fun parseTimestamp(date: String?, time: String?): Long? {
            if (date == null || time == null) return null
            return runCatching {
                val parsedDate = LocalDate.parse(date, dateFormatter)
                val parsedTime = LocalTime.parse(time, timeFormatter)
                LocalDateTime.of(parsedDate, parsedTime).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }
    }
}
