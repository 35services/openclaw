package signalfaqbot.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

fun interface CalendarProvider {
    suspend fun fetchUpcoming(): String
}

/**
 * Runs the existing `fetch-calendar.py` (or its Docker-wrapped equivalent,
 * see `Dockerfile.calendar`) as a subprocess — reimplementing its ICS
 * parsing, recurring-event expansion and "Intern" filtering in Kotlin would
 * just be a second copy of the same logic to keep in sync.
 *
 * [command] is a raw shell prefix, same convention as [signalfaqbot.SignalConfig.cliCommand]
 * and [signalfaqbot.LlmConfig.apfelCommand] — e.g. `"docker run --rm signal-faq-bot-calendar"`
 * (recommended, no local Python setup needed) or `"python3 fetch-calendar.py"` for local dev.
 */
class ProcessCalendarProvider(private val command: String) : CalendarProvider {
    override suspend fun fetchUpcoming(): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command.split(" "))
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        check(finished) { "calendar command timed out: $command" }
        check(process.exitValue() == 0) { "calendar command failed: $errorOutput" }
        output
    }
}

/** Fixed text — used by tests instead of shelling out to a process. */
class StaticCalendarProvider(private val text: String) : CalendarProvider {
    override suspend fun fetchUpcoming(): String = text
}
