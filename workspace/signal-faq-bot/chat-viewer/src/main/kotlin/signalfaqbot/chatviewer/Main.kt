package signalfaqbot.chatviewer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

private val json = Json { ignoreUnknownKeys = true }

/**
 * Usage: `./gradlew :chat-viewer:run --args="path/to/chat.txt.json"`.
 * Defaults to `../reference/chat.txt.json`, i.e. the sibling bot module's
 * own reference export, since Gradle's `run` task working directory is this
 * module's own directory (`chat-viewer/`).
 *
 * If `<chat.json>.analysis.json` exists alongside the chat file (the default
 * output path `analyze-chat` writes to), it's picked up automatically and
 * polled for changes — so this can be pointed at a chat export while
 * `analyze-chat` is still running against it in the background, and the UI
 * fills in as results land.
 */
fun main(args: Array<String>) {
    val path = args.getOrElse(0) { "../reference/chat.txt.json" }
    val file = File(path)
    if (!file.exists()) {
        System.err.println("No such file: ${file.absolutePath}")
        System.err.println("Usage: ./gradlew :chat-viewer:run --args=\"path/to/chat.txt.json\"")
        exitProcess(1)
    }

    val chat = json.decodeFromString(ParsedChat.serializer(), file.readText())
    val analysisFile = File("$path.analysis.json")

    application {
        Window(onCloseRequest = ::exitApplication, title = "Signal Chat Viewer — ${file.name}") {
            ChatViewerApp(chat, analysisFile)
        }
    }
}
