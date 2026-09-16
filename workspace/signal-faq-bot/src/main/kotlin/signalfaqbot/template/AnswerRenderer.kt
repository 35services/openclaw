package signalfaqbot.template

import signalfaqbot.model.IncomingMessage
import java.io.File

/** Wraps the raw LLM output into the message that actually gets sent. */
fun interface AnswerRenderer {
    fun render(message: IncomingMessage, llmOutput: String): String
}

class FileBackedAnswerRenderer(private val templatePath: String) : AnswerRenderer {
    override fun render(message: IncomingMessage, llmOutput: String): String {
        val template = File(templatePath).readText()
        return TemplateRenderer.render(
            template,
            mapOf(
                "answer" to llmOutput.trim(),
                "sender" to message.sender,
                "language" to message.language,
            ),
        )
    }
}
