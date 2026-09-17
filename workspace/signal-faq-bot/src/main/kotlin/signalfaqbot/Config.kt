package signalfaqbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SignalConfig(
    val account: String,
    val groupId: String,
    /** Shell prefix that runs `signal-cli`, e.g. a `docker run ...` line. See README. */
    val cliCommand: String,
    val pollIntervalSeconds: Long = 10,
    /**
     * Phone numbers (E.164, e.g. "+491701234567") of organization members.
     * Messages from these senders are never auto-answered — assumed to be
     * internal chat/replies between people who already know the FAQ, not
     * questions for the bot.
     */
    val memberAccounts: List<String> = emptyList(),
)

@Serializable
enum class LlmProvider { OLLAMA, APFEL }

@Serializable
data class LlmConfig(
    val provider: LlmProvider,
    val model: String,
    val ollamaBaseUrl: String = "http://localhost:11434",
    /** Shell command used to invoke the Apple Intelligence CLI, e.g. "apfel". */
    val apfelCommand: String = "apfel",
)

@Serializable
data class PathsConfig(
    val faq: String = "FAQ.md",
    /** Raw shell command that prints the calendar dump, e.g. "docker run --rm signal-faq-bot-calendar". */
    val calendarCommand: String = "docker run --rm signal-faq-bot-calendar",
    val promptTemplate: String = "templates/prompt.txt",
    val answerTemplate: String = "templates/answer.txt",
    /**
     * Directory scanned for the gate chain: every `classify*.txt` file here,
     * sorted alphabetically, becomes one [signalfaqbot.core.ClassificationGate]
     * (see [signalfaqbot.core.GateDiscovery]). A `classify_X.txt` pairs with
     * `answer_X.txt` in the same directory if present.
     */
    val templatesDir: String = "templates",
    val state: String = "state.json",
)

@Serializable
data class WebConfig(
    val enabled: Boolean = true,
    val port: Int = 8080,
)

@Serializable
data class Config(
    val signal: SignalConfig,
    val llm: LlmConfig,
    val paths: PathsConfig = PathsConfig(),
    val web: WebConfig = WebConfig(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(path: String): Config {
            val file = File(path)
            require(file.exists()) {
                "Config file not found at '$path'. Copy config.example.json to config.json and adjust it."
            }
            return json.decodeFromString(serializer(), file.readText())
        }
    }
}
