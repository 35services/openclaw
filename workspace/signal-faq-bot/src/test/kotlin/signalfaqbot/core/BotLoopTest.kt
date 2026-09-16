package signalfaqbot.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import signalfaqbot.calendar.StaticCalendarProvider
import signalfaqbot.fakes.FakeSignalClient
import signalfaqbot.faq.FaqProvider
import signalfaqbot.llm.LlmClient
import signalfaqbot.model.IncomingMessage
import signalfaqbot.model.MessageStatus
import signalfaqbot.signal.SignalClient
import signalfaqbot.state.InMemoryStateStore
import signalfaqbot.template.AnswerRenderer
import signalfaqbot.template.PromptRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotLoopTest {
    private fun answerService(response: String) = AnswerService(
        faqProvider = FaqProvider { "" },
        calendarProvider = StaticCalendarProvider(""),
        promptRenderer = PromptRenderer { msg, _, _ -> msg.text },
        llmClient = LlmClient { response },
        answerRenderer = AnswerRenderer { _, output -> output },
    )

    @Test
    fun `answers a new message, sends it and marks it answered`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val loop = BotLoop(signalClient, stateStore, answerService("Mittwochs 10-14 Uhr"), pollIntervalSeconds = 1)

        loop.runOnce()

        assertEquals(listOf("+491234" to "Mittwochs 10-14 Uhr"), signalClient.sent)
        assertEquals(MessageStatus.ANSWERED, stateStore.find("1")?.status)
    }

    @Test
    fun `never re-answers a message already recorded as answered`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message), emptyList())
        val stateStore = InMemoryStateStore()
        val loop = BotLoop(signalClient, stateStore, answerService("Antwort"), pollIntervalSeconds = 1)

        loop.runOnce()
        loop.runOnce()

        assertEquals(1, signalClient.sent.size)
    }

    @Test
    fun `records a failed LLM call instead of crashing the loop`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val failingService = AnswerService(
            faqProvider = FaqProvider { "" },
            calendarProvider = StaticCalendarProvider(""),
            promptRenderer = PromptRenderer { msg, _, _ -> msg.text },
            llmClient = LlmClient { throw IllegalStateException("no model loaded") },
            answerRenderer = AnswerRenderer { _, output -> output },
        )
        val loop = BotLoop(signalClient, stateStore, failingService, pollIntervalSeconds = 1)

        loop.runOnce()

        assertEquals(MessageStatus.FAILED, stateStore.find("1")?.status)
        assertEquals(0, signalClient.sent.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `runForever survives a poll that throws and retries on the next interval`() = runTest {
        var calls = 0
        val flakySignalClient = object : SignalClient {
            override suspend fun receiveMessages(): List<IncomingMessage> {
                calls++
                if (calls == 1) error("signal-cli unavailable")
                return emptyList()
            }

            override suspend fun sendDirectMessage(recipient: String, text: String) = Unit
        }
        val loop = BotLoop(flakySignalClient, InMemoryStateStore(), answerService("unused"), pollIntervalSeconds = 10)

        val job = launch { loop.runForever() }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        job.cancel()

        assertTrue(calls >= 2, "expected the loop to poll again after the first failure, but it polled $calls time(s)")
    }
}
