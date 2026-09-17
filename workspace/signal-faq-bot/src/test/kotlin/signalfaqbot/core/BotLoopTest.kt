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
import signalfaqbot.template.MessageTemplateRenderer
import signalfaqbot.template.PromptRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotLoopTest {
    /** No gates configured -> [GatePipeline.evaluate] always returns [GateResult.Passed]. */
    private val noGates = GatePipeline(emptyList(), LlmClient { "unused" })

    private fun answerService(response: String) = AnswerService(
        faqProvider = FaqProvider { "" },
        calendarProvider = StaticCalendarProvider(""),
        promptRenderer = PromptRenderer { msg, _, _ -> msg.text },
        llmClient = LlmClient { response },
        answerRenderer = AnswerRenderer { _, output -> output },
    )

    /** A single gate whose prompt is just its name, so a map-backed [LlmClient] can answer per gate. */
    private fun gate(name: String, hasAnswer: Boolean = false, answerText: String = "") = ClassificationGate(
        name = name,
        promptRenderer = MessageTemplateRenderer { name },
        answerRenderer = if (hasAnswer) MessageTemplateRenderer { answerText } else null,
    )

    private fun loop(
        signalClient: SignalClient,
        stateStore: InMemoryStateStore,
        gatePipeline: GatePipeline = noGates,
        answerService: AnswerService = answerService("Antwort"),
        memberAccounts: Set<String> = emptySet(),
        pollIntervalSeconds: Long = 1,
    ) = BotLoop(
        signalClient = signalClient,
        stateStore = stateStore,
        gatePipeline = gatePipeline,
        answerService = answerService,
        pollIntervalSeconds = pollIntervalSeconds,
        memberAccounts = memberAccounts,
    )

    @Test
    fun `answers a new message, sends it and marks it answered`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()

        loop(signalClient, stateStore, answerService = answerService("Mittwochs 10-14 Uhr")).runOnce()

        assertEquals(listOf("+491234" to "Mittwochs 10-14 Uhr"), signalClient.sent)
        assertEquals(MessageStatus.ANSWERED, stateStore.find("1")?.status)
    }

    @Test
    fun `never re-answers a message already recorded as answered`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message), emptyList())
        val stateStore = InMemoryStateStore()
        val botLoop = loop(signalClient, stateStore)

        botLoop.runOnce()
        botLoop.runOnce()

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

        loop(signalClient, stateStore, answerService = failingService).runOnce()

        assertEquals(MessageStatus.FAILED, stateStore.find("1")?.status)
        assertEquals(0, signalClient.sent.size)
    }

    @Test
    fun `skips a message from a configured member account without running the gate chain`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "hey bin gleich da", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        var gateCalls = 0
        val trackingPipeline = GatePipeline(listOf(gate("classify_x")), LlmClient { gateCalls++; "YES" })

        loop(signalClient, stateStore, gatePipeline = trackingPipeline, memberAccounts = setOf("+491234")).runOnce()

        assertEquals(MessageStatus.SKIPPED, stateStore.find("1")?.status)
        assertEquals(0, signalClient.sent.size)
        assertEquals(0, gateCalls, "member messages should never reach the gate chain")
    }

    @Test
    fun `skips a message when a gate without a static answer returns NO`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "haha genau", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val pipeline = GatePipeline(listOf(gate("classify_0_is_question")), LlmClient { "NO" })

        loop(signalClient, stateStore, gatePipeline = pipeline).runOnce()

        val record = stateStore.find("1")
        assertEquals(MessageStatus.SKIPPED, record?.status)
        assertEquals("gate 'classify_0_is_question' returned NO", record?.error)
        assertEquals(0, signalClient.sent.size)
    }

    @Test
    fun `sends a gate's static answer instead of running the answer pipeline when it returns NO`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wie repariere ich das?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val answerServiceCalls = mutableListOf<IncomingMessage>()
        val trackingAnswerService = AnswerService(
            faqProvider = FaqProvider { "" },
            calendarProvider = StaticCalendarProvider(""),
            promptRenderer = PromptRenderer { msg, _, _ -> answerServiceCalls.add(msg); msg.text },
            llmClient = LlmClient { "should not be called" },
            answerRenderer = AnswerRenderer { _, output -> output },
        )
        val pipeline = GatePipeline(
            listOf(gate("classify_practical", hasAnswer = true, answerText = "Sieht so aus als hast du eine praktische Frage.")),
            LlmClient { "NO" },
        )

        loop(signalClient, stateStore, gatePipeline = pipeline, answerService = trackingAnswerService).runOnce()

        assertEquals(listOf("+491234" to "Sieht so aus als hast du eine praktische Frage."), signalClient.sent)
        assertEquals(MessageStatus.REDIRECTED, stateStore.find("1")?.status)
        assertTrue(answerServiceCalls.isEmpty(), "a redirected message should never reach AnswerService")
    }

    @Test
    fun `answers a message that passes every gate in the chain`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann habt ihr offen?", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val pipeline = GatePipeline(
            listOf(gate("classify_0_is_question"), gate("classify_practical", hasAnswer = true)),
            LlmClient { "YES" },
        )

        loop(signalClient, stateStore, gatePipeline = pipeline).runOnce()

        assertEquals(MessageStatus.ANSWERED, stateStore.find("1")?.status)
        assertEquals(1, signalClient.sent.size)
    }

    @Test
    fun `stops at the first gate that returns NO and never evaluates later gates`() = runTest {
        val message = IncomingMessage(id = "1", sender = "+491234", text = "haha genau", timestamp = 0)
        val signalClient = FakeSignalClient(listOf(message))
        val stateStore = InMemoryStateStore()
        val evaluatedGates = mutableListOf<String>()
        val pipeline = GatePipeline(
            listOf(gate("classify_0_is_question"), gate("classify_practical", hasAnswer = true)),
            LlmClient { prompt -> evaluatedGates.add(prompt); "NO" },
        )

        loop(signalClient, stateStore, gatePipeline = pipeline).runOnce()

        assertEquals(listOf("classify_0_is_question"), evaluatedGates)
        assertEquals(MessageStatus.SKIPPED, stateStore.find("1")?.status)
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
        val botLoop = loop(flakySignalClient, InMemoryStateStore(), pollIntervalSeconds = 10)

        val job = launch { botLoop.runForever() }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        job.cancel()

        assertTrue(calls >= 2, "expected the loop to poll again after the first failure, but it polled $calls time(s)")
    }
}
