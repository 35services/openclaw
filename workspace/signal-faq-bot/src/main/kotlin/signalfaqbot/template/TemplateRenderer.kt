package signalfaqbot.template

/**
 * Deliberately dumb `{{key}}` substitution — no conditionals, no loops. The
 * point is that the *.txt template files stay editable by a non-programmer
 * to tune prompt wording, not that this engine is powerful.
 */
object TemplateRenderer {
    fun render(template: String, values: Map<String, String>): String {
        var result = template
        for ((key, value) in values) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }
}
