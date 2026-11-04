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

// How OpenAI refuses a key rather than a request: 401 bad key, 403 no access (e.g. the model is not
// allowed for the key's project), 404 model not found for it, 429 out of credit or rate limited.
private val KEY_REFUSED = setOf(401, 403, 404, 429)

private val logger = LoggerFactory.getLogger(OpenAI::class.java)

class OpenAI(
    defaultModel: String = "gpt-6-sol",
    httpClient: HttpClient = defaultHttpClient,
    private val apiKey: String,
    private val defaultTemperature: Float = 0.2f,
    private val defaultMaxTokens: Int = 1024,
    private val defaultImageDetail: String = "high",
    private val fallback: Fallback? = null,
    private val apiUrl: String = API_URL,
) : AI(defaultModel = defaultModel, httpClient = httpClient) {

    /**
     * A second key, tried once when OpenAI refuses [apiKey] (see [KEY_REFUSED]), with its own model
     * when that key's project needs a different one. Read from [AI.AI_KEY_FALLBACK_ENV_VAR] and
     * [AI.AI_MODEL_FALLBACK_ENV_VAR]. Without it, a refusal fails the command as before.
     */
    data class Fallback(val apiKey: String, val model: String? = null)

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

        fun request(model: String, temperature: Float?) = ChatCompletionRequest(
            model = model,
            temperature = temperature,
            messages = messages,
            maxTokens = actualMaxTokens,
            seed = 1566,
            responseFormat = if (jsonSchema == null) null else ResponseFormat(
                type = "json_schema",
                jsonSchema = jsonSchema,
            ),
        )

        // One request with one key and model, plus its retry without temperature when needed.
        suspend fun send(key: String, model: String): Attempt {
            var temperature: Float? = actualTemperature
            suspend fun post() = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                headers["Authorization"] = "Bearer $key"
                setBody(json.encodeToString(request(model, temperature)))
            }

            var httpResponse = post()
            var body = httpResponse.bodyAsText()
            // RETRY WITHOUT TEMPERATURE when the model refuses it. Reasoning models (gpt-6-sol)
            // accept only the default, 1; older ones keep the lower, steadier 0.2. Asking OpenAI
            // rather than keeping a list of model names means a new model needs no change here.
            if (httpResponse.status == HttpStatusCode.BadRequest && rejectsTemperature(body)) {
                logger.info("$model does not accept temperature $temperature; retrying with its default")
                temperature = null
                httpResponse = post()
                body = httpResponse.bodyAsText()
            }
            return Attempt(httpResponse.status, body, model, temperature)
        }

        lateinit var attempt: Attempt
        val chatCompletionResponse = try {
            attempt = send(apiKey, actualModel)
            // FALL BACK to the second key, once, when OpenAI refuses the first one.
            if (fallback != null && attempt.status.value in KEY_REFUSED) {
                val fallbackModel = fallback.model ?: actualModel
                val reason = errorCode(attempt.body)?.let { "${attempt.status}, $it" } ?: "${attempt.status}"
                logger.warn("OpenAI refused the primary key for $actualModel ($reason); falling back to the fallback key with $fallbackModel")
                attempt = send(fallback.apiKey, fallbackModel)
            }
            if (!attempt.status.isSuccess()) {
                logger.error("Failed to complete request to OpenAI: ${attempt.status}, ${attempt.body}")
                throw Exception("Failed to complete request to OpenAI: ${attempt.status}, ${attempt.body}")
            }

            json.decodeFromString<ChatCompletionResponse>(attempt.body)
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from OpenAI", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to OpenAI", e)
            throw e
        }

        return CompletionData(
            prompt = prompt,
            temperature = attempt.temperature ?: OPENAI_DEFAULT_TEMPERATURE,
            maxTokens = actualMaxTokens,
            images = imagesBase64,
            model = attempt.model,
            response = chatCompletionResponse.choices.first().message.content,
        )
    }

    private class Attempt(val status: HttpStatusCode, val body: String, val model: String, val temperature: Float?)

    /** OpenAI's error code, e.g. `insufficient_quota` or `model_not_found`, when the body has one. */
    private fun errorCode(body: String): String? {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }
            .getOrNull() ?: return null
        return (error["code"] ?: error["type"])?.jsonPrimitive?.contentOrNull
    }

    /** OpenAI's 400 for a parameter value the model does not support, naming `temperature`. */
    private fun rejectsTemperature(body: String): Boolean {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }
            .getOrNull() ?: return false
        return error["param"]?.jsonPrimitive?.contentOrNull == "temperature"
    }

    override fun close() = httpClient.close()
}
