package signalfaqbot.core

import signalfaqbot.template.FileBackedMessageTemplateRenderer
import java.io.File

/**
 * Finds every `classify*.txt` file directly under [templatesDir], sorted
 * alphabetically by filename — that sort order IS the gate execution order.
 * A numeric prefix like `classify_0_is_question.txt` therefore runs before
 * `classify_practical.txt`, without any config needed to state the order.
 *
 * Each gate's name is its filename minus the `.txt` extension (e.g.
 * `"classify_practical"`), which doubles as both its
 * `./gradlew run --args='<name> "<message>"'` CLI subcommand and its
 * matching static-answer lookup: `classify_practical.txt` pairs with
 * `answer_practical.txt` if that file exists next to it.
 */
object GateDiscovery {
    fun discover(templatesDir: String): List<ClassificationGate> {
        val dir = File(templatesDir)
        val classifyFiles = dir.listFiles { file -> file.isFile && file.name.startsWith("classify") && file.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()

        return classifyFiles.map { file ->
            val name = file.name.removeSuffix(".txt")
            val answerFile = File(dir, "answer${name.removePrefix("classify")}.txt")
            ClassificationGate(
                name = name,
                promptRenderer = FileBackedMessageTemplateRenderer(file.path),
                answerRenderer = if (answerFile.exists()) FileBackedMessageTemplateRenderer(answerFile.path) else null,
            )
        }
    }
}
