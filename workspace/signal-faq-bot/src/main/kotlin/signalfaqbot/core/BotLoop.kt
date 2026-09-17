package signalfaqbot.core

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import signalfaqbot.model.MessageStatus
import signalfaqbot.signal.SignalClient
import signalfaqbot.state.StateStore

/**
 * Polls [SignalClient] on an interval, answers anything new via
 * [AnswerService], and sends the result back as a DM — recording every step
 * in [StateStore] so a restart resumes cleanly instead of re-answering or
 * dropping in-flight messages.
 *
 * Two gates run before a message reaches [AnswerService]:
 * 1. Messages from a configured [memberAccounts] number are assumed to be
 *    internal chat/replies and skipped outright (no LLM call).
 * 2. [gatePipeline] runs the discovered `classify*.txt` chain (see
 *    [GateDiscovery]) — a `NO` from any gate either sends that gate's static
 *    answer or skips silently; only a message that passes every gate reaches
 *    [AnswerService].
 */
class BotLoop(
    private val signalClient: SignalClient,
    private val stateStore: StateStore,
    private val gatePipeline: GatePipeline,
    private val answerService: AnswerService,
    private val pollIntervalSeconds: Long,
    private val memberAccounts: Set<String> = emptySet(),
) {
    private val log = LoggerFactory.getLogger(BotLoop::class.java)

    suspend fun runForever() {
        while (true) {
            try {
                runOnce()
            } catch (e: Exception) {
                // A transient signal-cli/Docker/network hiccup shouldn't kill the whole bot —
                // log it and try again next interval.
                log.warn("Poll cycle failed, will retry", e)
            }
            delay(pollIntervalSeconds * 1000)
        }
    }

    /** One poll-and-answer cycle; exposed separately so it can be driven manually/tested. */
    suspend fun runOnce() {
        val incoming = signalClient.receiveMessages()
        if (incoming.isNotEmpty()) log.info("Received {} new message(s)", incoming.size)
        for (message in incoming) {
            stateStore.markReceived(message)
        }

        val pending = stateStore.all().filter { it.status == MessageStatus.RECEIVED }
        for (record in pending) {
            val message = record.message

            if (message.sender in memberAccounts) {
                log.info("Skipping message {}: {} is a configured member account", message.id, message.sender)
                stateStore.markSkipped(message.id, "sender is a configured member account")
                continue
            }

            stateStore.markProcessing(message.id)
            try {
                when (val result = gatePipeline.evaluate(message)) {
                    is GateResult.Skipped -> {
                        log.info("Skipping message {}: gate '{}' returned NO", message.id, result.gateName)
                        stateStore.markSkipped(message.id, "gate '${result.gateName}' returned NO")
                    }
                    is GateResult.Redirected -> {
                        signalClient.sendDirectMessage(message.sender, result.text)
                        stateStore.markRedirected(message.id, result.text)
                        log.info("Redirected message {} via gate '{}'", message.id, result.gateName)
                    }
                    GateResult.Passed -> {
                        val answer = answerService.answer(message)
                        signalClient.sendDirectMessage(answer.recipient, answer.text)
                        stateStore.markAnswered(message.id, answer.text)
                        log.info("Sent answer for message {} to {}", message.id, message.sender)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to process message ${message.id}", e)
                stateStore.markFailed(message.id, e.message ?: e.toString())
            }
        }
    }
}
