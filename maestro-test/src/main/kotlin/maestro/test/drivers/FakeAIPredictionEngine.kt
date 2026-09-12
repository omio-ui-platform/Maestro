package maestro.test.drivers

import maestro.ai.AI
import maestro.ai.AIPredictionEngine
import maestro.ai.cloud.Defect
import maestro.ai.cloud.ExtractPointValidationResponse
import maestro.ai.cloud.ExtractPointWithReasoningResponse
import maestro.ai.cloud.LanguageViolation

/**
 * A scripted [AIPredictionEngine] for tests, so an AI command's own logic -- validation, filtering,
 * the failure message, the artifacts it dispatches -- can be tested without a model.
 *
 * Only the methods a test needs are implemented; the rest fail loudly rather than returning an empty
 * result that would quietly pass an assertion.
 */
class FakeAIPredictionEngine(
    private val violations: List<LanguageViolation> = emptyList(),
) : AIPredictionEngine {

    /** Every call recorded, so a test can assert what the command actually sent the model. */
    val assertLanguageCalls = mutableListOf<AssertLanguageCall>()

    data class AssertLanguageCall(
        val language: String,
        val languageTag: String,
        val ignore: List<String>,
        val onScreenText: String?,
    )

    override suspend fun assertLanguage(
        screen: ByteArray,
        aiClient: AI,
        language: String,
        languageTag: String,
        ignore: List<String>,
        onScreenText: String?,
    ): List<LanguageViolation> {
        assertLanguageCalls += AssertLanguageCall(language, languageTag, ignore, onScreenText)
        return violations
    }

    override suspend fun findDefects(screen: ByteArray, aiClient: AI): List<Defect> =
        throw NotImplementedError("FakeAIPredictionEngine.findDefects is not scripted")

    override suspend fun performAssertion(screen: ByteArray, aiClient: AI, assertion: String): Defect? =
        throw NotImplementedError("FakeAIPredictionEngine.performAssertion is not scripted")

    override suspend fun extractText(screen: ByteArray, aiClient: AI, query: String): String =
        throw NotImplementedError("FakeAIPredictionEngine.extractText is not scripted")

    override suspend fun extractPoint(screen: ByteArray, aiClient: AI, query: String): String =
        throw NotImplementedError("FakeAIPredictionEngine.extractPoint is not scripted")

    override suspend fun extractPointWithReasoning(
        screen: ByteArray,
        aiClient: AI,
        query: String,
        viewHierarchy: String?,
    ): ExtractPointWithReasoningResponse? =
        throw NotImplementedError("FakeAIPredictionEngine.extractPointWithReasoning is not scripted")

    override suspend fun extractPointRefined(
        croppedScreen: ByteArray,
        aiClient: AI,
        query: String,
        contextDescription: String,
    ): ExtractPointWithReasoningResponse? =
        throw NotImplementedError("FakeAIPredictionEngine.extractPointRefined is not scripted")

    override suspend fun validatePoint(
        screen: ByteArray,
        aiClient: AI,
        query: String,
        pointXPercent: Int,
        pointYPercent: Int,
    ): ExtractPointValidationResponse? =
        throw NotImplementedError("FakeAIPredictionEngine.validatePoint is not scripted")

    override suspend fun extractComponentPoint(
        componentImage: ByteArray,
        screen: ByteArray,
        aiClient: AI,
        viewHierarchy: String?,
    ): ExtractPointWithReasoningResponse? =
        throw NotImplementedError("FakeAIPredictionEngine.extractComponentPoint is not scripted")
}
