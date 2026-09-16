package signalfaqbot.llm

/** One backend, one method: send a prompt, get the model's raw text back. */
fun interface LlmClient {
    suspend fun complete(prompt: String): String
}
