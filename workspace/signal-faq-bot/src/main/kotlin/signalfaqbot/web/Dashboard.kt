package signalfaqbot.web

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import signalfaqbot.model.MessageRecord
import signalfaqbot.model.MessageStatus
import signalfaqbot.state.StateStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A read-only status page: what's currently queued/processing, and a history
 * of everything else (answered, redirected, failed, skipped). Renders
 * straight from [StateStore] on every request — no separate database, the
 * state file already is the source of truth.
 */
fun Application.dashboardModule(stateStore: StateStore) {
    routing {
        get("/") {
            val records = stateStore.all().sortedByDescending { it.updatedAt }
            val queue = records.filter { it.status == MessageStatus.RECEIVED || it.status == MessageStatus.PROCESSING }
            val history = records.filter { it.status != MessageStatus.RECEIVED && it.status != MessageStatus.PROCESSING }

            call.respondHtml {
                head {
                    title("Signal FAQ Bot")
                    meta(charset = "utf-8")
                    meta(name = "viewport", content = "width=device-width, initial-scale=1")
                    style {
                        unsafe {
                            raw(
                                """
                                body { font-family: sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; }
                                table { width: 100%; border-collapse: collapse; margin-bottom: 2rem; }
                                th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid #ddd; vertical-align: top; }
                                .status-RECEIVED, .status-PROCESSING { color: #a66a00; }
                                .status-ANSWERED { color: #1a7f37; }
                                .status-REDIRECTED { color: #0a6cb8; }
                                .status-FAILED { color: #c00; }
                                .status-SKIPPED { color: #888; }
                                """.trimIndent(),
                            )
                        }
                    }
                }
                body {
                    h1 { +"Signal FAQ Bot" }

                    h2 { +"Queue (${queue.size})" }
                    if (queue.isEmpty()) {
                        p { +"Nothing waiting." }
                    } else {
                        renderTable(queue)
                    }

                    h2 { +"History (${history.size})" }
                    if (history.isEmpty()) {
                        p { +"No messages processed yet." }
                    } else {
                        renderTable(history, showAnswer = true)
                    }
                }
            }
        }
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Berlin"))

private fun kotlinx.html.FlowContent.renderTable(records: List<MessageRecord>, showAnswer: Boolean = false) {
    table {
        tr {
            th { +"Updated" }
            th { +"Sender" }
            th { +"Message" }
            th { +"Status" }
            if (showAnswer) th { +"Answer / Detail" }
        }
        for (record in records) {
            tr {
                td { +timeFormatter.format(Instant.ofEpochMilli(record.updatedAt)) }
                td { +record.message.sender }
                td { +record.message.text }
                td(classes = "status-${record.status}") { +record.status.name }
                if (showAnswer) td { +(record.answerText ?: record.error ?: "") }
            }
        }
    }
}

fun startDashboard(stateStore: StateStore, port: Int) =
    embeddedServer(Netty, port = port) { dashboardModule(stateStore) }.start(wait = false)
