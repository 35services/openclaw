package signalfaqbot.chatimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FSI = '⁨'
private const val PDI = '⁩'
private fun name(n: String) = "$FSI$n$PDI"

class ChatExportParserTest {
    @Test
    fun `parses a simple message with a date header`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                "${name("Vera")}",
                "Okay, das hatte ich fast befürchtet",
                "5:29 PM",
            ),
            referenceYear = 2026, // Apr 4 also falls on a Saturday in 2020 — pin the year to resolve unambiguously
        )

        assertEquals(1, chat.messages.size)
        val message = chat.messages.single()
        assertEquals("Vera", message.sender)
        assertEquals("Okay, das hatte ich fast befürchtet", message.text)
        assertEquals("5:29 PM", message.time)
        assertEquals("2026-04-04", message.date)
        assertEquals(false, message.edited)
        assertTrue(chat.anomalies.isEmpty(), chat.anomalies.toString())
    }

    @Test
    fun `keeps a multi-line message body together, including an internal blank line`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                "${name("Jannis 35services")} 🪵${name("Holzbereich")}",
                "hallo Vera!",
                "",
                "komm gerne vorbei.",
                "6:36 PM",
            ),
        )

        val message = chat.messages.single()
        assertEquals("Jannis 35services", message.sender)
        assertEquals("Holzbereich", message.senderTag)
        assertEquals("hallo Vera!\n\nkomm gerne vorbei.", message.text)
    }

    @Test
    fun `parses an Edited timestamp`() {
        val chat = ChatExportParser.parse(
            listOf("Sat, Apr 4", "", name("Arne"), "Ah super", "Edited5:26 PM"),
        )

        val message = chat.messages.single()
        assertEquals(true, message.edited)
        assertEquals("5:26 PM", message.time)
    }

    @Test
    fun `parses join and leave events`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Tue, Apr 7",
                "",
                "${name("Roland")} joined the group via the group link.",
                "",
                "Thu, Apr 9",
                "",
                "${name("Anna E")} left the group.",
            ),
            referenceYear = 2026,
        )

        assertEquals(
            listOf(
                ChatEvent("2026-04-07", ChatEventType.JOINED, member = "Roland"),
                ChatEvent("2026-04-09", ChatEventType.LEFT, member = "Anna E"),
            ),
            chat.events,
        )
    }

    @Test
    fun `parses a collapsed group-updates count`() {
        val chat = ChatExportParser.parse(listOf("Wed, Apr 8", "", "3 group updates"), referenceYear = 2026)

        assertEquals(ChatEvent("2026-04-08", ChatEventType.GROUP_UPDATES, count = 3), chat.events.single())
    }

    @Test
    fun `parses a safety number change and consumes its detail line`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                "Safety Number with ${name("Benji")} has changed",
                "View Safety Number",
                "",
                name("Js"),
                "hi",
                "8:29 PM",
            ),
            referenceYear = 2026,
        )

        assertEquals(ChatEvent("2026-04-04", ChatEventType.SAFETY_NUMBER_CHANGED, member = "Benji"), chat.events.single())
        assertEquals(1, chat.messages.size, "the 'View Safety Number' line must not become its own message")
    }

    @Test
    fun `discards an avatar-initials line but does not mistake real short message text for one`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                "V",
                "",
                name("Vera"),
                "Okay",
                "5:29 PM",
                "",
                name("Veitus"),
                "OK",
                "5:30 PM",
            ),
        )

        assertEquals(2, chat.messages.size)
        assertEquals("Vera", chat.messages[0].sender)
        assertEquals("Okay", chat.messages[0].text)
        assertEquals("Veitus", chat.messages[1].sender)
        assertEquals("OK", chat.messages[1].text, "a real message that happens to look like an initials line must survive")
    }

    @Test
    fun `attaches a reaction to the immediately preceding message`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                name("Georg"),
                "Hammer, bis dann!",
                "1:23 PM",
                "",
                "🤩",
                "",
                name("Anton"),
                "Wie groß?",
                "6:33 PM",
            ),
        )

        assertEquals(listOf("🤩"), chat.messages[0].reactions)
        assertTrue(chat.messages[1].reactions.isEmpty())
    }

    @Test
    fun `extracts a quote-reply preview separately from the actual reply text`() {
        val chat = ChatExportParser.parse(
            listOf(
                "Sat, Apr 4",
                "",
                "${name("Tobias Pela")} ${name("Metallbereich")}",
                name("Julius"),
                "Hi, ist es möglich in der werkstatt Edelstahl zu schweißen? Danke",
                "",
                "Na klar.... aber sowas von",
                "8:29 PM",
            ),
        )

        val message = chat.messages.single()
        assertEquals("Tobias Pela", message.sender)
        assertEquals("Julius", message.replyToSender)
        assertEquals("Hi, ist es möglich in der werkstatt Edelstahl zu schweißen? Danke", message.replyToText)
        assertEquals("Na klar.... aber sowas von", message.text)
    }

    @Test
    fun `flags a message with no sender header as an anomaly but keeps its text`() {
        val chat = ChatExportParser.parse(listOf("Also am Oster Montag zum Stammtisch würde z.b gehen?", "5:28 PM"))

        val message = chat.messages.single()
        assertNull(message.sender)
        assertEquals("Also am Oster Montag zum Stammtisch würde z.b gehen?", message.text)
        assertTrue(chat.anomalies.any { it.contains("no recognizable sender header") })
    }

    @Test
    fun `flags a message with no trailing timestamp as an anomaly instead of swallowing later lines`() {
        val chat = ChatExportParser.parse(listOf("Sat, Apr 4", "", name("Vesti Gium"), "Hallo :)"))

        val message = chat.messages.single()
        assertNull(message.time)
        assertEquals("Hallo :)", message.text)
        assertTrue(chat.anomalies.any { it.contains("no timestamp line") })
    }

    @Test
    fun `infers the year from the stated weekday, preferring the year closest to the reference year`() {
        // Apr 4 falls on a Saturday in both 2020 and 2026 within the default search window —
        // referenceYear must be the tie-breaker, not "smallest matching year".
        val closeTo2026 = ChatExportParser.parse(listOf("Sat, Apr 4", "", name("Vera"), "hi", "5:29 PM"), referenceYear = 2026)
        assertEquals("2026-04-04", closeTo2026.messages.single().date)

        val closeTo2020 = ChatExportParser.parse(listOf("Sat, Apr 4", "", name("Vera"), "hi", "5:29 PM"), referenceYear = 2021)
        assertEquals("2020-04-04", closeTo2020.messages.single().date)
    }

    @Test
    fun `rolls the year over across a December to January boundary`() {
        // Dec 31 falls on a Wednesday in more than one year within a wide search window,
        // and any such year trivially makes the following Jan 1 a Thursday too (consecutive
        // days) — pin the candidate range to 2025 so the test isn't at the mercy of which
        // matching year happens to sort first.
        val chat = ChatExportParser.parse(
            listOf(
                "Wed, Dec 31",
                "",
                name("Vera"),
                "Prost Neujahr!",
                "11:59 PM",
                "",
                "Thu, Jan 1",
                "",
                name("Vera"),
                "Guten Rutsch war's",
                "12:01 AM",
            ),
            yearCandidates = 2025..2025,
        )

        assertEquals("2025-12-31", chat.messages[0].date)
        assertEquals("2026-01-01", chat.messages[1].date)
    }

    @Test
    fun `reports an anomaly and leaves dates null when no candidate year fits the stated weekday`() {
        // Apr 4, 2026 is a Saturday, not a Monday — narrowing candidates to just 2026 guarantees no match.
        val chat = ChatExportParser.parse(
            listOf("Mon, Apr 4", "", name("Vera"), "hi", "5:29 PM"),
            yearCandidates = 2026..2026,
        )

        assertNull(chat.messages.single().date)
        assertTrue(chat.anomalies.any { it.contains("could not infer a start year") })
    }
}
