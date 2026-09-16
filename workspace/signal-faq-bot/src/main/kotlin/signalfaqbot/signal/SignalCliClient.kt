package signalfaqbot.signal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import signalfaqbot.model.IncomingMessage
import java.util.concurrent.TimeUnit

@Serializable
private data class Envelope(
    val sourceNumber: String? = null,
    val timestamp: Long? = null,
    val dataMessage: DataMessage? = null,
)

@Serializable
private data class DataMessage(
    val message: String? = null,
    val groupInfo: GroupInfo? = null,
)

@Serializable
private data class GroupInfo(val groupId: String? = null)

@Serializable
private data class SignalCliEvent(val envelope: Envelope? = null)

/**
 * Drives `signal-cli` (typically via `docker run ... signal-image`, see
 * README) exactly like `simple-filament-tool`'s `signal.json.cli_path`
 * already does for sends: [cliCommand] is a raw shell prefix, not a single
 * binary path, so it can be a whole `docker run ...` invocation.
 *
 * Receiving is done by polling `receive --json`, one JSON object per line —
 * see the README's "Future: signal-cli daemon (JSON-RPC)" note for the
 * lower-latency alternative we're deliberately not building yet.
 */
class SignalCliClient(
    private val cliCommand: String,
    private val account: String,
    private val groupId: String,
    private val receiveTimeoutSeconds: Int = 5,
) : SignalClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun receiveMessages(): List<IncomingMessage> = withContext(Dispatchers.IO) {
        // `-a`/`-o` are global signal-cli options and must come before the subcommand;
        // `receive` itself only takes the short `-t` for its timeout (signal-cli 0.14.x).
        val args = cliCommand.split(" ") + listOf(
            "-a", account, "-o", "json", "receive", "-t", receiveTimeoutSeconds.toString(),
        )
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readLines()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(receiveTimeoutSeconds + 30L, TimeUnit.SECONDS)
        check(finished) { "signal-cli receive timed out" }
        check(process.exitValue() == 0) { "signal-cli receive failed: $errorOutput" }

        output.mapNotNull { line -> parseGroupMessage(line) }
    }

    private fun parseGroupMessage(line: String): IncomingMessage? {
        if (line.isBlank()) return null
        val event = runCatching { json.decodeFromString(SignalCliEvent.serializer(), line) }.getOrNull()
        val envelope = event?.envelope ?: return null
        val data = envelope.dataMessage ?: return null
        val text = data.message ?: return null
        if (data.groupInfo?.groupId != groupId) return null
        val sender = envelope.sourceNumber ?: return null
        val timestamp = envelope.timestamp ?: System.currentTimeMillis()
        return IncomingMessage(id = "$sender-$timestamp", sender = sender, text = text, timestamp = timestamp)
    }

    override suspend fun sendDirectMessage(recipient: String, text: String) = withContext(Dispatchers.IO) {
        val args = cliCommand.split(" ") + listOf("-a", account, "send", "-m", text, recipient)
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished) { "signal-cli send timed out" }
        check(process.exitValue() == 0) { "signal-cli send failed: $errorOutput" }
        Unit
    }
}
