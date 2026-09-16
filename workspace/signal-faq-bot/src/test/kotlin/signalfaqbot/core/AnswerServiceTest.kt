package signalfaqbot.core

import kotlinx.coroutines.test.runTest
import signalfaqbot.calendar.StaticCalendarProvider
import signalfaqbot.faq.FaqProvider
import signalfaqbot.llm.LlmClient
import signalfaqbot.model.IncomingMessage
import signalfaqbot.template.AnswerRenderer
import signalfaqbot.template.PromptRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerServiceTest {
    private val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann habt ihr auf?", timestamp = 0, language = "de")

    @Test
    fun `feeds the message, FAQ and calendar into the prompt, and renders the LLM output as the answer`() = runTest {
        var capturedPrompt: String? = null
        val service = AnswerService(
            faqProvider = FaqProvider { "FAQ: Wir haben Mittwochs auf." },
            calendarProvider = StaticCalendarProvider("Mittwoch, 10:00-14:00"),
            promptRenderer = PromptRenderer { msg, faq, calendar ->
                "$faq | $calendar | ${msg.text}".also { capturedPrompt = it }
            },
            llmClient = LlmClient { prompt ->
                assertTrue(prompt.contains("Mittwochs"))
                assertTrue(prompt.contains("Mittwoch, 10:00-14:00"))
                assertTrue(prompt.contains(message.text))
                "Wir haben Mittwochs von 10 bis 14 Uhr auf."
            },
            answerRenderer = AnswerRenderer { _, llmOutput -> "$llmOutput\n-- Automatische Antwort" },
        )

        val answer = service.answer(message)

        assertEquals(message.sender, answer.recipient)
        assertEquals("Wir haben Mittwochs von 10 bis 14 Uhr auf.\n-- Automatische Antwort", answer.text)
        assertEquals("FAQ: Wir haben Mittwochs auf. | Mittwoch, 10:00-14:00 | Wann habt ihr auf?", capturedPrompt)
    }

    @Test
    fun `propagates an LLM failure instead of swallowing it`() = runTest {
        val service = AnswerService(
            faqProvider = FaqProvider { "" },
            calendarProvider = StaticCalendarProvider(""),
            promptRenderer = PromptRenderer { _, _, _ -> "prompt" },
            llmClient = LlmClient { throw IllegalStateException("model unavailable") },
            answerRenderer = AnswerRenderer { _, output -> output },
        )

        val error = runCatching { service.answer(message) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
