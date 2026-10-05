package maestro.ai.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.encodeBase64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.ai.AI
import maestro.ai.CompletionData
import maestro.ai.common.Base64Image
import org.slf4j.LoggerFactory

private const val API_URL = "https://api.openai.com/v1/chat/completions"
private const val OPENAI_DEFAULT_TEMPERATURE = 1f

private val logger = LoggerFactory.getLogger(OpenAI::class.java)

class OpenAI(
    defaultModel: String = "gpt-6-sol",
    httpClient: HttpClient = defaultHttpClient,
    private val apiKey: String,
    private val defaultTemperature: Float = 0.2f,
    private val defaultMaxTokens: Int = 1024,
    private val defaultImageDetail: String = "high",
) : AI(defaultModel = defaultModel, httpClient = httpClient) {

    // explicitNulls = false so a null temperature is left out of the request, not sent as null.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun chatCompletion(
        prompt: String,
        images: List<ByteArray>,
        temperature: Float?,
        model: String?,
        maxTokens: Int?,
        imageDetail: String?,
        jsonSchema: JsonObject?,
    ): CompletionData {
        val imagesBase64 = images.map { it.encodeBase64() }

        // Fallback to OpenAI defaults
        val actualTemperature = temperature ?: defaultTemperature
        val actualModel = model ?: defaultModel
        val actualMaxTokens = maxTokens ?: defaultMaxTokens
        val actualImageDetail = imageDetail ?: defaultImageDetail

        val imagesContent = imagesBase64.map { image ->
            ContentDetail(
                type = "image_url",
                imageUrl = Base64Image(url = "data:image/png;base64,$image", detail = actualImageDetail),
            )
        }
        val textContent = ContentDetail(type = "text", text = prompt)

        val messages = listOf(
            MessageContent(
                role = "user",
                content = imagesContent + textContent,
            )
        )

        fun request(temperature: Float?) = ChatCompletionRequest(
            model = actualModel,
            temperature = temperature,
            messages = messages,
            maxTokens = actualMaxTokens,
            seed = 1566,
            responseFormat = if (jsonSchema == null) null else ResponseFormat(
                type = "json_schema",
                jsonSchema = jsonSchema,
            ),
        )

        var sentTemperature: Float? = actualTemperature
        val chatCompletionResponse = try {
            suspend fun post(temperature: Float?) = httpClient.post(API_URL) {
                contentType(ContentType.Application.Json)
                headers["Authorization"] = "Bearer $apiKey"
                setBody(json.encodeToString(request(temperature)))
            }

            var httpResponse = post(sentTemperature)
            var body = httpResponse.bodyAsText()
            // RETRY WITHOUT TEMPERATURE when the model refuses it. Reasoning models (gpt-6-sol)
            // accept only the default, 1; older ones keep the lower, steadier 0.2. Asking OpenAI
            // rather than keeping a list of model names means a new model needs no change here.
            if (httpResponse.status == HttpStatusCode.BadRequest && rejectsTemperature(body)) {
                logger.info("$actualModel does not accept temperature $sentTemperature; retrying with its default")
                sentTemperature = null
                httpResponse = post(sentTemperature)
                body = httpResponse.bodyAsText()
            }
            if (!httpResponse.status.isSuccess()) {
                logger.error("Failed to complete request to OpenAI: ${httpResponse.status}, $body")
                throw Exception("Failed to complete request to OpenAI: ${httpResponse.status}, $body")
            }

            json.decodeFromString<ChatCompletionResponse>(body)
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from OpenAI", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to OpenAI", e)
            throw e
        }

        return CompletionData(
            prompt = prompt,
            temperature = sentTemperature ?: OPENAI_DEFAULT_TEMPERATURE,
            maxTokens = actualMaxTokens,
            images = imagesBase64,
            model = actualModel,
            response = chatCompletionResponse.choices.first().message.content,
        )
    }

    /** OpenAI's 400 for a parameter value the model does not support, naming `temperature`. */
    private fun rejectsTemperature(body: String): Boolean {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }
            .getOrNull() ?: return false
        return error["param"]?.jsonPrimitive?.contentOrNull == "temperature"
    }

    override fun close() = httpClient.close()
}
