package signalfaqbot.chatviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EventList(events: List<ChatEvent>, anomalies: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        if (anomalies.isNotEmpty()) {
            item {
                Text(
                    "Anomalies (${anomalies.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
            }
            items(anomalies) { anomaly ->
                Text(
                    text = "⚠ $anomaly",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        }

        item {
            Text(
                "Events (${events.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 4.dp),
            )
        }
        items(events) { event ->
            EventRow(event)
            HorizontalDivider()
        }
    }
}

@Composable
private fun EventRow(event: ChatEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(eventLabel(event))
        Text(
            text = event.date ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun eventLabel(event: ChatEvent): String = when (event.type) {
    ChatEventType.JOINED -> "→ ${event.member ?: "?"} joined"
    ChatEventType.LEFT -> "← ${event.member ?: "?"} left"
    ChatEventType.SAFETY_NUMBER_CHANGED -> "🔒 Safety number changed for ${event.member ?: "?"}"
    ChatEventType.GROUP_UPDATES -> "⚙ ${event.count ?: 1} group update(s)"
}
