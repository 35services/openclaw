package signalfaqbot.core

import org.slf4j.LoggerFactory
import signalfaqbot.calendar.CalendarProvider
import signalfaqbot.faq.FaqProvider
import signalfaqbot.llm.LlmClient
import signalfaqbot.model.Answer
import signalfaqbot.model.IncomingMessage
import signalfaqbot.template.AnswerRenderer
import signalfaqbot.template.PromptRenderer
import kotlin.system.measureTimeMillis

/**
 * The whole point of this codebase, boiled down: takes one [IncomingMessage],
 * returns one [Answer] for its sender. Everything else (Signal polling, the
 * state file, the CLI, the web dashboard) is plumbing around this function.
 *
 * Every collaborator is an interface, so a unit test can swap in fakes for
 * the FAQ/calendar/LLM without touching a filesystem, a subprocess, or the
 * network — see `AnswerServiceTest`.
 */
class AnswerService(
    private val faqProvider: FaqProvider,
    private val calendarProvider: CalendarProvider,
    private val promptRenderer: PromptRenderer,
    private val llmClient: LlmClient,
    private val answerRenderer: AnswerRenderer,
) {
    private val log = LoggerFactory.getLogger(AnswerService::class.java)

    suspend fun answer(message: IncomingMessage): Answer {
        log.info("Answering message {} from {}: \"{}\"", message.id, message.sender, message.text)

        val faq = faqProvider.load()
        log.debug("Loaded FAQ ({} chars)", faq.length)

        val calendar = calendarProvider.fetchUpcoming()
        log.debug("Fetched calendar ({} chars)", calendar.length)

        val prompt = promptRenderer.render(message, faq, calendar)
        log.debug("Rendered prompt ({} chars):\n{}", prompt.length, prompt)

        var llmOutput: String
        val llmMillis = measureTimeMillis { llmOutput = llmClient.complete(prompt) }
        log.info("LLM responded in {} ms ({} chars)", llmMillis, llmOutput.length)
        log.debug("Raw LLM output:\n{}", llmOutput)

        val text = answerRenderer.render(message, llmOutput)
        log.info("Rendered answer for {} ({} chars)", message.sender, text.length)

        return Answer(recipient = message.sender, text = text)
    }
}
