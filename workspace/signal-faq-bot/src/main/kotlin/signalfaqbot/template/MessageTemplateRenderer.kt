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
            ),
        )
    }
}
