package signalfaqbot.model

import kotlinx.serialization.Serializable

/** A single message received from the monitored Signal group. */
@Serializable
data class IncomingMessage(
    /** Stable id used for de-duplication in the state store, e.g. `"<sender>-<timestamp>"`. */
    val id: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    /** BCP-47-ish hint ("de" or "en"); defaults to "de" since that's the group's default. */
    val language: String = "de",
    /**
     * Minutes between the sender joining the group and this message, or null
     * if unknown (the live bot doesn't track this; offline chat analysis
     * does via [signalfaqbot.chatanalysis.MessageContext]). Exposed to
     * classifier prompts as `{{time_since_joined}}` — see
     * [signalfaqbot.template.FileBackedMessageTemplateRenderer].
     */
    val minutesSinceJoined: Long? = null,
    /** How many other messages this sender sent in the 24h before this one. Exposed as `{{messages_last_24h}}`. */
    val messagesLast24h: Int = 0,
)
