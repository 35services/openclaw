package signalfaqbot.chatanalysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import signalfaqbot.chatimport.ChatEvent
import signalfaqbot.chatimport.ChatEventType
import signalfaqbot.chatimport.ChatMessage

class MessageContextTest {
    private fun message(date: String?, time: String?, sender: String?) =
        ChatMessage(date = date, time = time, sender = sender, text = "irrelevant")

    @Test
    fun `minutes since joined is null without a matching join event`() {
        val messages = listOf(message("2026-04-10", "10:00 AM", "Alice"))
        val context = MessageContext(messages, emptyList())
        assertNull(context.minutesSinceJoined(0))
    }

    @Test
    fun `minutes since joined counts from the most recent join before the message`() {
        val messages = listOf(message("2026-04-10", "2:00 AM", "Alice"))
        val events = listOf(ChatEvent(date = "2026-04-10", type = ChatEventType.JOINED, member = "Alice"))
        val context = MessageContext(messages, events)
        // Join is recorded at midnight of the join date; message is 2 hours later.
        assertEquals(120L, context.minutesSinceJoined(0))
    }

    @Test
    fun `minutes since joined ignores a join event after the message`() {
        val messages = listOf(message("2026-04-10", "10:00 AM", "Alice"))
        val events = listOf(ChatEvent(date = "2026-04-12", type = ChatEventType.JOINED, member = "Alice"))
        val context = MessageContext(messages, events)
        assertNull(context.minutesSinceJoined(0))
    }

    @Test
    fun `minutes since joined picks the latest of several joins at or before the message`() {
        val messages = listOf(message("2026-04-12", "10:00 AM", "Alice"))
        val events = listOf(
            ChatEvent(date = "2026-04-05", type = ChatEventType.JOINED, member = "Alice"),
            ChatEvent(date = "2026-04-11", type = ChatEventType.JOINED, member = "Alice"),
        )
        val context = MessageContext(messages, events)
        // From 2026-04-11 00:00 to 2026-04-12 10:00 = 1 day 10 hours = 2050 minutes.
        assertEquals((24 * 60 + 10 * 60).toLong(), context.minutesSinceJoined(0))
    }

    @Test
    fun `messages last 24h counts only the same sender within the window`() {
        val messages = listOf(
            message("2026-04-10", "9:00 AM", "Alice"),
            message("2026-04-10", "10:00 AM", "Bob"),
            message("2026-04-10", "11:00 AM", "Alice"),
            message("2026-04-11", "8:00 AM", "Alice"),
        )
        val context = MessageContext(messages, emptyList())
        // Alice's message at index 3 (2026-04-11 08:00): window is [2026-04-10 08:00, 2026-04-11 08:00).
        // Alice's earlier messages at 09:00 and 11:00 on 04-10 both fall inside it; Bob's doesn't count.
        assertEquals(2, context.messagesLast24h(3))
    }

    @Test
    fun `messages last 24h excludes messages outside the window`() {
        val messages = listOf(
            message("2026-04-08", "9:00 AM", "Alice"),
            message("2026-04-11", "8:00 AM", "Alice"),
        )
        val context = MessageContext(messages, emptyList())
        assertEquals(0, context.messagesLast24h(1))
    }

    @Test
    fun `unparseable date or time yields no signal`() {
        val messages = listOf(message(null, null, "Alice"))
        val context = MessageContext(messages, emptyList())
        assertNull(context.minutesSinceJoined(0))
        assertEquals(0, context.messagesLast24h(0))
    }
}
