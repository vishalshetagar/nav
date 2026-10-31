package com.example.nav.llm

import com.example.nav.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class LLMServiceTest {
    private val observation = Observation("{}", emptyMap(), ScreenContext("app", "App", emptyList(), emptyList(), emptyList(), false))
    private class FakeApi(val response: Response<GroqResponse>? = null, val cancel: Boolean = false) : GroqApi {
        var calls = 0
        var request: GroqRequest? = null
        override suspend fun getChatCompletion(authHeader: String, request: GroqRequest): Response<GroqResponse> {
            calls++; this.request = request
            if (cancel) throw CancellationException("stopped")
            return response!!
        }
    }
    @Test fun usesOnlyGroq20bAndPacesRequests() = runBlocking {
        var time = 0L
        val api = FakeApi(Response.success(GroqResponse(listOf(Choice(Message("assistant", """{"action":"explain","instruction":"A settings screen","target":"","value":""}"""), "stop")), Usage(123))))
        val service = LLMService(api) { time }
        val result = service.nextStep("test-key", observation) as AgentResult.Success
        assertEquals(123, result.tokensUsed)
        assertEquals("openai/gpt-oss-20b", api.request!!.model)
        assertEquals("low", api.request!!.reasoningEffort)
        assertTrue(service.nextStep("test-key", observation) is AgentResult.Failure)
        assertEquals(1, api.calls)
        time = 30000
        assertTrue(service.nextStep("test-key", observation) is AgentResult.Success)
        assertEquals(2, api.calls)
    }
    @Test fun cancellationIsNeverConvertedToAReply() {
        assertThrows(CancellationException::class.java) {
            runBlocking { LLMService(FakeApi(cancel = true)).nextStep("key", observation) }
        }
    }
    @Test fun noAutomaticRetryOnRateLimit() = runBlocking {
        val api = FakeApi(Response.error(429, "{}".toResponseBody()))
        val service = LLMService(api) { 0L }
        assertTrue(service.nextStep("key", observation) is AgentResult.Failure)
        assertEquals(1, api.calls)
        assertTrue(service.waitMillis >= 30000L)
    }
    @Test fun incompleteReplyCannotBecomeAction() = runBlocking {
        val api = FakeApi(Response.success(GroqResponse(listOf(Choice(Message("assistant", "{}"), "length")), null)))
        assertTrue(LLMService(api).nextStep("key", observation) is AgentResult.Failure)
    }
    @Test fun blankKeyNeverCallsNetwork() = runBlocking {
        val api = FakeApi()
        assertTrue(LLMService(api).nextStep(" ", observation) is AgentResult.Failure)
        assertEquals(0, api.calls)
    }
}
