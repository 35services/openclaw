# Signal FAQ Bot

Standalone bot for **35services g.e.V.** that watches a Signal group, and answers
member questions automatically by asking a local LLM — grounded in the workshop's
FAQ and its live calendar. This replaces the current setup where an agent
(`INSTRUCTIONS.md`) manually follows a runbook; the goal here is a small
long-running Kotlin service that does the same job unattended.

## Requirements

### Signal integration
- Monitor one Signal group ("Offener Kanal 35services") for new incoming messages.
- Use the `signal-cli` Docker image built by
  [35services/simple-filament-tool](https://github.com/35services/simple-filament-tool)
  (its `Dockerfile` / `signal-cli-docker.sh`, run against a `signal-state` volume
  linked to the workshop's Signal number) — the same mechanism that project
  already uses to *send* Signal notifications, reused here for both receiving
  and sending.
- Every reply is sent as a **direct message to the sender's phone number**,
  never back into the group.

### Answering a message
For each new message, gather three inputs and combine them into one LLM prompt:
1. **The incoming message** (text + sender number + language it's written in).
2. **FAQ.md** — the static list of standard questions/answers.
3. **Live calendar** — next ~2 weeks of events, fetched fresh per message
   (reusing the logic in `fetch-calendar.py`: pulls the public Google Calendar
   ICS feed, expands recurring events, flags anything with "Intern" in the
   title/description so it can be excluded from the answer).

The LLM only ever sees what the prompt template puts in front of it — no tool
use, no multi-turn back-and-forth. One prompt in, one answer out.

### Prompting is templated, not hardcoded
- Prompt assembly must go through an external, editable template (not a Kotlin
  string baked into the code), so the wording/instructions can be tuned later
  without a rebuild. Placeholders for: the FAQ text, the calendar dump, the
  incoming message, sender language, and any other framing text ("You are the
  friendly assistant of 35services...", tone rules, the volunteering
  disclaimer, the "forward if unknown" fallback — all the stuff currently
  living in `INSTRUCTIONS.md`).
- The final message sent back to the user is **also templated**: some
  hardcoded framing text/boilerplate around the raw LLM output (e.g. a
  greeting, a signature, a "this was answered automatically" note) — separate
  from the prompt template.

### Local LLM, pluggable backend
- Must run against a **local** LLM, configurable between two backends:
  - **Ollama** — any model already pulled locally (e.g.
    `ollama run glm-4.7-flash:latest "hello world"` as the baseline shape of a
    call, though the bot should talk to Ollama's HTTP API rather than shelling
    out to the CLI).
  - **Apfel** (Apple Intelligence via the `apfel` CLI tool).
- Backend + model selectable via config, no code change needed to switch.

### State / processing log
- A state file (successor to `last-answered.json`) tracks, per incoming
  message: message id/timestamp, sender, and a status — e.g.
  `received → processing → answered` (or `failed`). Since LLM processing can
  take a noticeable amount of time, a message must be marked `processing`
  *before* the LLM call starts, so a crash/restart mid-processing doesn't
  cause it to be silently dropped or double-answered.
- On startup, the bot resumes from this file rather than re-answering
  everything from scratch.

### Non-functional / carried over from `INSTRUCTIONS.md`
- Reply in the same language as the incoming message (German or English).
- Friendly, brief, informal tone (no "Sie").
- Always mention the volunteering disclaimer when opening-hours are discussed.
- Never surface events marked "Intern".
- If neither FAQ nor calendar cover the question, send the
  "forwarding your question" fallback instead of guessing.

### Web dashboard
- A small read-only status page shows the current **queue** (received/processing)
  and the **history** of answered/failed messages, rendered straight from the
  state file — no separate database.

## Tech stack

Kotlin + [Ktor](https://ktor.io) (JetBrains' own, coroutine-native — currently
the most idiomatic "simple server" in the Kotlin space, much lighter than
Spring Boot), Gradle (Kotlin DSL), `kotlinx.serialization` for JSON,
`kotlinx.coroutines` for the poll loop. Ktor is used less as a "server" and
more for its HTTP **client** (talking to Ollama's REST API) and, now, its
tiny **server** for the dashboard.

- **Signal**: shells out to `signal-cli` (via the Docker image from
  [35services/simple-filament-tool](https://github.com/35services/simple-filament-tool))
  on a poll loop, `receive -o json`. See "Future: signal-cli daemon (JSON-RPC)"
  below for the lower-latency alternative deliberately not built yet.
- **Calendar**: shells out to `fetch-calendar.py`, wrapped in `Dockerfile.calendar`
  so no local Python setup is required. Reimplementing its ICS/recurrence
  logic in Kotlin would just be a second copy to keep in sync.
- **Templating**: dumb `{{placeholder}}` substitution in plain `.txt` files
  under `templates/` — no templating library. `templates/prompt.txt` builds
  the LLM prompt; `templates/answer.txt` wraps the LLM's raw output into the
  message actually sent.
- **LLM**: `LlmClient` interface with `OllamaLlmClient` (HTTP, `POST /api/generate`,
  `stream: false`, `think: false`) and `ApfelLlmClient` (shells out to `apfel`),
  chosen via `llm.provider` in config.
- **State**: `JsonFileStateStore` — a single JSON array, rewritten via
  temp-file-then-rename on every change. One workshop's Signal group, not a
  firehose; no database needed.

## Building & running

Requires a **JDK 21 toolchain** — the Kotlin 2.0.21 compiler bundled here
crashes parsing very new `java.version` strings (confirmed against a system
JDK 26). If `java -version` shows something newer than 21, install one
(`brew install openjdk@21`) and either export `JAVA_HOME` before running
Gradle, or set `org.gradle.java.home` in a local (gitignored) `gradle.properties`.

```bash
# one-time setup
cp config.example.json config.json        # fill in signal.account/groupId, llm.model
docker build -f Dockerfile.calendar -t signal-faq-bot-calendar .

./gradlew build                            # compiles + runs the test suite
./gradlew run --args="ask \"Wann habt ihr auf?\" --sender +491701234567"
./gradlew run --args="run"                 # starts polling + the dashboard on :8080
```

See `Cli.kt`'s usage text (`./gradlew run` with no args) for all flags.

## Future: signal-cli daemon (JSON-RPC)

Not built — polling `receive` is simpler and good enough for one group's
traffic — but kept here as the documented upgrade path if poll latency
(`signal.pollIntervalSeconds`) ever becomes a problem:

```bash
signal-cli -a +<account> daemon --http-port 8080
```

This exposes a JSON-RPC API; messages arrive as they're delivered instead of
on a fixed interval (subscribe over its WebSocket, or long-poll `receive` via
JSON-RPC). Switching means replacing `SignalCliClient.receiveMessages()` with
a client that talks JSON-RPC/WebSocket instead of shelling out per poll —
`SignalClient` is already an interface for exactly this kind of swap.

## Known limitations / not handled yet
- `cliCommand`/`apfelCommand`/`calendarCommand` are split on literal spaces —
  fine for the documented `docker run ...` shapes, but a path containing a
  space would break it.
- No de-dup beyond message id; if `signal-cli receive` ever redelivers a
  message with a new id, it would be answered twice.
- The dashboard is unauthenticated — fine on `localhost`, not meant for
  exposure beyond that.
