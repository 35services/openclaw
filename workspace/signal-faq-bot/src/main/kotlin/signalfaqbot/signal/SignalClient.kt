package signalfaqbot.signal

import signalfaqbot.model.IncomingMessage

interface SignalClient {
    /** New messages in the monitored group since the last call. */
    suspend fun receiveMessages(): List<IncomingMessage>

    /** Sends a direct message to a single recipient (never back into the group). */
    suspend fun sendDirectMessage(recipient: String, text: String)
}
