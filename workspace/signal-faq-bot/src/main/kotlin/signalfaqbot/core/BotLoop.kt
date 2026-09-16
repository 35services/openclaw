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
 */
class BotLoop(
    private val signalClient: SignalClient,
    private val stateStore: StateStore,
    private val answerService: AnswerService,
    private val pollIntervalSeconds: Long,
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
            stateStore.markProcessing(message.id)
            try {
                val answer = answerService.answer(message)
                signalClient.sendDirectMessage(answer.recipient, answer.text)
                stateStore.markAnswered(message.id, answer.text)
                log.info("Sent answer for message {} to {}", message.id, message.sender)
            } catch (e: Exception) {
                log.warn("Failed to answer message ${message.id}", e)
                stateStore.markFailed(message.id, e.message ?: e.toString())
            }
        }
    }
}
