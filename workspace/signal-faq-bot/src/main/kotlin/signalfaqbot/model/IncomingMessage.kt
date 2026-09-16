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
)
