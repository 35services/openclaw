# CLAUDE.md — paradigms for this project

Read this before making structural changes. It captures decisions and
gotchas that aren't obvious from the code alone.

## The one thing this codebase is actually about

`AnswerService.answer(message: IncomingMessage): Answer` in `core/AnswerService.kt`.
Everything else — Signal polling, the state file, the CLI, the web dashboard —
is plumbing around that one function. When in doubt about where a change
belongs, ask whether it changes *how an answer gets produced* (→ `AnswerService`
and its collaborators) or *how messages flow through the system* (→ `BotLoop`,
`StateStore`).

## Architecture paradigm: interfaces at every I/O boundary, mockable by construction

Every collaborator that touches a filesystem, subprocess, or network is a
`fun interface` or `interface`:
`FaqProvider`, `CalendarProvider`, `PromptRenderer`, `MessageTemplateRenderer`,
`LlmClient`, `AnswerRenderer`, `SignalClient`, `StateStore`. `AnswerService`
and `BotLoop` depend only on these, never on a concrete implementation.
`GatePipeline`/`ClassificationGate`/`GateDiscovery` (see "The gate chain"
below) are the exception — they're plain classes, not interfaces, because
their job *is* the generic, data-driven mechanism; there's no second
implementation to swap in.

This means:
- Tests pass lambdas or tiny fakes (see `src/test/.../fakes/`), not mocking
  framework magic — no MockK/Mockito dependency exists or is needed.
- Real implementations (`FileFaqProvider`, `ScriptCalendarProvider`,
  `OllamaLlmClient`, `SignalCliClient`, `JsonFileStateStore`, ...) live
  alongside their interface and are only wired together in `cli/Cli.kt`.
- Adding a backend (e.g. a third LLM provider, or the signal-cli daemon/
  JSON-RPC client mentioned in the README) means writing one new class and
  wiring it into `LlmClientFactory`/`Cli.kt` — no other file should need to
  change.

Don't add a mocking library. Don't add an interface for something that isn't
an I/O boundary (e.g. `Answer`/`IncomingMessage` are plain data classes on
purpose).

## The "raw shell prefix" convention

`SignalConfig.cliCommand`, `LlmConfig.apfelCommand`, and
`PathsConfig.calendarCommand` are all **raw shell command prefixes**, split on
spaces and executed via `ProcessBuilder`, not resolved binary paths. This
mirrors `simple-filament-tool`'s `signal.json.cli_path` convention (see its
README) so a Docker invocation (`docker run --rm -v ... image ...`) works
exactly like a bare binary name would. Known limitation: this breaks if a
path segment contains a space — acceptable for the documented use cases,
called out in the README rather than solved with a shell-quoting parser.

## Templates are files, not code

`templates/prompt.txt`, `templates/answer.txt`, and every
`templates/classify*.txt`/`templates/answer_*.txt` gate file are read from
disk on every call (`FileBackedPromptRenderer`/`FileBackedAnswerRenderer`/
`FileBackedMessageTemplateRenderer`), not embedded as Kotlin strings or
classpath resources. The explicit point is that someone tuning prompt
wording edits a text file and reruns `ask`/`classify`, no rebuild. Keep
`TemplateRenderer` a dumb `{{key}}` string-replace — do not upgrade it to a
real template engine (conditionals/loops) unless the prompt actually needs
them; that complexity isn't earned yet.

## The gate chain: filesystem convention, not hardcoded classifiers

Before a message reaches `AnswerService`, `BotLoop.runOnce()` checks:
1. **`memberAccounts` skip-list** (config, cheap, no LLM call) — a known
   member's message is never answered, full stop.
2. **`GatePipeline`** — everyone else's message runs through every gate
   `GateDiscovery.discover(paths.templatesDir)` finds, in order.

`GateDiscovery` scans `templatesDir` for `classify*.txt` files, sorted
alphabetically by filename — **that sort order is the execution order**,
which is why the first shipped gate is `classify_0_is_question.txt` (the
`0_` forces it before `classify_practical.txt`). Each gate's name is its
filename minus `.txt`; `GateDiscovery` looks for a file named
`answer<suffix>.txt` in the same directory (`classify_practical.txt` ->
`answer_practical.txt`) to decide what a `NO` does:
- **Matching answer file exists** -> `GateResult.Redirected`: that static
  file is rendered and sent instead of a generated answer.
