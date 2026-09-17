package signalfaqbot.chatviewer

import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders [ChatViewerContent] against invented, non-real sample data — not
 * a real group export — straight to a PNG via Compose's off-screen Skia
 * rasterizer ([renderComposeScene]). Deliberately doesn't go through an
 * actual OS window or [Main]'s file-polling: no display, no screen-capture
 * permission, and no timing dependent on a coroutine tick, needed.
 *
 * Run via `./gradlew :chat-viewer:screenshot`.
 */
fun main() {
    val image = renderComposeScene(width = 1500, height = 2000, density = Density(2f)) {
        ChatViewerContent(mockChat, mockAnalysis)
    }
    val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
    val out = File("screenshot.png")
    out.writeBytes(bytes)
    println("Wrote ${out.absolutePath} (${bytes.size} bytes)")
}

private val mockChat = ParsedChat(
    messages = listOf(
        ChatMessage(
            date = "2026-05-03", time = "10:12 AM", sender = "Mira",
            text = "Hallo zusammen! Ich bin heute der Gruppe beigetreten, freue mich auf die Werkstatt 🙂",
        ),
        ChatMessage(
            date = "2026-05-03", time = "10:15 AM", sender = "Jonas", senderTag = "Holzbereich",
            text = "Willkommen, Mira! Schau gern donnerstags beim Fahrradtreff vorbei.",
            reactions = listOf("👍", "❤️"),
        ),
        ChatMessage(
            date = "2026-05-03", time = "10:20 AM", sender = "Mira",
            text = "Wann habt ihr denn geöffnet und muss ich mich vorher anmelden?",
        ),
        ChatMessage(
            date = "2026-05-03", time = "10:41 AM", sender = "Sophie",
            text = "Wie bekomme ich eigentlich eine feste Bremsscheibe vom Laufrad ab? Die sitzt ziemlich fest.",
        ),
        ChatMessage(
            date = "2026-05-03", time = "11:02 AM", sender = "Tobias", senderTag = "Metallbereich",
            text = "haha ja das kenn ich",
            replyToSender = "Sophie", replyToText = "Wie bekomme ich eigentlich eine feste Bremsscheibe...",
        ),
        ChatMessage(
            date = "2026-05-03", time = "1:15 PM", sender = "Nadine",
            text = "Kann man bei euch auch Werkzeug ausleihen, ohne selbst vor Ort etwas zu bauen?",
            edited = true,
        ),
    ),
    events = listOf(
        ChatEvent(date = "2026-05-03", type = ChatEventType.JOINED, member = "Mira"),
        ChatEvent(date = "2026-05-02", type = ChatEventType.GROUP_UPDATES, count = 2),
        ChatEvent(date = "2026-05-01", type = ChatEventType.LEFT, member = "Paul"),
    ),
    anomalies = listOf("Zeile 214: Absender konnte nicht bestimmt werden (vermutlich Linkvorschau)"),
)

private val mockAnalysis = listOf(
    MessageAnalysis(
        messageIndex = 2,
        sender = "Mira",
        text = mockChat.messages[2].text,
        checks = listOf(GateCheck("classify_0_is_question", true), GateCheck("classify_practical", true)),
        status = AnalysisStatus.ANSWERED,
        answerText = "Wir haben donnerstags 17–20 Uhr und montags 19–21 Uhr geöffnet. Eine Anmeldung ist nicht " +
            "nötig, schau einfach vorbei! Alle Termine findest du auch im Online-Kalender.",
        updatedAt = 0L,
    ),
    MessageAnalysis(
        messageIndex = 3,
        sender = "Sophie",
        text = mockChat.messages[3].text,
        checks = listOf(GateCheck("classify_0_is_question", true), GateCheck("classify_practical", false)),
        status = AnalysisStatus.REDIRECTED,
        stoppedByGate = "classify_practical",
        answerText = "Das ist eine praktische Frage, die wir dir am besten direkt vor Ort zeigen können. " +
            "Komm gerne zu einer Öffnungszeit vorbei!",
        updatedAt = 0L,
    ),
    MessageAnalysis(
        messageIndex = 4,
        sender = "Tobias",
        text = mockChat.messages[4].text,
        checks = listOf(GateCheck("classify_0_is_question", false)),
        status = AnalysisStatus.SKIPPED,
        stoppedByGate = "classify_0_is_question",
        updatedAt = 0L,
    ),
    MessageAnalysis(
        messageIndex = 5,
        sender = "Nadine",
        text = mockChat.messages[5].text,
        checks = listOf(GateCheck("classify_0_is_question", true), GateCheck("classify_practical", true)),
        status = AnalysisStatus.FAILED,
        error = "Request timeout has expired [url=http://localhost:11434/api/generate, request_timeout=300000 ms]",
        updatedAt = 0L,
    ),
    // messageIndex 0 and 1 intentionally have no analysis entry, to show the "pending…" state.
).associateBy { it.messageIndex }
