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
  `RECEIVED -> PROCESSING -> ANSWERED/FAILED`, so a crash mid-processing
  doesn't drop or double-answer a message on restart.
- `Dockerfile.calendar`: wraps `fetch-calendar.py` and its Python
  dependencies so the calendar fetch needs no local Python setup.
- Web dashboard (Ktor server) showing the current queue and the
  answered/failed message history, read straight from the state file.
- CLI: `run` (start polling + dashboard) and `ask "<message>"` (run the
  answer pipeline once for manual/local testing, without touching Signal or
  the state file).
- `README.md` documenting requirements, architecture, build/run instructions,
  and the signal-cli daemon/JSON-RPC path kept as a future reference instead
  of built now.
