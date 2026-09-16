package signalfaqbot.template

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateRendererTest {
    @Test
    fun `substitutes every occurrence of a placeholder`() {
        val result = TemplateRenderer.render(
            "Hallo {{name}}, {{name}} hat geschrieben: {{message}}",
            mapOf("name" to "Alex", "message" to "Wann offen?"),
        )
        assertEquals("Hallo Alex, Alex hat geschrieben: Wann offen?", result)
    }

    @Test
    fun `leaves unknown placeholders untouched`() {
        val result = TemplateRenderer.render("{{known}} and {{unknown}}", mapOf("known" to "x"))
        assertEquals("x and {{unknown}}", result)
    }
}
