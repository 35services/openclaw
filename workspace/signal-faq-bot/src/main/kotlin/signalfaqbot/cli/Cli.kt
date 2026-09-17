package signalfaqbot.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import signalfaqbot.Config
import signalfaqbot.calendar.ProcessCalendarProvider
import signalfaqbot.chatanalysis.AnalysisStatus
import signalfaqbot.chatanalysis.ChatAnalysisStore
import signalfaqbot.chatanalysis.ChatAnalyzer
import signalfaqbot.chatimport.ChatExportParser
import signalfaqbot.chatimport.ParsedChat
import signalfaqbot.core.AnswerService
import signalfaqbot.core.BotLoop
import signalfaqbot.core.GateDiscovery
import signalfaqbot.core.GatePipeline
import signalfaqbot.core.GateResult
import signalfaqbot.faq.FileFaqProvider
import signalfaqbot.llm.LlmClient
import signalfaqbot.llm.LlmClientFactory
import signalfaqbot.model.IncomingMessage
import signalfaqbot.signal.SignalCliClient
import signalfaqbot.state.JsonFileStateStore
import signalfaqbot.template.FileBackedAnswerRenderer
import signalfaqbot.template.FileBackedPromptRenderer
import signalfaqbot.web.startDashboard
import java.io.File

private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Subcommands:
 *  - `run` starts the real bot (Signal polling + web dashboard).
 *  - `ask` runs the [AnswerService] pipeline once for a message you type in,
 *    without touching Signal, the state file, or any gate — the fast loop
 *    for tuning the prompt/answer templates or trying a different model.
 *  - `classify` runs the *whole* gate chain for a message and prints the
 *    outcome (skip / redirect / answer), without touching Signal.
 *  - `<gate-name>` (e.g. `classify_practical`) runs *just that one gate* —
 *    any file matching `templates/classify*.txt` is a valid gate name, see
 *    [GateDiscovery].
 *  - `benchmark` runs the whole gate chain against the fixture files under
 *    `benchmarks/` using the real configured LLM, and reports accuracy.
 */
