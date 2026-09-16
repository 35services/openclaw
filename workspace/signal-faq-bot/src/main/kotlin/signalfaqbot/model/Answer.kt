package signalfaqbot.model

/** The rendered reply text, ready to be sent back to [recipient]. */
data class Answer(
    val recipient: String,
    val text: String,
)
