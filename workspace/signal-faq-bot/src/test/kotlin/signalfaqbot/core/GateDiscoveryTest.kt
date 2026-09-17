package signalfaqbot.core

import kotlinx.coroutines.test.runTest
import signalfaqbot.model.IncomingMessage
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GateDiscoveryTest {
    @Test
    fun `discovers gates sorted alphabetically by filename, ignoring unrelated files`() {
        val dir = Files.createTempDirectory("gates")
        dir.resolve("classify_practical.txt").writeText("practical prompt")
        dir.resolve("classify_0_is_question.txt").writeText("question prompt")
        dir.resolve("answer.txt").writeText("not a gate")
        dir.resolve("prompt.txt").writeText("not a gate")

        val gates = GateDiscovery.discover(dir.toString())

        assertEquals(listOf("classify_0_is_question", "classify_practical"), gates.map { it.name })
    }

    @Test
    fun `pairs a classify file with its matching answer file when present`() = runTest {
        val dir = Files.createTempDirectory("gates")
        dir.resolve("classify_practical.txt").writeText("practical prompt")
        dir.resolve("answer_practical.txt").writeText("static redirect text")

        val gate = GateDiscovery.discover(dir.toString()).single()
        val message = IncomingMessage(id = "1", sender = "+491234", text = "hi", timestamp = 0)

        assertEquals("static redirect text", gate.answerRenderer?.render(message))
        assertEquals("practical prompt", gate.promptRenderer.render(message))
    }

    @Test
    fun `leaves answerRenderer null when no matching answer file exists`() {
        val dir = Files.createTempDirectory("gates")
        dir.resolve("classify_0_is_question.txt").writeText("question prompt")

        val gate = GateDiscovery.discover(dir.toString()).single()

        assertNull(gate.answerRenderer)
    }

    @Test
    fun `an unknown directory yields no gates`() {
        assertEquals(emptyList(), GateDiscovery.discover("/no/such/directory"))
    }
}
