package maestro.ai.cloud

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import maestro.ai.openai.OpenAI
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OpenAI::class.java)

@Serializable
data class Defect(
    val category: String,
    val reasoning: String,
    /**
     * The exact user-visible string this defect is about, when the check has one.
     *
     * `reasoning` is prose written for a human and its wording is ours to change; anything
     * machine-readable must not be recovered by parsing it. The localization report groups
     * untranslated strings per screen, so it needs the string itself, verbatim.
     *
     * Null for checks that describe a screen rather than a string (`assertNoDefectsWithAI`,
     * `assertWithAI`), and for defects decoded from the cloud API, which does not send it.
     */
    val offendingText: String? = null,
)

@Serializable
data class FindDefectsRequest(
    val assertion: String? = null,
    val screen: ByteArray,
)

@Serializable
data class FindDefectsResponse(
    val defects: List<Defect>,
)

@Serializable
data class LanguageViolation(
    val text: String,
    val detectedLanguage: String,
    val reasoning: String,
    /**
     * The model's own verdict, read rather than inferred.
     *
     * Models populate `violations` as "strings I examined", not "strings that are wrong": a real
     * run reported "Paris" with the reasoning *"the German equivalent 'Paris' is spelled the same,
     * so this is not a violation"*, and "Booking.com" with *"this is a brand name and should not be
     * translated"*. The judgement was right both times; only the output contract was wrong. Asking
     * more firmly in the prompt does not fix that -- the prompt already told it not to report
     * brands. Making the verdict a required field and filtering on it does.
     */
    val isViolation: Boolean,
)

@Serializable
data class AssertLanguageResponse(
    val violations: List<LanguageViolation>,
)

@Serializable
data class ExtractTextWithAiRequest(
    val query: String,
    val screen: ByteArray,
)

@Serializable
data class ExtractTextWithAiResponse(
    val text: String,
)

@Serializable
data class ExtractPointWithAiResponse(
    val text: String,
)

@Serializable
data class ExtractPointWithReasoningResponse(
    val reasoning: String,
    val description: String,
    val boundingRegion: String,
    val text: String,
)

@Serializable
data class ExtractPointValidationResponse(
    val isCorrect: Boolean,
    val correctedText: String,
    val reasoning: String,
)

class ApiClient {
    private val baseUrl by lazy {
        System.getenv("MAESTRO_CLOUD_API_URL") ?: "https://api.copilot.mobile.dev"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            Json {
                ignoreUnknownKeys = true
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 60000
            requestTimeoutMillis = 60000
        }
    }

    suspend fun extractTextWithAi(
        apiKey: String,
        query: String,
        screen: ByteArray,
    ): ExtractTextWithAiResponse {
        val url = "$baseUrl/v2/extract-text"

        val response = try {
            val httpResponse = httpClient.post(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) // Explicitly set JSON content type
                }
                setBody(json.encodeToString(ExtractTextWithAiRequest(query, screen)))
            }

            val body = httpResponse.bodyAsText()
            if (!httpResponse.status.isSuccess()) {
                logger.error("Failed to complete request to Maestro Cloud: ${httpResponse.status}, $body")
                throw Exception("Failed to complete request to Maestro Cloud: ${httpResponse.status}, $body")
            }

            json.decodeFromString<ExtractTextWithAiResponse>(body)
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from Maestro Cloud", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to Maestro Cloud", e)
            throw e
        }

        return response
    }

    suspend fun extractPointWithAi(
        apiKey: String,
        query: String,
        screen: ByteArray,
    ): ExtractPointWithAiResponse {
        return ExtractPointWithAiResponse("0%,0%")
    }

    suspend fun findDefects(
        apiKey: String,
        screen: ByteArray,
        assertion: String? = null,
    ): FindDefectsResponse {
        val url = "$baseUrl/v2/find-defects"

        val response = try {
            val httpResponse = httpClient.post(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) // Explicitly set JSON content type
                }
                setBody(json.encodeToString(FindDefectsRequest(assertion = assertion, screen = screen)))
            }

            val body = httpResponse.bodyAsText()
            if (!httpResponse.status.isSuccess()) {
                logger.error("Failed to complete request to Maestro Cloud: ${httpResponse.status}, $body")
                throw Exception("Failed to complete request to Maestro Cloud: ${httpResponse.status}, $body")
            }

            json.decodeFromString<FindDefectsResponse>(body)
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from Maestro Cloud", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to Maestro Cloud", e)
            throw e
        }

        return response
    }

}
