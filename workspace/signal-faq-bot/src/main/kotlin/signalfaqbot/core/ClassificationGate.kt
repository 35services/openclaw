package signalfaqbot.core

import signalfaqbot.template.MessageTemplateRenderer

/**
 * One step in [GatePipeline]: an LLM yes/no check with a templated prompt
 * (`templates/<name>.txt`, where `name` starts with "classify"). If
 * [answerRenderer] is present — a matching `templates/answer<suffix>.txt`
 * exists, e.g. `classify_practical.txt` <-> `answer_practical.txt` — a NO
 * sends that static message instead of the full FAQ/calendar answer. If
 * absent, a NO just skips the message silently (no reply sent at all).
 */
data class ClassificationGate(
    val name: String,
    val promptRenderer: MessageTemplateRenderer,
    val answerRenderer: MessageTemplateRenderer?,
)
