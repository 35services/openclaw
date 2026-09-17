package signalfaqbot.chatanalysis

import org.slf4j.LoggerFactory
import signalfaqbot.chatimport.ChatEvent
import signalfaqbot.chatimport.ChatMessage
import signalfaqbot.core.AnswerService
import signalfaqbot.core.GatePipeline
import signalfaqbot.core.GateResult
import signalfaqbot.model.IncomingMessage

/**
 * Replays [signalfaqbot.core.BotLoop]'s decision flow — member skip-list,
 * then [gatePipeline], then [answerService] — against historical messages
 * from a parsed chat export, to see what the live bot would have done.
 * Nothing is ever sent anywhere; this is pure analysis, run offline against
 * a `parse-chat` JSON file rather than live Signal traffic.
 *
 * Progress is persisted to [store] after *every* message (one full gate
 * chain + possibly one answer call), so a killed/crashed run — expected for
 * a real chat export, since each message can cost several LLM calls —
 * resumes from wherever it left off: call [analyzeAll] again with the same
 * [store] and it picks up where it stopped, spending zero further LLM calls
 * on messages already analyzed.
 */
class ChatAnalyzer(
    private val gatePipeline: GatePipeline,
    private val answerService: AnswerService,
    private val store: ChatAnalysisStore,
    private val memberAccounts: Set<String> = emptySet(),
) {
    private val log = LoggerFactory.getLogger(ChatAnalyzer::class.java)

    /**
     * Analyzes every not-yet-done message in [messages], in order. Safe to
     * call again to resume. [events] (join/leave history) feeds
     * [MessageContext] so each message's classifier prompt can be enriched
     * with how recently its sender joined and how active they've been.
     */
    suspend fun analyzeAll(messages: List<ChatMessage>, events: List<ChatEvent> = emptyList()) {
        val context = MessageContext(messages, events)
        messages.forEachIndexed { index, message ->
            if (store.isDone(index)) {
                log.debug("Skipping message {} (already analyzed)", index)
                return@forEachIndexed
            }
            log.info("Analyzing message {}/{}", index + 1, messages.size)
            store.save(analyzeOne(index, message, context))
        }
    }

    private suspend fun analyzeOne(index: Int, message: ChatMessage, context: MessageContext): MessageAnalysis {
        val sender = message.sender
        if (sender == null) {
            return MessageAnalysis(
                messageIndex = index,
                sender = null,
                text = message.text,
                status = AnalysisStatus.SKIPPED,
                error = "no recognized sender (parser anomaly)",
                updatedAt = System.currentTimeMillis(),
            )
        }

        if (sender in memberAccounts) {
            return MessageAnalysis(
                messageIndex = index,
                sender = sender,
                text = message.text,
                status = AnalysisStatus.SKIPPED,
                error = "sender is a configured member account",
                updatedAt = System.currentTimeMillis(),
            )
        }

        val incoming = IncomingMessage(
            id = "chat-$index",
            sender = sender,
            text = message.text,
            timestamp = 0L,
            minutesSinceJoined = context.minutesSinceJoined(index),
            messagesLast24h = context.messagesLast24h(index),
        )

        return try {
            val result = gatePipeline.evaluate(incoming)
            val checks = buildChecks(gatePipeline.gateNames, result)
            when (result) {
                GateResult.Passed -> {
                    val answer = answerService.answer(incoming)
                    MessageAnalysis(
                        messageIndex = index,
                        sender = sender,
                        text = message.text,
                        checks = checks,
                        status = AnalysisStatus.ANSWERED,
                        answerText = answer.text,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                is GateResult.Redirected -> MessageAnalysis(
                    messageIndex = index,
                    sender = sender,
                    text = message.text,
                    checks = checks,
                    status = AnalysisStatus.REDIRECTED,
                    stoppedByGate = result.gateName,
                    answerText = result.text,
                    updatedAt = System.currentTimeMillis(),
                )
                is GateResult.Skipped -> MessageAnalysis(
                    messageIndex = index,
                    sender = sender,
                    text = message.text,
                    checks = checks,
                    status = AnalysisStatus.SKIPPED,
                    stoppedByGate = result.gateName,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to analyze message {}", index, e)
            MessageAnalysis(
                messageIndex = index,
                sender = sender,
                text = message.text,
                status = AnalysisStatus.FAILED,
                error = e.message ?: e.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Reconstructs the full per-gate trace from [GatePipeline.evaluate]'s
     * single result, with zero extra LLM calls: a message that reached gate
     * N must have passed gates before it (pipeline semantics guarantee
     * that), so only the *stopping* gate's verdict needs to come from the
     * result itself — earlier gates are recorded as passed without
     * re-invoking them, and later, never-reached gates are correctly left
     * out of the list entirely.
     */
    private fun buildChecks(gateNames: List<String>, result: GateResult): List<GateCheck> = when (result) {
        GateResult.Passed -> gateNames.map { GateCheck(it, passed = true) }
        is GateResult.Skipped -> checksUpTo(gateNames, result.gateName)
        is GateResult.Redirected -> checksUpTo(gateNames, result.gateName)
    }

    private fun checksUpTo(gateNames: List<String>, stoppedAt: String): List<GateCheck> {
        val stopIndex = gateNames.indexOf(stoppedAt)
        return gateNames.take(stopIndex).map { GateCheck(it, passed = true) } + GateCheck(stoppedAt, passed = false)
    }
}
