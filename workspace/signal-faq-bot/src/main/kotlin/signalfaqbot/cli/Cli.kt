package signalfaqbot.cli

import kotlinx.coroutines.runBlocking
import signalfaqbot.Config
import signalfaqbot.calendar.ProcessCalendarProvider
import signalfaqbot.core.AnswerService
import signalfaqbot.core.BotLoop
import signalfaqbot.faq.FileFaqProvider
import signalfaqbot.llm.LlmClientFactory
import signalfaqbot.model.IncomingMessage
import signalfaqbot.signal.SignalCliClient
import signalfaqbot.state.JsonFileStateStore
import signalfaqbot.template.FileBackedAnswerRenderer
import signalfaqbot.template.FileBackedPromptRenderer
import signalfaqbot.web.startDashboard

/**
 * Two subcommands:
 *  - `run` starts the real bot (Signal polling + web dashboard).
 *  - `ask` runs the [AnswerService] pipeline once for a message you type in,
 *    without touching Signal or the state file — the fast loop for tuning
 *    the prompt/answer templates or trying a different model.
 */
object Cli {
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "run" -> run(args.drop(1))
            "ask" -> ask(args.drop(1))
            else -> printUsage()
        }
    }

    private fun printUsage() {
        println(
            """
            Usage:
              run [--config <path>]
                  Start polling Signal and serving the web dashboard.

              ask "<message>" [--config <path>] [--sender <number>] [--language de|en] [--send]
                  Run the answer pipeline once for manual testing. Prints the
                  rendered answer to stdout; only actually sends it via Signal
                  when --send is given.

            Defaults: --config config.json, --sender +000000000, --language de
            """.trimIndent(),
        )
    }

    private fun run(args: List<String>) = runBlocking {
        val config = Config.load(flag(args, "--config") ?: "config.json")

        val signalClient = SignalCliClient(config.signal.cliCommand, config.signal.account, config.signal.groupId)
        val stateStore = JsonFileStateStore(config.paths.state)
        val answerService = buildAnswerService(config)

        if (config.web.enabled) {
            startDashboard(stateStore, config.web.port)
            println("Dashboard listening on http://localhost:${config.web.port}")
        }

        println("Polling Signal group ${config.signal.groupId} every ${config.signal.pollIntervalSeconds}s...")
        BotLoop(signalClient, stateStore, answerService, config.signal.pollIntervalSeconds).runForever()
    }

    private fun ask(args: List<String>) = runBlocking {
        require(args.isNotEmpty()) { "Missing \"<message>\" argument. See usage below.\n" + usageText() }
        val text = args[0]
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val sender = flag(args, "--sender") ?: "+000000000"
        val language = flag(args, "--language") ?: "de"
        val send = args.contains("--send")

        val message = IncomingMessage(
            id = "cli-${System.currentTimeMillis()}",
            sender = sender,
            text = text,
            timestamp = System.currentTimeMillis(),
            language = language,
        )

        val answer = buildAnswerService(config).answer(message)
        println("--- Answer for $sender ---")
        println(answer.text)

        if (send) {
            SignalCliClient(config.signal.cliCommand, config.signal.account, config.signal.groupId)
                .sendDirectMessage(answer.recipient, answer.text)
            println("Sent via Signal.")
        }
    }

    private fun buildAnswerService(config: Config): AnswerService = AnswerService(
        faqProvider = FileFaqProvider(config.paths.faq),
        calendarProvider = ProcessCalendarProvider(config.paths.calendarCommand),
        promptRenderer = FileBackedPromptRenderer(config.paths.promptTemplate),
        llmClient = LlmClientFactory.from(config.llm),
        answerRenderer = FileBackedAnswerRenderer(config.paths.answerTemplate),
    )

    private fun flag(args: List<String>, name: String): String? {
        val idx = args.indexOf(name)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    private fun usageText(): String = "Run without arguments to see usage."
}
