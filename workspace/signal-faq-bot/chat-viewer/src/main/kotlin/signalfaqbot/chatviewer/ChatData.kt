package signalfaqbot.chatviewer

import kotlinx.serialization.Serializable

/**
 * Mirrors signalfaqbot.chatimport.ChatMessage/ChatEvent/ParsedChat's JSON wire
 * format exactly (see that module's doc comments for field meaning). Kept as
 * its own copy rather than a project(":") dependency on the bot module, so
 * this viewer stays a small, standalone desktop app — it only ever reads the
 * JSON `parse-chat` already produces, never the bot's other dependencies.
 */
@Serializable
data class ChatMessage(
    val date: String?,
    val time: String?,
    val sender: String?,
    val senderTag: String? = null,
    val text: String,
    val edited: Boolean = false,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val reactions: List<String> = emptyList(),
)

@Serializable
data class ChatEvent(
    val date: String?,
    val type: ChatEventType,
    val member: String? = null,
    val count: Int? = null,
)

@Serializable
enum class ChatEventType { JOINED, LEFT, GROUP_UPDATES, SAFETY_NUMBER_CHANGED }

@Serializable
data class ParsedChat(
    val messages: List<ChatMessage>,
    val events: List<ChatEvent>,
    val anomalies: List<String> = emptyList(),
)
