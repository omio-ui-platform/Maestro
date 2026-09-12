package maestro.ai

import maestro.ai.cloud.Defect
import maestro.ai.openai.OpenAI
import maestro.ai.cloud.LanguageViolation
import maestro.ai.cloud.ExtractPointValidationResponse
import maestro.ai.cloud.ExtractPointWithReasoningResponse
import maestro.ai.cloud.OpenAIClient

object Prediction {
    private const val MAESTRO_CLI_AI_MODEL = "MAESTRO_CLI_AI_MODEL"
    private val openApi = OpenAIClient()

    suspend fun findDefects(
        aiClient: AI?,
        screen: ByteArray,
    ): List<Defect> {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen)
            return response.defects
        }
        return listOf()
    }

    suspend fun performAssertion(
        aiClient: AI?,
        screen: ByteArray,
        assertion: String,
    ): Defect? {
        if(aiClient !== null){
            val response = openApi.findDefects(aiClient, screen, assertion)
            return response.defects.firstOrNull()
        }
        return null
    }

    suspend fun assertLanguage(
        aiClient: AI?,
        language: String,
        languageTag: String,
        screen: ByteArray,
        ignore: List<String>,
        onScreenText: String?,
    ): List<LanguageViolation> {
        if (aiClient == null) return listOf()
        // Structured output is an OpenAI feature; the Claude client drops `jsonSchema` silently
        // (see AI.chatCompletion). Failing here beats parsing prose leniently, because a lenient
        // parse of an unstructured reply yields "no violations" -- a green assertion that checked
        // nothing, on a command whose whole job is to fail.
        if (aiClient !is OpenAI) {
            throw IllegalStateException(
                "assertLanguageWithAI needs an OpenAI model, but $MAESTRO_CLI_AI_MODEL selected " +
                    "${aiClient::class.simpleName}. Structured JSON output is not implemented for it."
            )
        }
        return openApi.assertLanguageWithAi(aiClient, language, languageTag, screen, ignore, onScreenText).violations
    }

    suspend fun extractText(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
    ): String {
        if(aiClient !== null){
            val response = openApi.extractTextWithAi(aiClient, query, screen)
            return response.text
        }
        return ""
    }

    @Deprecated("Use extractPointWithReasoning for improved accuracy", replaceWith = ReplaceWith("extractPointWithReasoning(aiClient, query, screen)"))
    suspend fun extractPoint(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
    ): String {
        if(aiClient !== null){
            val response = openApi.extractPointWithAi(aiClient, query, screen)
            return response.text
        }
        return ""
    }

    suspend fun extractPointWithReasoning(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractPointWithAi(aiClient, query, screen, viewHierarchy)
        }
        return null
    }

    suspend fun extractPointRefined(
        aiClient: AI?,
        query: String,
        croppedScreen: ByteArray,
        contextDescription: String,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractPointRefined(aiClient, query, croppedScreen, contextDescription)
        }
        return null
    }

    suspend fun extractComponentPoint(
        aiClient: AI?,
        componentImage: ByteArray,
        screen: ByteArray,
        viewHierarchy: String? = null,
    ): ExtractPointWithReasoningResponse? {
        if(aiClient !== null){
            return openApi.extractComponentPoint(aiClient, componentImage, screen, viewHierarchy)
        }
        return null
    }

    suspend fun validatePoint(
        aiClient: AI?,
        query: String,
        screen: ByteArray,
        pointXPercent: Int,
        pointYPercent: Int,
    ): ExtractPointValidationResponse? {
        if(aiClient !== null){
            return openApi.validatePoint(aiClient, query, screen, pointXPercent, pointYPercent)
        }
        return null
    }
}
