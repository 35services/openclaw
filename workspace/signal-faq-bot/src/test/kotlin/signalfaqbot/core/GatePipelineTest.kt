package signalfaqbot.core

import kotlinx.coroutines.test.runTest
import signalfaqbot.llm.LlmClient
import signalfaqbot.model.IncomingMessage
import signalfaqbot.template.MessageTemplateRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GatePipelineTest {
    private val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann habt ihr offen?", timestamp = 0)

    private fun gate(name: String, hasAnswer: Boolean = false) = ClassificationGate(
        name = name,
        promptRenderer = MessageTemplateRenderer { name },
        answerRenderer = if (hasAnswer) MessageTemplateRenderer { "static answer for $name" } else null,
    )

    @Test
    fun `an empty gate list always passes`() = runTest {
        val pipeline = GatePipeline(emptyList(), LlmClient { "NO" })
        assertEquals(GateResult.Passed, pipeline.evaluate(message))
    }

    @Test
    fun `passes when every gate answers YES`() = runTest {
        val pipeline = GatePipeline(listOf(gate("a"), gate("b")), LlmClient { "YES" })
        assertEquals(GateResult.Passed, pipeline.evaluate(message))
    }

    @Test
    fun `is case-insensitive and tolerates surrounding whitespace`() = runTest {
        val pipeline = GatePipeline(listOf(gate("a")), LlmClient { "  yes\n" })
        assertEquals(GateResult.Passed, pipeline.evaluate(message))
    }

    @Test
    fun `treats an unexpected response conservatively as NO`() = runTest {
        val pipeline = GatePipeline(listOf(gate("a")), LlmClient { "I'm not sure what you mean" })
        assertIs<GateResult.Skipped>(pipeline.evaluate(message))
    }

    @Test
    fun `a gate without a matching answer file skips`() = runTest {
        val pipeline = GatePipeline(listOf(gate("classify_0_is_question", hasAnswer = false)), LlmClient { "NO" })
        val result = pipeline.evaluate(message)
        assertEquals(GateResult.Skipped("classify_0_is_question"), result)
    }

    @Test
    fun `a gate with a matching answer file redirects with its rendered text`() = runTest {
        val pipeline = GatePipeline(listOf(gate("classify_practical", hasAnswer = true)), LlmClient { "NO" })
        val result = pipeline.evaluate(message)
        assertEquals(GateResult.Redirected("classify_practical", "static answer for classify_practical"), result)
    }

    @Test
    fun `stops at the first NO and never calls later gates`() = runTest {
        val calledPrompts = mutableListOf<String>()
        val pipeline = GatePipeline(
            listOf(gate("a"), gate("b")),
            LlmClient { prompt -> calledPrompts.add(prompt); "NO" },
        )

        pipeline.evaluate(message)

        assertEquals(listOf("a"), calledPrompts)
    }

    @Test
    fun `evaluateOne runs a single named gate directly`() = runTest {
        val pipeline = GatePipeline(listOf(gate("a"), gate("b")), LlmClient { prompt -> if (prompt == "b") "YES" else "NO" })

        assertEquals(false, pipeline.evaluateOne("a", message))
        assertEquals(true, pipeline.evaluateOne("b", message))
    }

    @Test
    fun `evaluateOne returns null for an unknown gate name`() = runTest {
        val pipeline = GatePipeline(listOf(gate("a")), LlmClient { "YES" })
        assertNull(pipeline.evaluateOne("does-not-exist", message))
    }
}
