package signalfaqbot.core

import org.slf4j.LoggerFactory
import signalfaqbot.llm.LlmClient
import signalfaqbot.model.IncomingMessage

/** Outcome of running a message through every [ClassificationGate] in order. */
sealed interface GateResult {
    data object Passed : GateResult
    data class Skipped(val gateName: String) : GateResult
    data class Redirected(val gateName: String, val text: String) : GateResult
}

/**
 * Runs [gates] against a message in order, stopping at the first `NO`. Only
 * a message that passes every gate reaches [AnswerService] — see
 * [ClassificationGate] for what happens on a `NO`.
 */
class GatePipeline(private val gates: List<ClassificationGate>, private val llmClient: LlmClient) {
    private val log = LoggerFactory.getLogger(GatePipeline::class.java)

    val gateNames: List<String> get() = gates.map { it.name }

    suspend fun evaluate(message: IncomingMessage): GateResult {
        for (gate in gates) {
            if (!runGate(gate, message)) {
                return gate.answerRenderer?.let { GateResult.Redirected(gate.name, it.render(message)) }
                    ?: GateResult.Skipped(gate.name)
            }
        }
        return GateResult.Passed
    }

    /** Runs a single named gate in isolation, e.g. for `./gradlew run --args='<gate-name> "<message>"'`. */
    suspend fun evaluateOne(gateName: String, message: IncomingMessage): Boolean? {
        val gate = gates.find { it.name == gateName } ?: return null
        return runGate(gate, message)
    }

    private suspend fun runGate(gate: ClassificationGate, message: IncomingMessage): Boolean {
        val prompt = gate.promptRenderer.render(message)
        val response = llmClient.complete(prompt).trim()
        val passed = response.uppercase().startsWith("YES")
        log.info("{}: message {} classified {} (raw response: \"{}\")", gate.name, message.id, if (passed) "YES" else "NO", response)
        return passed
    }
}
