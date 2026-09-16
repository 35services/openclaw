package signalfaqbot.llm

import signalfaqbot.LlmConfig
import signalfaqbot.LlmProvider

object LlmClientFactory {
    fun from(config: LlmConfig): LlmClient = when (config.provider) {
        LlmProvider.OLLAMA -> OllamaLlmClient(model = config.model, baseUrl = config.ollamaBaseUrl)
        LlmProvider.APFEL -> ApfelLlmClient(command = config.apfelCommand, model = config.model)
    }
}
