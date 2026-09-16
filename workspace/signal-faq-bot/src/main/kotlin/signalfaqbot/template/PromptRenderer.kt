package signalfaqbot.template

import signalfaqbot.model.IncomingMessage
import java.io.File

/** Builds the final prompt string sent to the LLM. */
fun interface PromptRenderer {
    fun render(message: IncomingMessage, faq: String, calendar: String): String
}

/**
 * Reads the prompt template from disk on every call, so editing the file on
 * the running host (no rebuild, no restart) changes the next answer.
 */
class FileBackedPromptRenderer(private val templatePath: String) : PromptRenderer {
    override fun render(message: IncomingMessage, faq: String, calendar: String): String {
        val template = File(templatePath).readText()
        return TemplateRenderer.render(
            template,
            mapOf(
                "faq" to faq,
                "calendar" to calendar,
                "message" to message.text,
                "sender" to message.sender,
                "language" to message.language,
            ),
        )
    }
}
