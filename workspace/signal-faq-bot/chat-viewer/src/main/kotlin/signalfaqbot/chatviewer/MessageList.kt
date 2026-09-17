package signalfaqbot.chatviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MessageList(messages: List<ChatMessage>, analysisByIndex: Map<Int, MessageAnalysis> = emptyMap()) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(messages.size) { index ->
            MessageRow(messages[index], analysisByIndex[index], analysisMode = analysisByIndex.isNotEmpty())
            HorizontalDivider()
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage, analysis: MessageAnalysis?, analysisMode: Boolean) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = message.sender ?: "(unknown sender)",
                    fontWeight = FontWeight.Bold,
                    color = if (message.sender == null) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                message.senderTag?.let {
                    Text("· $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
                if (message.edited) {
                    Text("(edited)", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = listOfNotNull(message.date, message.time).joinToString(" "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (message.replyToSender != null || message.replyToText != null) {
            Column(modifier = Modifier.padding(top = 4.dp, start = 8.dp)) {
                Text(
                    text = "↩ ${message.replyToSender ?: "?"}: ${message.replyToText.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }

        Text(text = message.text, modifier = Modifier.padding(top = 4.dp))

        if (message.reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions.forEach { reaction ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(reaction, style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(),
                    )
                }
            }
        }

        if (analysisMode) {
            Row(
                modifier = Modifier.padding(top = 6.dp).clickable(enabled = analysis != null) { expanded = !expanded },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusChip(analysis)
                if (analysis != null) {
                    Text(
                        text = if (expanded) "▲ hide" else "▼ details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (expanded && analysis != null) {
                AnalysisDetail(analysis)
            }
        }
    }
}

@Composable
private fun StatusChip(analysis: MessageAnalysis?) {
    val (label, color) = when (analysis?.status) {
        AnalysisStatus.ANSWERED -> "ANSWERED" to Color(0xFF2E7D32)
        AnalysisStatus.REDIRECTED -> "REDIRECTED" to Color(0xFFEF6C00)
        AnalysisStatus.SKIPPED -> "SKIPPED" to Color(0xFF757575)
        AnalysisStatus.FAILED -> "FAILED" to MaterialTheme.colorScheme.error
        null -> "pending…" to Color(0xFFBDBDBD)
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White) },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = color),
    )
}

@Composable
private fun AnalysisDetail(analysis: MessageAnalysis) {
    Column(
        modifier = Modifier.padding(top = 6.dp, start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        analysis.checks.forEach { check ->
            Text(
                text = "${if (check.passed) "✓" else "✗"} ${check.gateName}",
                style = MaterialTheme.typography.bodySmall,
                color = if (check.passed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        analysis.answerText?.let {
            Text(
                text = "Answer:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        analysis.error?.let {
            Text(
                text = "Error: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
