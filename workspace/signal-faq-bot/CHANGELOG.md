# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project does not (yet) follow Semantic Versioning releases — changes are
grouped under `[Unreleased]` until the bot is deployed and tagged.

## [Unreleased]

### Added
- Kotlin/Ktor bot skeleton: polls `signal-cli receive` for new group messages,
  answers each one via a local LLM grounded in `FAQ.md` and the live calendar,
  and sends the reply as a Signal DM.
- `AnswerService`: the mockable `IncomingMessage -> Answer` pipeline at the
  core of the bot, with every collaborator (FAQ, calendar, LLM, prompt/answer
  rendering) behind an interface for unit testing without touching a
  filesystem, subprocess, or network.
- File-templated prompt (`templates/prompt.txt`) and answer
  (`templates/answer.txt`) — editable without a rebuild.
- Pluggable local LLM backend: `OllamaLlmClient` (HTTP API, `think: false` to
  suppress reasoning traces) and `ApfelLlmClient` (Apple Intelligence via the
  `apfel` CLI), selected via `llm.provider` in config.
- `JsonFileStateStore`: tracks each message through
  `RECEIVED -> PROCESSING -> ANSWERED/REDIRECTED/FAILED/SKIPPED`, so a crash
  mid-processing doesn't drop or double-answer a message on restart.
- `Dockerfile.calendar`: wraps `fetch-calendar.py` and its Python
  dependencies so the calendar fetch needs no local Python setup.
- Web dashboard (Ktor server) showing the current queue and the full message
  history (answered/redirected/failed/skipped), read straight from the
  state file.
- CLI: `run` (start polling + dashboard), `ask "<message>"` (run the answer
  pipeline once for manual/local testing, skipping every gate), `classify
  "<message>"` (dry-run the whole gate chain and print the outcome),
  `<gate-name> "<message>"` (run one discovered gate in isolation, e.g.
  `classify_practical`), and `benchmark` (run the gate chain against
  `benchmarks/*.txt` fixtures with the real configured LLM and report
  accuracy).
- `README.md` documenting requirements, architecture, build/run instructions,
  and the signal-cli daemon/JSON-RPC path kept as a future reference instead
  of built now.
- Message filtering before a reply is even attempted: a configured
  `signal.memberAccounts` skip-list (organization members' messages are
  never auto-answered), plus a filesystem-driven gate chain
  (`GateDiscovery`/`GatePipeline`) — every `templates/classify*.txt` file,
  run in alphabetical order, each optionally paired with a
  `templates/answer_<name>.txt` static redirect by naming convention. Ships
  with two gates: `classify_0_is_question.txt` (filters out chit-chat/replies,
  silent skip) and `classify_practical.txt` (filters out practical how-to/
  repair questions the FAQ/calendar can't answer, redirects to
  `answer_practical.txt`'s "let's talk in chat" message instead of guessing).
  Adding a gate is dropping a template file, no code change needed.
- `benchmarks/answerable.txt` and `benchmarks/practical.txt`: seed fixture
  files (a few starter examples each) for the `benchmark` CLI command, to be
  grown with real messages collected from the group.
- `ChatExportParser` (`./gradlew run --args='parse-chat <file>'`): parses a
  Signal Desktop chat export (plain-text copy/paste) into JSON — messages
  (sender, text, timestamp, reactions, quote-reply info) and join/leave/
  group-update events, with a printed anomaly list for anything it couldn't
  confidently resolve. A separate, offline tool laying groundwork for
  running the bot's gate/answer logic against real historical messages.
- `analyze-chat` (`./gradlew run --args='analyze-chat <chat.json>'`): replays
  every message from a `parse-chat` export through the bot's real gate chain
  and `AnswerService`, recording each message's per-gate trace and outcome
  (ANSWERED/REDIRECTED/SKIPPED/FAILED) to a resumable JSON file — a killed or
  crashed run picks back up with zero wasted LLM calls on already-analyzed
  messages.
- Two contextual signals now feed every classifier prompt (`{{time_since_joined}}`,
  `{{messages_last_24h}}`, see `MessageContext`), used by `analyze-chat` to
  give the LLM a weak signal that a message from a recently-joined or
  newly-active sender is more likely a general FAQ question than a practical
  how-to one.
- `chat-viewer`: a standalone Compose Desktop app (`./gradlew :chat-viewer:run --args="<chat.json>"`)
  to browse a parsed chat export, live-polling the matching `analyze-chat`
  output file so per-message gate/answer results fill in as a background
  analysis run progresses. `./gradlew :chat-viewer:screenshot` renders it
  against mock data to `chat-viewer/screenshot.png` headlessly (Compose's
  off-screen Skia rasterizer, no display or screen-recording permission
  needed) — see `README.md`.
