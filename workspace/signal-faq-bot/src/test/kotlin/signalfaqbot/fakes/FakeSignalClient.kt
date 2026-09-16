package signalfaqbot.fakes

import signalfaqbot.model.IncomingMessage
import signalfaqbot.signal.SignalClient

/**
 * Returns each queued batch of messages exactly once (like a real poll would
 * only see a message once), and records every DM sent so tests can assert on
 * it without a real signal-cli process.
 */
class FakeSignalClient(private vararg val batches: List<IncomingMessage>) : SignalClient {
    private var nextBatch = 0
    val sent = mutableListOf<Pair<String, String>>()

    override suspend fun receiveMessages(): List<IncomingMessage> {
        if (nextBatch >= batches.size) return emptyList()
        return batches[nextBatch++]
    }

    override suspend fun sendDirectMessage(recipient: String, text: String) {
        sent += recipient to text
    }
}
