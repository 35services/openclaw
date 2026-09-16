package signalfaqbot.faq

import java.io.File

fun interface FaqProvider {
    fun load(): String
}

class FileFaqProvider(private val path: String) : FaqProvider {
    override fun load(): String = File(path).readText()
}