- **No matching answer file** -> `GateResult.Skipped`: message is dropped
  silently, no reply at all (this is `classify_0_is_question.txt`'s case —
  there's no `answer_0_is_question.txt`).

`GatePipeline.evaluate()` stops at the first `NO`; only a message that gets
`YES` from every gate returns `GateResult.Passed` and reaches
`AnswerService`. **Every gate's prompt must treat `YES` as "let it through"
and `NO` as "stop it here"** — this is the one convention every
`classify*.txt` file must follow, regardless of what the gate is actually
checking. Getting a gate's polarity backwards is a real bug that was hit
while building this: `classify_practical.txt` originally asked "is this
practical?" (YES=practical), which silently redirected *ordinary factual
questions* because the pipeline reads YES as "pass through" for every gate
uniformly. It now asks "is this answerable from FAQ/calendar (i.e. NOT
practical)?" — inverted to match the convention. When adding a gate, phrase
its prompt as "should this message keep moving through the chain?", never
as "is this condition true?", however that reads in the gate's own domain
language.

A message stopped by either the member skip-list or a gate is recorded with
`MessageStatus.SKIPPED`/`REDIRECTED` and which gate stopped it, in
`MessageRecord.error` — shows up in the dashboard history and, like
`ANSWERED`/`FAILED`, is never re-picked-up after a restart.

**Adding a gate is a filesystem operation, not a code change**: drop a
`templates/classify_<name>.txt` (and optionally a matching
`templates/answer_<name>.txt`) in place, and it's picked up automatically —
no `Cli.kt`/`BotLoop`/`Config` edit needed. Don't special-case any gate name
in code (nothing currently does, keep it that way) — `GateDiscovery`
existing generically over the directory is the whole point.

## State machine, and why `PROCESSING` exists

