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
`FaqProvider`, `CalendarProvider`, `PromptRenderer`, `LlmClient`, `AnswerRenderer`,
`SignalClient`, `StateStore`. `AnswerService` and `BotLoop` depend only on
these, never on a concrete implementation.

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

`templates/prompt.txt` and `templates/answer.txt` are read from disk on every
call (`FileBackedPromptRenderer`/`FileBackedAnswerRenderer`), not embedded as
Kotlin strings or classpath resources. The explicit point is that someone
tuning prompt wording edits a text file and reruns `ask`, no rebuild. Keep
`TemplateRenderer` a dumb `{{key}}` string-replace — do not upgrade it to a
real template engine (conditionals/loops) unless the prompt actually needs
them; that complexity isn't earned yet.

## State machine, and why `PROCESSING` exists

`MessageStatus`: `RECEIVED -> PROCESSING -> ANSWERED | FAILED`. The
`PROCESSING` state is written *before* the LLM call starts (see
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

## Testing paradigm

- Unit tests use hand-written fakes/lambdas, not a mocking library (see
  "interfaces at every I/O boundary" above).
- `AnswerServiceTest` verifies the pipeline wiring (message + FAQ + calendar
  all reach the prompt; LLM output reaches the answer renderer) without any
  real file, subprocess, or network access.
- `BotLoopTest` verifies the state machine and restart-safety
  (never re-answer an `ANSWERED` message) and the poll loop's resilience to a
  throwing `SignalClient`, using `kotlinx-coroutines-test`'s virtual time
  rather than real `delay()`s.
- `JsonFileStateStoreTest` writes to a real temp file and reopens a fresh
  `JsonFileStateStore` instance to prove restart-safety end to end.

## Manual testing

`./gradlew run --args="ask \"<message>\""` runs the real `AnswerService`
pipeline (real FAQ/calendar/LLM, per `config.json`) without touching Signal
or the state file — the fast loop for iterating on `templates/prompt.txt` or
trying a different model.