object Cli {
    fun main(args: Array<String>) {
        when (val command = args.getOrNull(0)) {
            "run" -> run(args.drop(1))
            "ask" -> ask(args.drop(1))
            "classify" -> classify(args.drop(1))
            "benchmark" -> benchmark(args.drop(1))
            "parse-chat" -> parseChat(args.drop(1))
            "analyze-chat" -> analyzeChat(args.drop(1))
            null -> printUsage()
            else -> classifyGate(command, args.drop(1))
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
                  when --send is given. Skips every gate — it always answers.

              classify "<message>" [--config <path>] [--sender <number>] [--language de|en]
                  Run the full gate chain for a message and print what the
                  bot would do (skip / redirect / answer), without answering.

              <gate-name> "<message>" [--config <path>] [--sender <number>] [--language de|en]
                  Run just one gate and print YES/NO. <gate-name> is any
                  templates/classify*.txt file's name minus ".txt", e.g.
                  "classify_practical" for templates/classify_practical.txt.

              benchmark [--config <path>]
                  Run the full gate chain against benchmarks/answerable.txt
                  and benchmarks/practical.txt using the real configured LLM,
                  and report accuracy.

              parse-chat <input.txt> [--output <path>]
                  Parse a Signal Desktop chat export (plain-text copy/paste)
                  into JSON: messages (sender, text, timestamp) and join/leave
                  events. Defaults --output to "<input.txt>.json". Prints a
                  summary and any parsing anomalies found (see
                  ChatExportParser's doc comment for the format it expects).

              analyze-chat <chat.json> [--config <path>] [--output <path>]
                  Replay the bot's real decision flow (member skip-list, gate
                  chain, AnswerService) against every message in a
                  parse-chat JSON file, using the real configured LLM.
                  Nothing is ever sent anywhere. Defaults --output to
                  "<chat.json>.analysis.json". Can take a while for a real
                  export (one gate-chain run, and sometimes one answer call,
                  per message) — progress is saved after every message, so
                  killing it and running the same command again resumes
                  instead of restarting or re-spending LLM calls.

            Defaults: --config config.json, --sender +000000000, --language de
            """.trimIndent(),
        )
    }

    private fun run(args: List<String>) = runBlocking {
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val llmClient = LlmClientFactory.from(config.llm)

        val signalClient = SignalCliClient(config.signal.cliCommand, config.signal.account, config.signal.groupId)
        val stateStore = JsonFileStateStore(config.paths.state)
        val gatePipeline = buildGatePipeline(config, llmClient)

        if (config.web.enabled) {
            startDashboard(stateStore, config.web.port)
            println("Dashboard listening on http://localhost:${config.web.port}")
        }

        println("Polling Signal group ${config.signal.groupId} every ${config.signal.pollIntervalSeconds}s...")
        println("Skipping messages from ${config.signal.memberAccounts.size} configured member account(s).")
        println("Gate chain (in order): ${gatePipeline.gateNames.joinToString(", ").ifEmpty { "(none found)" }}")
        BotLoop(
            signalClient = signalClient,
            stateStore = stateStore,
            gatePipeline = gatePipeline,
            answerService = buildAnswerService(config, llmClient),
            pollIntervalSeconds = config.signal.pollIntervalSeconds,
            memberAccounts = config.signal.memberAccounts.toSet(),
        ).runForever()
    }

    private fun ask(args: List<String>) = runBlocking {
        require(args.isNotEmpty()) { "Missing \"<message>\" argument. See usage below.\n" + usageText() }
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val message = messageFromArgs(args)
        val send = args.contains("--send")

        val answer = buildAnswerService(config, LlmClientFactory.from(config.llm)).answer(message)
        println("--- Answer for ${message.sender} ---")
        println(answer.text)

        if (send) {
            SignalCliClient(config.signal.cliCommand, config.signal.account, config.signal.groupId)
                .sendDirectMessage(answer.recipient, answer.text)
            println("Sent via Signal.")
        }
    }

    private fun classify(args: List<String>) = runBlocking {
        require(args.isNotEmpty()) { "Missing \"<message>\" argument. See usage below.\n" + usageText() }
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val message = messageFromArgs(args)

        if (message.sender in config.signal.memberAccounts) {
            println("member=YES -> SKIP (sender is a configured member account, no LLM call made)")
            return@runBlocking
        }
        println("member=NO")

        val pipeline = buildGatePipeline(config, LlmClientFactory.from(config.llm))
        println("Gate chain (in order): ${pipeline.gateNames.joinToString(", ")}")
        when (val result = pipeline.evaluate(message)) {
            GateResult.Passed -> println("-> ANSWER (passed every gate; would run the full FAQ/calendar pipeline)")
            is GateResult.Skipped -> println("-> SKIP (gate '${result.gateName}' returned NO, no static answer configured for it)")
            is GateResult.Redirected -> println("-> REDIRECT via gate '${result.gateName}':\n${result.text}")
        }
    }

    private fun classifyGate(gateName: String, args: List<String>) = runBlocking {
        require(args.isNotEmpty()) { "Missing \"<message>\" argument. See usage below.\n" + usageText() }
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val message = messageFromArgs(args)
        val pipeline = buildGatePipeline(config, LlmClientFactory.from(config.llm))

        val result = pipeline.evaluateOne(gateName, message)
        if (result == null) {
            println("Unknown command/gate \"$gateName\". Available gates: ${pipeline.gateNames.joinToString(", ")}")
            println()
            printUsage()
            return@runBlocking
        }
        println(if (result) "YES" else "NO")
    }

    private fun benchmark(args: List<String>) = runBlocking {
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val pipeline = buildGatePipeline(config, LlmClientFactory.from(config.llm))
        println("Gate chain (in order): ${pipeline.gateNames.joinToString(", ")}")

        println()
        println("=== benchmarks/answerable.txt (expect: passes every gate) ===")
        val answerable = runBenchmarkCategory(loadBenchmarkFixture("benchmarks/answerable.txt"), pipeline) { it is GateResult.Passed }

        println()
        println("=== benchmarks/practical.txt (expect: redirected by some gate) ===")
        val practical = runBenchmarkCategory(loadBenchmarkFixture("benchmarks/practical.txt"), pipeline) { it is GateResult.Redirected }

        println()
        println("Summary: answerable ${answerable.first}/${answerable.second} correct, practical ${practical.first}/${practical.second} correct")
    }

    private suspend fun runBenchmarkCategory(
        cases: List<String>,
        pipeline: GatePipeline,
        expected: (GateResult) -> Boolean,
    ): Pair<Int, Int> {
        if (cases.isEmpty()) {
            println("(no test cases yet — add lines to the fixture file)")
            return 0 to 0
        }
        var correct = 0
        for (text in cases) {
            val message = IncomingMessage(id = "bench-${text.hashCode()}", sender = "+000000000", text = text, timestamp = 0)
            val result = pipeline.evaluate(message)
            val pass = expected(result)
            if (pass) correct++
            println("${if (pass) "PASS" else "FAIL"}  ${describe(result)}  \"$text\"")
        }
        return correct to cases.size
    }

    private fun describe(result: GateResult): String = when (result) {
        GateResult.Passed -> "PASSED"
        is GateResult.Skipped -> "SKIPPED(${result.gateName})"
        is GateResult.Redirected -> "REDIRECTED(${result.gateName})"
    }

    private fun parseChat(args: List<String>) {
        require(args.isNotEmpty()) { "Missing \"<input.txt>\" argument. See usage below.\n" + usageText() }
        val inputPath = args[0]
        val outputPath = flag(args, "--output") ?: "$inputPath.json"

        val parsed = ChatExportParser.parse(File(inputPath).readLines())
        File(outputPath).writeText(prettyJson.encodeToString(ParsedChat.serializer(), parsed))

        println("Parsed ${parsed.messages.size} message(s) and ${parsed.events.size} event(s) from $inputPath -> $outputPath")
        if (parsed.anomalies.isNotEmpty()) {
            println("${parsed.anomalies.size} anomalies:")
            parsed.anomalies.forEach { println("  - $it") }
        }
    }

    private fun analyzeChat(args: List<String>) = runBlocking {
        require(args.isNotEmpty()) { "Missing \"<chat.json>\" argument. See usage below.\n" + usageText() }
        val config = Config.load(flag(args, "--config") ?: "config.json")
        val chatPath = args[0]
        val outputPath = flag(args, "--output") ?: "$chatPath.analysis.json"

        val chat = prettyJson.decodeFromString(ParsedChat.serializer(), File(chatPath).readText())
        val llmClient = LlmClientFactory.from(config.llm)
        val store = ChatAnalysisStore(outputPath)
        val analyzer = ChatAnalyzer(
            gatePipeline = buildGatePipeline(config, llmClient),
            answerService = buildAnswerService(config, llmClient),
            store = store,
            memberAccounts = config.signal.memberAccounts.toSet(),
        )

        val alreadyDone = chat.messages.indices.count { store.isDone(it) }
        println(
            "Analyzing ${chat.messages.size} message(s) from $chatPath -> $outputPath" +
                if (alreadyDone > 0) " ($alreadyDone already done, resuming)" else "",
        )
        analyzer.analyzeAll(chat.messages, chat.events)

        val results = store.all()
        val byStatus = results.groupingBy { it.status }.eachCount()
        println("Done. ${results.size}/${chat.messages.size} analyzed:")
        AnalysisStatus.entries.forEach { status -> println("  $status: ${byStatus[status] ?: 0}") }
    }

    private fun loadBenchmarkFixture(path: String): List<String> {
        val file = File(path)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun messageFromArgs(args: List<String>): IncomingMessage = IncomingMessage(
        id = "cli-${System.currentTimeMillis()}",
        sender = flag(args, "--sender") ?: "+000000000",
        text = args[0],
        timestamp = System.currentTimeMillis(),
        language = flag(args, "--language") ?: "de",
    )

    private fun buildAnswerService(config: Config, llmClient: LlmClient): AnswerService = AnswerService(
        faqProvider = FileFaqProvider(config.paths.faq),
        calendarProvider = ProcessCalendarProvider(config.paths.calendarCommand),
        promptRenderer = FileBackedPromptRenderer(config.paths.promptTemplate),
        llmClient = llmClient,
        answerRenderer = FileBackedAnswerRenderer(config.paths.answerTemplate),
    )

    private fun buildGatePipeline(config: Config, llmClient: LlmClient): GatePipeline =
        GatePipeline(GateDiscovery.discover(config.paths.templatesDir), llmClient)

    private fun flag(args: List<String>, name: String): String? {
        val idx = args.indexOf(name)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    private fun usageText(): String = "Run without arguments to see usage."
}
