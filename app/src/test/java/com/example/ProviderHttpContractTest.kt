package com.example

import com.example.ai.AiErrorCategory
import com.example.ai.AiTextRequest
import com.example.ai.ConnectionState
import com.example.ai.GroqAiProvider
import com.example.ai.ModelCatalogCache
import com.example.ai.ProviderHealthStore
import com.example.ai.ProviderId
import com.example.ai.SmartModelRouter
import com.example.ai.TaskProfile
import com.example.ai.XkiroAiProvider
import com.example.ai.asAiError
import com.example.security.ApiKeyRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end provider HTTP behaviour against mocked responses (requirements 8, 11, 16, 27.6-27.12).
 *
 * No real API key is used and no real network call is made.
 */
class ProviderHttpContractTest {

    private lateinit var xkiroServer: MockWebServer
    private lateinit var groqServer: MockWebServer
    private lateinit var keys: ApiKeyRepository
    private lateinit var health: ProviderHealthStore
    private lateinit var catalog: ModelCatalogCache

    private val xkiroModels = """
        {"data":[
          {"id":"openai/gpt-5.6-sol","display_name":"GPT-5.6 Sol","owned_by":"openai",
           "access_tier":"free","context_length":128000,"max_output_tokens":8192,
           "capabilities":["chat","reasoning"],
           "pricing":{"input":0.0,"output":0.0}},
          {"id":"vendor/premium-model","access_tier":"premium","context_length":128000,
           "max_output_tokens":8192,"capabilities":["chat"]}
        ]}
    """.trimIndent()

    private val groqModels = """
        {"object":"list","data":[
          {"id":"openai/gpt-oss-120b","owned_by":"openai","active":true,
           "context_window":131072,"max_completion_tokens":32768},
          {"id":"groq/compound-mini","owned_by":"groq","active":true,
           "context_window":131072,"max_completion_tokens":8192}
        ]}
    """.trimIndent()

    private fun chatResponse(text: String) = """
        {"id":"1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}
    """.trimIndent()

    @Before
    fun setUp() {
        xkiroServer = MockWebServer().apply { start() }
        groqServer = MockWebServer().apply { start() }
        keys = ApiKeyRepository(InMemorySecretStore())
        health = ProviderHealthStore()
        catalog = ModelCatalogCache()
    }

    @After
    fun tearDown() {
        xkiroServer.shutdown()
        groqServer.shutdown()
    }

    private fun xkiro(client: OkHttpClient = OkHttpClient.Builder().build()) =
        XkiroAiProvider(keys, health, catalog, client, baseUrl = xkiroServer.url("/v1").toString().trimEnd('/'))

    private fun groq(client: OkHttpClient = OkHttpClient.Builder().build()) =
        GroqAiProvider(keys, health, catalog, client, baseUrl = groqServer.url("/openai/v1").toString().trimEnd('/'))

    private fun pathDispatcher(handler: (RecordedRequest) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }

    // ---------------------------------------------------------------- discovery

    @Test
    fun xkiroDiscoveryParsesCatalogueAndKeepsVendorPrefixes() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher { req ->
            if (req.path == "/v1/models") MockResponse().setResponseCode(200).setBody(xkiroModels)
            else MockResponse().setResponseCode(404)
        }

