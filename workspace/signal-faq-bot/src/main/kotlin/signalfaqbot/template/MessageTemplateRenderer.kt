package signalfaqbot.template

import signalfaqbot.model.IncomingMessage
import java.io.File

/**
 * Renders a template file against just a message (text/sender/language) — no
 * FAQ or calendar involved. Used for the classifier prompts
 * (`templates/classify.txt`, `templates/classify_practical.txt`) and the
 * static practical-question redirect (`templates/practical.txt`).
 */
fun interface MessageTemplateRenderer {
    fun render(message: IncomingMessage): String
}

class FileBackedMessageTemplateRenderer(private val templatePath: String) : MessageTemplateRenderer {
    override fun render(message: IncomingMessage): String {
        val template = File(templatePath).readText()
        return TemplateRenderer.render(
            template,
            mapOf(
                "message" to message.text,
                "sender" to message.sender,
                "language" to message.language,
                "time_since_joined" to formatMinutesSinceJoined(message.minutesSinceJoined),
                "messages_last_24h" to message.messagesLast24h.toString(),
            ),
        )
    }
}

/** Renders [minutes] as short German prose, e.g. for `{{time_since_joined}}` — "unbekannt" when there's no join event to compare against. */
private fun formatMinutesSinceJoined(minutes: Long?): String = when {
    minutes == null -> "unbekannt"
    minutes < 60 -> "weniger als 1 Stunde"
    minutes < 60 * 24 -> "${minutes / 60} Stunde(n)"
    else -> "${minutes / (60 * 24)} Tag(e)"
}
