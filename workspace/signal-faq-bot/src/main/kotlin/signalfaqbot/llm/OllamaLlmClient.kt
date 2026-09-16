package signalfaqbot.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OllamaRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    /**
     * Reasoning models (e.g. glm-4.7) otherwise prepend a thinking trace to
     * the response text. `false` keeps only the final answer — we're
     * building a one-shot text reply, not a chat UI that would render the
     * reasoning separately.
     */
    val think: Boolean = false,
)

@Serializable
private data class OllamaResponseChunk(val response: String = "", val done: Boolean = false)

/**
 * Talks to Ollama's HTTP API (`POST /api/generate`) instead of shelling out to
 * `ollama run`, so we get a clean response body rather than scraping a CLI's
 * stdout formatting.
 */
class OllamaLlmClient(
    private val model: String,
    private val baseUrl: String = "http://localhost:11434",
    /** Local models can take minutes to load and generate — no short default timeout. */
    private val timeoutMillis: Long = 5 * 60 * 1000,
    private val client: HttpClient = HttpClient(CIO) {
        engine { requestTimeout = 0 } // disable the CIO engine's own (short) default; HttpTimeout below governs it
        install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) : LlmClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(prompt: String): String {
        // Ollama replies as newline-delimited JSON chunks (`Content-Type:
        // application/x-ndjson`) even with "stream": false — observed with
        // glm-4.7-flash, which apparently ignores that flag. Each chunk
        // carries one piece of `response`; concatenate them all.
        val text = client.post("$baseUrl/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(OllamaRequest(model = model, prompt = prompt))
        }.bodyAsText()

        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(OllamaResponseChunk.serializer(), it) }
            .joinToString("") { it.response }
    }
}
