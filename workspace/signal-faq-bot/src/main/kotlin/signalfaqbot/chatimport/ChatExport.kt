package signalfaqbot.chatimport

import kotlinx.serialization.Serializable

/**
 * One message from an exported Signal Desktop chat transcript.
 *
 * [date]/[time] are null when the parser couldn't establish them (e.g. a
 * message before the transcript's first date header, or a block with no
 * trailing timestamp line — both noted in [ParsedChat.anomalies] too).
 */
@Serializable
data class ChatMessage(
    val date: String?,
    val time: String?,
    val sender: String?,
    /** The secondary "role"/topic tag some senders have next to their name, e.g. "Holzbereich". */
    val senderTag: String? = null,
    val text: String,
    val edited: Boolean = false,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val reactions: List<String> = emptyList(),
)

/** A join/leave/system event from the transcript. */
@Serializable
data class ChatEvent(
    val date: String?,
    val type: ChatEventType,
    val member: String? = null,
    /** Only set for [ChatEventType.GROUP_UPDATES] — the collapsed count, no per-member detail available. */
    val count: Int? = null,
)

@Serializable
enum class ChatEventType { JOINED, LEFT, GROUP_UPDATES, SAFETY_NUMBER_CHANGED }

/**
 * Result of parsing a chat export: everything the parser was confident
 * about, plus a human-readable list of lines it couldn't fully make sense
 * of — read [anomalies] before trusting the output for anything downstream.
 */
@Serializable
data class ParsedChat(
    val messages: List<ChatMessage>,
    val events: List<ChatEvent>,
    val anomalies: List<String> = emptyList(),
)
