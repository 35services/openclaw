package signalfaqbot.chatviewer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val tabs = listOf("Messages", "Events & Anomalies")
private val analysisJson = Json { ignoreUnknownKeys = true }
private const val POLL_INTERVAL_MS = 2000L

@Composable
fun ChatViewerApp(chat: ParsedChat, analysisFile: File? = null) {
    var analysisByIndex by remember { mutableStateOf<Map<Int, MessageAnalysis>>(emptyMap()) }

    if (analysisFile != null) {
        LaunchedEffect(analysisFile) {
            var lastModified = -1L
            while (isActive) {
                val mtime = if (analysisFile.exists()) analysisFile.lastModified() else 0L
                if (mtime != lastModified) {
                    lastModified = mtime
                    runCatching {
                        val list = analysisJson.decodeFromString(
                            ListSerializer(MessageAnalysis.serializer()),
                            analysisFile.readText(),
                        )
                        analysisByIndex = list.associateBy { it.messageIndex }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    ChatViewerContent(chat, analysisByIndex)
}

/**
 * The actual UI, decoupled from [analysisFile] polling so it can be rendered
 * directly with in-memory data — e.g. [signalfaqbot.chatviewer.renderScreenshot]
 * feeds it mock data straight through, with no file or coroutine involved.
 */
@Composable
fun ChatViewerContent(chat: ParsedChat, analysisByIndex: Map<Int, MessageAnalysis> = emptyMap()) {
    MaterialTheme {
        Surface {
            Column {
                StatsHeader(chat, analysisByIndex)

                var selectedTab by remember { mutableStateOf(0) }
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }

                when (selectedTab) {
                    0 -> MessageList(chat.messages, analysisByIndex)
                    1 -> EventList(chat.events, chat.anomalies)
                }
            }
        }
    }
}

@Composable
private fun StatsHeader(chat: ParsedChat, analysisByIndex: Map<Int, MessageAnalysis>) {
    val people = chat.messages.mapNotNull { it.sender }.distinct().size
    Row(
        modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Stat("Messages", chat.messages.size.toString())
        Stat("People", people.toString())
        Stat("Events", chat.events.size.toString())
        Stat("Anomalies", chat.anomalies.size.toString())
        if (analysisByIndex.isNotEmpty()) {
            Stat("Analyzed", "${analysisByIndex.size}/${chat.messages.size}")
            AnalysisStatus.entries.forEach { status ->
                val count = analysisByIndex.values.count { it.status == status }
                if (count > 0) Stat(status.name, count.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
