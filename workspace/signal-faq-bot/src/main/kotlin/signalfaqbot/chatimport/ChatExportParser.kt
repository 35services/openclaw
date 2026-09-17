package signalfaqbot.chatimport

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Parses a plain-text export of a Signal Desktop group chat (Signal Desktop's
 * "select all, copy" from the conversation view) into [ParsedChat].
 *
 * The export has no machine-readable structure — everything below is reverse
 * engineered from a real 1150-line export (see `reference/chat.txt`, which is
 * gitignored since it's a real chat log). Read [ParsedChat.anomalies] before
 * trusting the output for anything downstream; this format has real ambiguity
 * (see CLAUDE.md's "Chat export parser" section) that no amount of regex
 * fully resolves — e.g. a link-preview card's own lines (title/description/
 * domain/date) are indistinguishable from typed message text and are kept
 * as-is rather than guessed apart.
 *
 * Recognized line shapes, per exported block:
 * - Date header: `"Sat, Apr 4"` — no year; see [inferStartYear].
 * - Join/leave: `"⁨Name⁩ joined the group via the group link."` / `"⁨Name⁩ left the group."`
 * - Collapsed events: `"2 group updates"` — count only, no per-member detail.
 * - Safety number change: `"Safety Number with ⁨Name⁩ has changed"` + `"View Safety Number"`.
 * - Avatar-initials artifact: a short line of 1-3 letters (e.g. `"V"`, `"SA"`, `"c"`)
 *   that precedes a real sender header for a contact with no profile photo — pure UI
 *   noise, discarded.
 * - Reaction: a short line with no letters at all (e.g. `"❤️"`, `"👎2"`) attached to
 *   the immediately preceding message.
 * - Message: `"⁨Sender⁩ [tag]"` header, one or more body lines (which may include a
 *   quote-reply preview — another `⁨Name⁩` header followed by quoted text, a blank
 *   line, then the real reply), ending in a time line (`"6:36 PM"`, optionally
 *   `"Edited6:36 PM"`). A message with **no** header at all, directly following
 *   another message (no date header/event in between), is Signal's own grouping of
 *   consecutive messages from the same sender — its sender is inherited from the
 *   previous message rather than flagged as an anomaly.
 *
 * A few structural lines (observed on collapsed `"N group updates"` lines) are
 * wrapped in invisible Private Use Area characters (U+E000–U+F8FF) that Signal
 * Desktop's copy/paste leaves behind for what's a clickable UI affordance in the
 * app; these are stripped from every line before any pattern matching happens.
 */
object ChatExportParser {
    private const val FSI = '⁨' // FIRST STRONG ISOLATE — wraps every name Signal Desktop exports
    private const val PDI = '⁩' // POP DIRECTIONAL ISOLATE — closes it

    private val dateHeaderRegex = Regex("""^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), ([A-Za-z]+) (\d{1,2})$""")
    private val headerLineRegex = Regex("""^$FSI(.+?)$PDI(.*)$""")
    private val tagRegex = Regex("""$FSI(.+?)$PDI""")
    private val joinRegex = Regex("""^$FSI(.+?)$PDI joined the group via the group link\.$""")
    private val leftRegex = Regex("""^$FSI(.+?)$PDI left the group\.$""")
    private val groupUpdatesRegex = Regex("""^(\d+) group updates?$""")
    private val safetyNumberRegex = Regex("""^Safety Number with $FSI(.+?)$PDI has changed$""")
    private val timeLineRegex = Regex("""^(Edited)?(\d{1,2}:\d{2} (?:AM|PM))$""")
    private val avatarInitialsRegex = Regex("""^\p{L}{1,3}$""")
    private val privateUseAreaChars = Regex("""[-]""")

    private val monthAbbreviations = mapOf(
        "Jan" to Month.JANUARY, "Feb" to Month.FEBRUARY, "Mar" to Month.MARCH, "Apr" to Month.APRIL,
        "May" to Month.MAY, "Jun" to Month.JUNE, "Jul" to Month.JULY, "Aug" to Month.AUGUST,
        "Sep" to Month.SEPTEMBER, "Oct" to Month.OCTOBER, "Nov" to Month.NOVEMBER, "Dec" to Month.DECEMBER,
    )
    private val dayAbbreviations = mapOf(
        "Mon" to DayOfWeek.MONDAY, "Tue" to DayOfWeek.TUESDAY, "Wed" to DayOfWeek.WEDNESDAY,
        "Thu" to DayOfWeek.THURSDAY, "Fri" to DayOfWeek.FRIDAY, "Sat" to DayOfWeek.SATURDAY, "Sun" to DayOfWeek.SUNDAY,
    )

    /**
     * [yearCandidates] is a plausibility window for [inferStartYear] — widen it if parsing
     * a much older/newer export ever reports "could not infer year" as an anomaly.
     * [referenceYear] resolves the (real, and not at all rare) ambiguity where a header's
     * weekday matches more than one candidate year — see [inferStartYear].
     */
    fun parse(
        rawLines: List<String>,
        yearCandidates: IntRange = 2015..2035,
        referenceYear: Int = LocalDate.now().year,
    ): ParsedChat {
        val lines = rawLines.map { it.trimEnd('\r').replace(privateUseAreaChars, "") }
        val startYear = inferStartYear(extractDateHeaders(lines), yearCandidates, referenceYear)

        val messages = mutableListOf<ChatMessage>()
        val events = mutableListOf<ChatEvent>()
        val anomalies = mutableListOf<String>()

        var currentDate: LocalDate? = null
        var rollingYear = startYear
        var lastMonth: Month? = null
        if (startYear == null && extractDateHeaders(lines).isNotEmpty()) {
            anomalies += "could not infer a start year consistent with every date header's weekday; all dates will be null"
        }

        // True right after a date header/event, or at the very start of the transcript —
        // a headerless message block is only "the same sender as before" (see class doc)
        // when it directly follows another message, not one of these.
        var precededByNonMessageMarker = true

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            val dateMatch = dateHeaderRegex.matchEntire(line)
            if (dateMatch != null) {
                val (dowAbbr, monthAbbr, dayStr) = dateMatch.destructured
                val month = monthAbbreviations[monthAbbr]
                val day = dayStr.toIntOrNull()
                if (month != null && day != null && rollingYear != null) {
                    if (lastMonth != null && month < lastMonth) rollingYear = rollingYear + 1
                    val date = runCatching { LocalDate.of(rollingYear, month, day) }.getOrNull()
                    if (date != null) {
                        val expectedDow = dayAbbreviations[dowAbbr]
                        if (expectedDow != null && date.dayOfWeek != expectedDow) {
                            anomalies += "line ${i + 1}: date header \"$line\" doesn't match weekday for inferred year $rollingYear (got ${date.dayOfWeek})"
                        }
                        currentDate = date
                        lastMonth = month
                    } else {
                        anomalies += "line ${i + 1}: could not resolve date header \"$line\""
                    }
                } else if (month == null || day == null) {
                    anomalies += "line ${i + 1}: could not parse date header \"$line\""
                }
                precededByNonMessageMarker = true
                i++
                continue
            }

            val joinMatch = joinRegex.matchEntire(line)
            if (joinMatch != null) {
                events += ChatEvent(currentDate?.toString(), ChatEventType.JOINED, member = joinMatch.groupValues[1])
                precededByNonMessageMarker = true
                i++
                continue
            }

            val leftMatch = leftRegex.matchEntire(line)
            if (leftMatch != null) {
                events += ChatEvent(currentDate?.toString(), ChatEventType.LEFT, member = leftMatch.groupValues[1])
                precededByNonMessageMarker = true
                i++
                continue
            }

            val groupUpdatesMatch = groupUpdatesRegex.matchEntire(line)
            if (groupUpdatesMatch != null) {
                events += ChatEvent(currentDate?.toString(), ChatEventType.GROUP_UPDATES, count = groupUpdatesMatch.groupValues[1].toInt())
                precededByNonMessageMarker = true
                i++
                continue
            }

            val safetyMatch = safetyNumberRegex.matchEntire(line)
            if (safetyMatch != null) {
                events += ChatEvent(currentDate?.toString(), ChatEventType.SAFETY_NUMBER_CHANGED, member = safetyMatch.groupValues[1])
                precededByNonMessageMarker = true
                i++
                val next = nextNonBlank(lines, i)
                if (next < lines.size && lines[next] == "View Safety Number") i = next + 1
                continue
            }

            if (avatarInitialsRegex.matches(line)) {
                val next = nextNonBlank(lines, i + 1)
                if (next < lines.size && headerLineRegex.matches(lines[next])) {
                    i++ // pure avatar-fallback UI artifact, no information beyond what the header repeats
                    continue
                }
            }

            if (messages.isNotEmpty() && isReactionLine(line)) {
                val last = messages.removeAt(messages.size - 1)
                messages += last.copy(reactions = last.reactions + line)
                i++
                continue
            }

            val block = parseMessageBlock(lines, i)
            var sender = block.sender
            var senderTag = block.senderTag
            if (sender == null && !precededByNonMessageMarker && messages.isNotEmpty()) {
                // Signal groups consecutive same-sender messages by only showing the header once.
                sender = messages.last().sender
                senderTag = messages.last().senderTag
            } else if (sender == null) {
                anomalies += "line ${i + 1}: message block has no recognizable sender header, starting: \"$line\""
            }
            if (block.time == null) {
                anomalies += "line ${i + 1}: message block for ${sender ?: "unknown sender"} has no timestamp line (possibly truncated)"
            }

            messages += ChatMessage(
                date = currentDate?.toString(),
                time = block.time,
                sender = sender,
                senderTag = senderTag,
                text = block.text,
                edited = block.edited,
                replyToSender = block.replyToSender,
                replyToText = block.replyToText,
            )
            precededByNonMessageMarker = false
            i = block.nextIndex
        }

        return ParsedChat(messages, events, anomalies)
    }

    private class MessageBlock(
        val sender: String?,
        val senderTag: String?,
        val text: String,
        val time: String?,
        val edited: Boolean,
        val replyToSender: String?,
        val replyToText: String?,
        val nextIndex: Int,
    )

    private fun parseMessageBlock(lines: List<String>, startIndex: Int): MessageBlock {
        val headerMatch = headerLineRegex.matchEntire(lines[startIndex])
        val sender = headerMatch?.groupValues?.get(1)
        val senderTag = headerMatch?.let { tagRegex.find(it.groupValues[2].trim()) }?.groupValues?.get(1)
        var j = if (headerMatch != null) startIndex + 1 else startIndex

        val bodyLines = mutableListOf<String>()
        var time: String? = null
        var edited = false
        while (j < lines.size) {
            val candidate = lines[j]
            val timeMatch = timeLineRegex.matchEntire(candidate)
            if (timeMatch != null) {
                edited = timeMatch.groupValues[1] == "Edited"
                time = timeMatch.groupValues[2]
                j++
                break
            }
            // A structural marker before any time line means this block never had one
            // (e.g. truncated at end-of-file) — stop rather than swallow the next block.
            if (isStructuralMarker(candidate)) break
            bodyLines += candidate
            j++
        }

        while (bodyLines.isNotEmpty() && bodyLines.first().isBlank()) bodyLines.removeAt(0)
        while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) bodyLines.removeAt(bodyLines.size - 1)

        var replyToSender: String? = null
        var replyToText: String? = null
        val quoteHeader = bodyLines.firstOrNull()?.let { headerLineRegex.matchEntire(it) }
        if (quoteHeader != null) {
            val blankIndex = bodyLines.indexOfFirst { it.isBlank() }
            if (blankIndex > 1) {
                replyToSender = quoteHeader.groupValues[1]
                replyToText = bodyLines.subList(1, blankIndex).joinToString("\n")
                val remaining = bodyLines.subList(blankIndex + 1, bodyLines.size).toList()
                bodyLines.clear()
                bodyLines.addAll(remaining)
            }
        }

        return MessageBlock(
            sender = sender,
            senderTag = senderTag,
            text = bodyLines.joinToString("\n"),
            time = time,
            edited = edited,
            replyToSender = replyToSender,
            replyToText = replyToText,
            nextIndex = j,
        )
    }

    private fun isStructuralMarker(line: String): Boolean =
        dateHeaderRegex.matches(line) || joinRegex.matches(line) || leftRegex.matches(line) ||
            groupUpdatesRegex.matches(line) || safetyNumberRegex.matches(line)

    private fun isReactionLine(line: String): Boolean = line.length <= 20 && line.none { it.isLetter() }

    private fun nextNonBlank(lines: List<String>, from: Int): Int {
        var j = from
        while (j < lines.size && lines[j].isBlank()) j++
        return j
    }

    private fun extractDateHeaders(lines: List<String>): List<Triple<DayOfWeek, Month, Int>> =
        lines.mapNotNull { line ->
            dateHeaderRegex.matchEntire(line)?.let { m ->
                val (dow, month, day) = m.destructured
                val d = dayAbbreviations[dow]
                val mo = monthAbbreviations[month]
                val da = day.toIntOrNull()
                if (d != null && mo != null && da != null) Triple(d, mo, da) else null
            }
        }

    /**
     * Date headers carry no year. Since every header also states its weekday, a
     * candidate year is *consistent* if every header's stated weekday actually matches
     * that (month, day) in it — checked while replaying the same "year rolls over when
     * the month decreases" logic [parse] itself uses, so a chat spanning a New Year's
     * Eve resolves correctly too.
     *
     * More than one year in [candidates] is often consistent — March onward repeats its
     * weekday alignment every 6 or 11 years (leap days only affect Jan/Feb), so e.g. Apr 4
     * lands on a Saturday in both 2020 and 2026. [referenceYear] breaks that tie by
     * preferring whichever consistent year is closest to it — a chat export is
     * overwhelmingly more likely to be from around "now" than from 6 years prior.
     */
    private fun inferStartYear(headers: List<Triple<DayOfWeek, Month, Int>>, candidates: IntRange, referenceYear: Int): Int? {
        if (headers.isEmpty()) return null
        return candidates
            .filter { candidateYear -> isConsistentStartYear(candidateYear, headers) }
            .minByOrNull { candidateYear -> Math.abs(candidateYear - referenceYear) }
    }

    private fun isConsistentStartYear(candidateYear: Int, headers: List<Triple<DayOfWeek, Month, Int>>): Boolean {
        var year = candidateYear
        var lastMonth: Month? = null
        for ((dow, month, day) in headers) {
            if (lastMonth != null && month < lastMonth) year++
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
            if (date == null || date.dayOfWeek != dow) return false
            lastMonth = month
        }
        return true
    }
}
