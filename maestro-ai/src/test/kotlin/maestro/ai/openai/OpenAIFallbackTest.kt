package maestro.ai.openai

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The fallback key: one more try with a second key when OpenAI refuses the first. */
class OpenAIFallbackTest {

    private lateinit var server: MockWebServer
    private val warnings = mutableListOf<String>()
    private val appender = object : AbstractAppender("openai-test", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (event.level == Level.WARN) warnings += event.message.formattedMessage
        }
    }
    private val openAiLogger = LogManager.getLogger(OpenAI::class.java) as Logger

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        appender.start()
        openAiLogger.addAppender(appender)
        openAiLogger.level = Level.ALL
    }

    @AfterEach
    fun tearDown() {
        openAiLogger.removeAppender(appender)
        server.shutdown()
    }

    private fun client(fallback: OpenAI.Fallback?) = OpenAI(
        defaultModel = "gpt-6-sol",
        httpClient = HttpClient(),
        apiKey = "primary-key",
        fallback = fallback,
        apiUrl = server.url("/v1/chat/completions").toString(),
    )

    private fun ok(content: String = "fine") = MockResponse().setResponseCode(200).setBody(
        """{"id":"x","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"$content"}}]}"""
    )

    private fun error(code: Int, errorCode: String, param: String? = null) = MockResponse().setResponseCode(code).setBody(
        """{"error":{"message":"refused","type":"x","code":"$errorCode"${param?.let { ",\"param\":\"$it\"" } ?: ""}}}"""
    )

    private val RecordedRequest.key get() = getHeader("Authorization")
    private val RecordedRequest.json get() = Json.parseToJsonElement(body.readUtf8()).jsonObject
    private fun RecordedRequest.model(json: kotlinx.serialization.json.JsonObject) = json["model"]?.jsonPrimitive?.content

    @Test
    fun `out of credit on the primary key - retries once with the fallback key and its model, and logs it`() {
        server.enqueue(error(429, "insufficient_quota"))
        server.enqueue(ok("from the fallback"))

        val result = runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key", model = "gpt-4.1")).chatCompletion("prompt") }

        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.key).isEqualTo("Bearer primary-key")
        assertThat(first.model(first.json)).isEqualTo("gpt-6-sol")
        assertThat(second.key).isEqualTo("Bearer fallback-key")
        assertThat(second.model(second.json)).isEqualTo("gpt-4.1")
        assertThat(result.response).isEqualTo("from the fallback")
        assertThat(result.model).isEqualTo("gpt-4.1")
        assertThat(warnings).containsExactly(
            "OpenAI refused the primary key for gpt-6-sol (429 Client Error, insufficient_quota); " +
                "falling back to the fallback key with gpt-4.1"
        )
        assertThat(warnings.single()).doesNotContain("fallback-key")
        assertThat(warnings.single()).doesNotContain("primary-key")
    }

    @Test
    fun `every refusal status falls back, keeping the primary model when the fallback names none`() {
        for (status in listOf(401, 403, 404, 429)) {
            server.enqueue(error(status, "refused_$status"))
            server.enqueue(ok())
            runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key")).chatCompletion("prompt") }
            server.takeRequest()
            val retry = server.takeRequest()
            assertThat(retry.key).isEqualTo("Bearer fallback-key")
            assertThat(retry.model(retry.json)).isEqualTo("gpt-6-sol")
        }
        assertThat(warnings).hasSize(4)
    }

    @Test
    fun `primary key answers - no fallback request, no warning`() {
        server.enqueue(ok())

        runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key")).chatCompletion("prompt") }

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().key).isEqualTo("Bearer primary-key")
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `a bad request is not a refused key - no fallback`() {
        server.enqueue(error(400, "invalid_request_error"))

        val e = assertThrows<Exception> {
            runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key")).chatCompletion("prompt") }
        }

        assertThat(e.message).startsWith("Failed to complete request to OpenAI: 400")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `no fallback configured - a refusal fails exactly as before`() {
        server.enqueue(error(429, "insufficient_quota"))

        val e = assertThrows<Exception> { runBlocking { client(fallback = null).chatCompletion("prompt") } }

        assertThat(e.message).startsWith("Failed to complete request to OpenAI: 429")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `the fallback key is refused too - fails with its answer, after one fallback try`() {
        server.enqueue(error(429, "insufficient_quota"))
        server.enqueue(error(401, "invalid_api_key"))

        val e = assertThrows<Exception> {
            runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key")).chatCompletion("prompt") }
        }

        assertThat(e.message).startsWith("Failed to complete request to OpenAI: 401")
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(warnings).hasSize(1)
    }

    @Test
    fun `the fallback model's own temperature refusal is still retried without temperature`() {
        server.enqueue(error(429, "insufficient_quota"))
        server.enqueue(error(400, "unsupported_value", param = "temperature"))
        server.enqueue(ok())

        val result = runBlocking { client(OpenAI.Fallback(apiKey = "fallback-key", model = "gpt-6-luna")).chatCompletion("prompt") }

        assertThat(server.requestCount).isEqualTo(3)
        server.takeRequest()
        val withTemperature = server.takeRequest().json
        val withoutTemperature = server.takeRequest().json
        assertThat(withTemperature["temperature"]?.jsonPrimitive?.content).isEqualTo("0.2")
        assertThat(withoutTemperature.containsKey("temperature")).isFalse()
        assertThat(result.temperature).isEqualTo(1f)
    }
}
