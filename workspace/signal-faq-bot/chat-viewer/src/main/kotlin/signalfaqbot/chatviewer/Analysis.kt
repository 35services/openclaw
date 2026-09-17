package signalfaqbot.chatviewer

import kotlinx.serialization.Serializable

/**
 * Mirrors signalfaqbot.chatanalysis.{GateCheck,AnalysisStatus,MessageAnalysis}'s
 * JSON wire format exactly, same rationale as ChatData.kt: kept as its own
 * copy rather than a project(":") dependency, so this viewer stays standalone.
 */
@Serializable
data class GateCheck(val gateName: String, val passed: Boolean)

@Serializable
enum class AnalysisStatus { ANSWERED, REDIRECTED, SKIPPED, FAILED }

@Serializable
data class MessageAnalysis(
    val messageIndex: Int,
    val sender: String?,
    val text: String,
    val checks: List<GateCheck> = emptyList(),
    val status: AnalysisStatus,
    val stoppedByGate: String? = null,
    val answerText: String? = null,
    val error: String? = null,
    val updatedAt: Long,
)
