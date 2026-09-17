package signalfaqbot.chatanalysis

import kotlinx.serialization.Serializable

/** One gate's verdict for a message, in the order it actually ran. */
@Serializable
data class GateCheck(val gateName: String, val passed: Boolean)

/** Mirrors [signalfaqbot.core.GateResult] plus a terminal ANSWERED case for messages that passed every gate. */
@Serializable
enum class AnalysisStatus { ANSWERED, REDIRECTED, SKIPPED, FAILED }

/**
 * What [signalfaqbot.core.BotLoop]'s decision flow (member skip-list, then
 * the gate chain, then [signalfaqbot.core.AnswerService]) would have done
 * with one historical message — nothing is ever sent, this is analysis only.
 *
 * [checks] lists only the gates actually evaluated, in order: a pipeline
 * that stops at gate 2 of 3 never reaches gate 3, so gate 3 simply isn't in
 * the list — this is the *true* execution trace, not a hypothetical
 * "run every gate regardless" one.
 */
@Serializable
data class MessageAnalysis(
    val messageIndex: Int,
    val sender: String?,
    val text: String,
    val checks: List<GateCheck> = emptyList(),
    val status: AnalysisStatus,
    /** Which gate produced a SKIPPED/REDIRECTED outcome; null for ANSWERED/FAILED. */
    val stoppedByGate: String? = null,
    /** The LLM-crafted answer (ANSWERED) or the gate's static redirect text (REDIRECTED). */
    val answerText: String? = null,
    /** Why a message is SKIPPED without reaching a gate (no sender, member account) or FAILED. */
    val error: String? = null,
    val updatedAt: Long,
)
