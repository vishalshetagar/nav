package com.example.nav.llm

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GroqApi {
    @POST("openai/v1/chat/completions")
    suspend fun getChatCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: GroqRequest
    ): Response<GroqResponse>
}

data class GroqRequest(
    val messages: List<Message>,
    val model: String = "openai/gpt-oss-20b",
    val temperature: Double = 0.1,
    @SerializedName("max_completion_tokens") val maxCompletionTokens: Int = 768,
    @SerializedName("reasoning_effort") val reasoningEffort: String = "low",
    @SerializedName("response_format") val responseFormat: Map<String, Any> = ActionParser.responseFormat
)

data class Message(val role: String, val content: String)
data class GroqResponse(val choices: List<Choice>?, val usage: Usage?)
data class Usage(@SerializedName("total_tokens") val totalTokens: Int)
data class Choice(val message: Message?, @SerializedName("finish_reason") val finishReason: String?)
