package signalfaqbot.state

import signalfaqbot.model.IncomingMessage
import signalfaqbot.model.MessageStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonFileStateStoreTest {
    private fun tempStatePath(): String = Files.createTempFile("state", ".json").also { Files.delete(it) }.toString()

    @Test
    fun `survives a restart by reloading from disk`() {
        val path = tempStatePath()
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)

        JsonFileStateStore(path).apply {
            markReceived(message)
            markProcessing(message.id)
        }

        // Simulates a crash right after markProcessing and a process restart.
        val reopened = JsonFileStateStore(path)
        assertEquals(MessageStatus.PROCESSING, reopened.find("1")?.status)
    }

    @Test
    fun `markReceived does not overwrite an existing record`() {
        val path = tempStatePath()
        val message = IncomingMessage(id = "1", sender = "+491234", text = "Wann offen?", timestamp = 0)
        val store = JsonFileStateStore(path)

        store.markReceived(message)
        store.markAnswered(message.id, "Mittwochs 10-14 Uhr")
        store.markReceived(message)

        assertEquals(MessageStatus.ANSWERED, store.find("1")?.status)
    }
}
