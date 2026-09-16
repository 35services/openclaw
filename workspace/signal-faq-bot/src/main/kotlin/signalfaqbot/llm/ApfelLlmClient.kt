package signalfaqbot.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Shells out to the `apfel` CLI to harness on-device Apple Intelligence.
 * `command` is a raw shell prefix (like `signal.cliCommand` in the filament
 * tool), so it can be swapped for a full path or wrapped call if needed.
 */
class ApfelLlmClient(
    private val command: String = "apfel",
    private val model: String? = null,
) : LlmClient {
    override suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        val args = mutableListOf<String>().apply {
            addAll(command.split(" "))
            if (model != null) {
                add("--model")
                add(model)
            }
            add(prompt)
        }
        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        check(finished) { "apfel timed out" }
        check(process.exitValue() == 0) { "apfel failed: $errorOutput" }
        output
    }
}