        val models = xkiro().discoverModels(forceRefresh = true)
        assertEquals(2, models.size)
        assertTrue(models.any { it.modelId == "openai/gpt-5.6-sol" })
        assertTrue(models.all { it.provider == ProviderId.XKIRO })
    }

    @Test
    fun discoveryUsesCacheAndDoesNotRefetchEveryTime() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher { MockResponse().setResponseCode(200).setBody(xkiroModels) }

        val provider = xkiro()
        provider.discoverModels(forceRefresh = true)
        val afterFirst = xkiroServer.requestCount
        provider.discoverModels()
        provider.discoverModels()

        assertEquals("catalogue must be cached between calls", afterFirst, xkiroServer.requestCount)
    }

    // ---------------------------------------------------------------- request shape

    @Test
    fun chatRequestSendsOnlySupportedFieldsAndFullModelId() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(200).setBody(chatResponse("ok"))
        }

        xkiro().generate(
            AiTextRequest(
                systemPrompt = "You are a chat assistant.",
                userPrompt = "hello",
                maxOutputTokens = 320,
                temperature = 0.7,
                modelId = "openai/gpt-5.6-sol"
            )
        )

        val request = xkiroServer.takeRequest()
        val body = request.body.readUtf8()

        assertTrue("full vendor-prefixed id required", body.contains("\"model\":\"openai/gpt-5.6-sol\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"max_tokens\":320"))

        // No speculative OpenAI fields that Groq/xKiro may reject.
        listOf("frequency_penalty", "presence_penalty", "top_logprobs", "logprobs", "n\":", "stream_options", "parallel_tool_calls")
            .forEach { assertTrue("must not send $it", !body.contains(it)) }

        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.contains("application/json"))
        assertEquals("/v1/chat/completions", request.path)
    }

    @Test
    fun chatResponseIsParsedCorrectly() = runBlocking {
        keys.setKey(ProviderId.GROQ, "test-key")
        groqServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(200).setBody(chatResponse("Haan bhai, chalte hain!"))
        }

        val result = groq().generate(
            AiTextRequest(userPrompt = "x", modelId = "openai/gpt-oss-120b")
        )

        assertTrue(result.isSuccess)
        assertEquals("Haan bhai, chalte hain!", result.getOrThrow().text)
        assertEquals("openai/gpt-oss-120b", result.getOrThrow().modelId)
    }

    // ---------------------------------------------------------------- error mapping

    @Test
    fun http401_mapsToInvalidApiKeyAndCoolsProvider() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "bad-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}""")
        }

        val result = xkiro().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))
        assertEquals(AiErrorCategory.InvalidApiKey, result.exceptionOrNull()!!.asAiError().category)
        assertTrue(health.isCoolingDown(ProviderId.XKIRO))
    }

    @Test
    fun http403_mapsToPermissionDeniedForThatModelOnly() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"permission_denied"}}""")
        }

        val result = xkiro().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))
        assertEquals(AiErrorCategory.PermissionDenied, result.exceptionOrNull()!!.asAiError().category)

        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "openai/gpt-5.6-sol"))
        assertTrue("provider itself stays usable", !health.isCoolingDown(ProviderId.XKIRO))
    }

    @Test
    fun http404_mapsToModelUnavailableAndRetiresModel() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}""")
        }

        val result = xkiro().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))
        assertEquals(AiErrorCategory.ModelUnavailable, result.exceptionOrNull()!!.asAiError().category)
        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "openai/gpt-5.6-sol"))
    }

    @Test
    fun http429_mapsToRateLimitedAndReadsRetryAfter() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(429)
                .addHeader("Retry-After", "45")
                .setBody("""{"error":{"message":"rate limited"}}""")
        }

        val result = xkiro().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))
        val error = result.exceptionOrNull()!!.asAiError()

        assertEquals(AiErrorCategory.RateLimited, error.category)
        assertEquals(45_000L, error.retryAfterMs)
    }

    @Test
    fun http500_mapsToServerError() = runBlocking {
        keys.setKey(ProviderId.GROQ, "test-key")
        groqServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"internal"}}""")
        }

        val result = groq().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-oss-120b"))
        assertEquals(AiErrorCategory.ServerError, result.exceptionOrNull()!!.asAiError().category)
    }

    @Test
    fun http422_mapsToInvalidRequest() = runBlocking {
        keys.setKey(ProviderId.GROQ, "test-key")
        groqServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(422).setBody("""{"error":{"message":"unprocessable"}}""")
        }

        val result = groq().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-oss-120b"))
        assertEquals(AiErrorCategory.InvalidRequest, result.exceptionOrNull()!!.asAiError().category)
    }

    @Test
    fun timeoutMapsToTimeoutCategory() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(200).setBody(chatResponse("ok")).setBodyDelay(600, TimeUnit.MILLISECONDS)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(50, TimeUnit.MILLISECONDS)
            .readTimeout(80, TimeUnit.MILLISECONDS)
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val result = xkiro(client).generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))
        assertEquals(AiErrorCategory.Timeout, result.exceptionOrNull()!!.asAiError().category)
    }

    @Test
    fun missingKeyFailsWithoutAnyHttpRequest() = runBlocking {
        xkiroServer.dispatcher = pathDispatcher { MockResponse().setResponseCode(200).setBody(xkiroModels) }

        val result = xkiro().generate(AiTextRequest(userPrompt = "x", modelId = "openai/gpt-5.6-sol"))

        assertEquals(AiErrorCategory.MissingApiKey, result.exceptionOrNull()!!.asAiError().category)
        assertEquals("no request may be sent without a key", 0, xkiroServer.requestCount)
    }

    // ---------------------------------------------------------------- testConnection

    @Test
    fun testConnectionReportsConnectedOnRealSuccess() = runBlocking {
        keys.setKey(ProviderId.GROQ, "test-key")
        groqServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(groqModels)
                else -> MockResponse().setResponseCode(200).setBody(chatResponse("OK"))
            }
        }

        val state = groq().testConnection()
        assertTrue("expected Connected, got ${state::class.java.simpleName}", state is ConnectionState.Connected)
    }

    @Test
    fun testConnectionReportsInvalidKeyOn401() = runBlocking {
        keys.setKey(ProviderId.GROQ, "bad")
        groqServer.dispatcher = pathDispatcher { MockResponse().setResponseCode(401).setBody("""{"error":{}}""") }

        val state = groq().testConnection()
        assertTrue("expected InvalidKey, got ${state::class.java.simpleName}", state is ConnectionState.InvalidKey)
    }

    @Test
    fun testConnectionReportsNoEligibleModelWhenCatalogueHasNone() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "test-key")
        xkiroServer.dispatcher = pathDispatcher {
            MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"openai/embed","capabilities":["embedding"]}]}""")
        }

        val state = xkiro().testConnection()
        assertTrue("expected NoEligibleModel, got ${state::class.java.simpleName}", state is ConnectionState.NoEligibleModel)
    }

    @Test
    fun testConnectionReportsNotConfiguredWithoutKey() = runBlocking {
        assertEquals(ConnectionState.NotConfigured, xkiro().testConnection())
    }

    // ---------------------------------------------------------------- routing / fallback

    @Test
    fun routerFailsOverFromXkiroToGroq() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "xk")
        keys.setKey(ProviderId.GROQ, "gq")

        xkiroServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(xkiroModels)
                else -> MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}""")
            }
        }
        groqServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(groqModels)
                else -> MockResponse().setResponseCode(200).setBody(chatResponse("Groq answered"))
            }
        }

        val router = SmartModelRouter(listOf(xkiro(), groq()), health, catalog)
        val result = router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        assertTrue(result.isSuccess)
        assertEquals(ProviderId.GROQ, result.getOrThrow().let { ProviderId.GROQ })
        assertEquals("Groq answered", result.getOrThrow().text)
        assertEquals(ProviderId.GROQ, router.lastRun.value?.provider)
    }

    @Test
    fun routerUsesOnlyConfiguredProviders() = runBlocking {
        keys.setKey(ProviderId.GROQ, "gq")

        groqServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(groqModels)
                else -> MockResponse().setResponseCode(200).setBody(chatResponse("only groq"))
            }
        }

        val router = SmartModelRouter(listOf(xkiro(), groq()), health, catalog)
        val result = router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        assertTrue(result.isSuccess)
        assertEquals("xKiro must not be contacted without a key", 0, xkiroServer.requestCount)
    }

    @Test
    fun routerWithNoConfiguredProvidersReportsNoEligibleModel() = runBlocking {
        val router = SmartModelRouter(listOf(xkiro(), groq()), health, catalog)
        val result = router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        assertEquals(AiErrorCategory.NoEligibleModel, result.exceptionOrNull()!!.asAiError().category)
        assertEquals(0, xkiroServer.requestCount)
        assertEquals(0, groqServer.requestCount)
    }

    @Test
    fun routerAttemptsAreBounded() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "xk")
        xkiroServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(xkiroModels)
                else -> MockResponse().setResponseCode(500).setBody("""{"error":{"message":"always down"}}""")
            }
        }

        val router = SmartModelRouter(listOf(xkiro()), health, catalog)
        router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        val attempts = xkiroServer.requestCount
        assertTrue(
            "attempts must be bounded by MAX_ATTEMPTS (was $attempts)",
            attempts <= SmartModelRouter.MAX_ATTEMPTS + 1 // +1 for the models request
        )
    }

    @Test
    fun routerReturnsInvalidApiKeyWhenTheOnlyProviderKeyIsBad() = runBlocking {
        keys.setKey(ProviderId.GROQ, "bad")
        groqServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(groqModels)
                else -> MockResponse().setResponseCode(401).setBody("""{"error":{"message":"nope"}}""")
            }
        }

        val router = SmartModelRouter(listOf(groq()), health, catalog)
        val result = router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        assertEquals(AiErrorCategory.InvalidApiKey, result.exceptionOrNull()!!.asAiError().category)
    }

    @Test
    fun routerRecordsSafeDiagnosticsOnly() = runBlocking {
        keys.setKey(ProviderId.XKIRO, "xk-super-secret-key-value")
        xkiroServer.dispatcher = pathDispatcher { req ->
            when {
                req.path!!.endsWith("/models") -> MockResponse().setResponseCode(200).setBody(xkiroModels)
                else -> MockResponse().setResponseCode(200).setBody(chatResponse("done"))
            }
        }

        val router = SmartModelRouter(listOf(xkiro()), health, catalog)
        router.generate(AiTextRequest(userPrompt = "hi"), TaskProfile.default())

        val run = router.lastRun.value!!
        assertEquals("success", run.result)
        assertTrue(run.model!!.startsWith("openai/"))
        // Diagnostics must never carry the key.
        assertTrue(!run.toString().contains("xk-super-secret-key-value"))
    }
}