`MessageStatus`: `RECEIVED -> PROCESSING -> ANSWERED | REDIRECTED | FAILED | SKIPPED`
(the member skip-list check happens before `PROCESSING`, so a member's
message goes straight `RECEIVED -> SKIPPED`; the gate chain runs after, so a
message a gate stops goes `RECEIVED -> PROCESSING -> SKIPPED/REDIRECTED`).
The `PROCESSING` state is written *before* the LLM call starts (see
`BotLoop.runOnce()`), specifically so a crash mid-LLM-call (which can take
minutes) leaves an honest, inspectable record instead of either silently
losing the message or re-answering it after restart. If you ever add
automatic retry of `FAILED`/stuck-`PROCESSING` messages, make sure it can
distinguish "still actually running" from "crashed while processing" —
today it cannot (there's no heartbeat), it just never re-picks anything but
`RECEIVED`.

`BotLoop.runForever()` catches exceptions around the whole poll cycle, not
just per-message — a `signal-cli`/Docker/network hiccup must not kill the
process. Keep it that way; don't move the try/catch back down to just the
per-message loop.

## Gotchas hit while building this (don't rediscover them)

- **JDK 21 toolchain required.** The Kotlin 2.0.21 compiler crashes
  (`IllegalArgumentException` parsing `java.version`) on very new JDKs
  (confirmed on JDK 26). Build with `JAVA_HOME` pointed at a JDK 21. See
  README "Building & running".
- **Ollama's `/api/generate` sends newline-delimited JSON even with
  `"stream": false`** (observed with `glm-4.7-flash`) — `Content-Type` comes
  back as `application/x-ndjson`, and Ktor's `ContentNegotiation` won't
  auto-decode a typed body against that. `OllamaLlmClient` reads the body as
  text and parses it line-by-line, concatenating each chunk's `response`
  field. Don't switch back to `.body<T>()` without re-checking this.
- **`signal-cli`'s `-a`/`-o` are global options**, not subcommand flags — they
  must come *before* `receive`/`send`, and `receive`'s timeout flag is the
  short `-t`, not `--timeout` (this is signal-cli 0.14.x's argparse
  behavior; `--json`/`--timeout` are from an older CLI shape and fail with
  "unrecognized arguments"). Verified against a real local `signal-cli 0.14.8`
  install.
- **Local LLM calls need a long HTTP timeout.** `OllamaLlmClient` disables
  the CIO engine's short default and sets a 5-minute `HttpTimeout` — a big
  local model can take a while to load and generate. Don't reintroduce a
  short default.
- **`fetch-calendar.py`'s dependencies (`icalendar`, `recurring_ical_events`)
  are not on the system Python** on the dev machine this was built on
  (Homebrew Python is externally managed, PEP 668). Solved by running the
  script inside `Dockerfile.calendar` instead of relying on any local Python
  environment — that's the default `calendarCommand` in
  `config.example.json`. A `.venv/` exists in the repo as a fallback for
  local dev without Docker; it's gitignored.
- **A gate's YES/NO polarity is easy to get backwards** — see "The gate
  chain" above. If a gate seems to be firing on the wrong messages, the
  prompt's polarity (not the pipeline code) is the first thing to check;
  confirm with `./gradlew run --args='<gate-name> "<message>"'` in isolation.
- **LLM classification is not deterministic** — the same model/prompt can
  answer differently across runs (confirmed directly: `classify_0_is_question`
  sometimes misclassified a real question as chit-chat). This is why
  `benchmark` exists — don't chase single-example failures as if they were
  reproducible bugs; widen the fixture files and look at the pass rate.

## Testing paradigm

- Unit tests use hand-written fakes/lambdas, not a mocking library (see
  "interfaces at every I/O boundary" above).
- `AnswerServiceTest` verifies the pipeline wiring (message + FAQ + calendar
  all reach the prompt; LLM output reaches the answer renderer) without any
  real file, subprocess, or network access.
- `BotLoopTest` verifies the state machine and restart-safety
  (never re-answer an `ANSWERED` message), the member skip-list
  short-circuiting before the gate chain even runs, and the poll loop's
  resilience to a throwing `SignalClient`, using `kotlinx-coroutines-test`'s
  virtual time rather than real `delay()`s. It builds `GatePipeline`
  instances directly with in-memory `ClassificationGate`s (prompt renderers
  that return a fixed marker string, a map-backed fake `LlmClient`) rather
  than real template files — see the `gate(...)` test helper.
- `GatePipelineTest` verifies the chain mechanics in isolation: response
  parsing (case/whitespace tolerance, conservative default for anything
  that isn't `YES`), stopping at the first `NO`, `Skipped` vs `Redirected`
  based on whether a gate has an `answerRenderer`, and `evaluateOne`.
- `GateDiscoveryTest` writes real `classify*.txt`/`answer*.txt` files to a
  temp directory to prove the alphabetical-sort-is-execution-order and
  filename-pairing behavior end to end, the same way `JsonFileStateStoreTest`
  does for the state file.
- `JsonFileStateStoreTest` writes to a real temp file and reopens a fresh
  `JsonFileStateStore` instance to prove restart-safety end to end.

## Manual testing

- `./gradlew run --args="ask \"<message>\""` runs the real `AnswerService`
  pipeline (real FAQ/calendar/LLM, per `config.json`) without touching Signal,
  the state file, or any gate — it always answers. The fast loop for
  iterating on `templates/prompt.txt`/`templates/answer.txt` or trying a
  different model.
- `./gradlew run --args='classify "<message>"'` runs the **whole gate
  chain** and prints which gate stopped it (and how) or that it would
  answer — the go-to command when tuning a gate's wording.
- `./gradlew run --args='<gate-name> "<message>"'` runs **one gate** and
  prints `YES`/`NO` — `<gate-name>` is any discovered gate's `name` (see
  `GatePipeline.gateNames`), e.g. `classify_practical`.
- `./gradlew run --args="benchmark"` runs the whole chain against
  `benchmarks/answerable.txt`/`benchmarks/practical.txt` with the real
  configured LLM and reports a pass rate — this is the tool for judging
  whether a prompt/model change actually helped, not a single manual
  `classify` call (see the "LLM classification is not deterministic" gotcha
  above).
