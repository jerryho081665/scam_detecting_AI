package com.example.testing

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

// 1. Request/Response Models
data class ScamCheckRequest(val message: String)

data class ScamCheckResponse(
    val text_received: String,
    val scam_probability: Double,
    val is_risk: Boolean,
    val advice: String? = null
)

data class AdviceResponse(
    val choices: List<Choice>
)

data class Choice(
    val index: Int?,
    val message: MessageContent
)

data class MessageContent(
    val role: String,
    val content: String
)

data class OpenAIRequest(
    val model: String,
    val messages: List<Message>,
    val reasoning: Reasoning? = null,
    val max_tokens: Int = 1500
)

data class Reasoning(
    val enabled: Boolean
)

data class Message(
    val role: String,
    val content: String
)

// --- ASR Configuration ---
data class AsrProvider(
    val name: String,
    val id: String
)

object ServerConfigAsr {
    val PROVIDERS = listOf(
        AsrProvider("Google Native (Default)", "google"),
        AsrProvider("Yating (雅婷逐字稿)", "yating")
    )

    var currentProvider: AsrProvider = PROVIDERS[0]
    var yatingApiKey: String = ""
}

// --- NEW: TTS Configuration ---
object TtsConfig {
    // UPDATED: Defaults to FALSE (closed)
    var isEnabled: Boolean = false
    var currentVoiceName: String = ""
}

// 2. Interface
interface ApiServiceSlow {
    @POST
    suspend fun getAdvice(
        @Url url: String,
        @Header("Authorization") auth: String?,
        @Body request: OpenAIRequest
    ): AdviceResponse
}

interface ApiServiceFast {
    @POST("/predict?7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun checkMessage(@Body request: ScamCheckRequest): ScamCheckResponse
}

// 3. Server Configuration

data class AdviceProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val useAuthHeader: Boolean,
    val supportsReasoning: Boolean
)

object ServerConfig {
    val PRESETS = listOf(
        "https://detect.443.gs/" to "(Default)",
        "https://detect2.443.gs/" to "(backup)",
        "http://10.112.0.52:5000/" to "(inuk)"
    )
    var currentBaseUrl: String = PRESETS[0].first
}

object RetrofitClientFast {
    private var apiService: ApiServiceFast? = null

    val instance: ApiServiceFast
        get() {
            if (apiService == null) rebuild()
            return apiService!!
        }

    fun rebuild() {
        val url = if (ServerConfig.currentBaseUrl.endsWith("/"))
            ServerConfig.currentBaseUrl
        else "${ServerConfig.currentBaseUrl}/"

        apiService = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServiceFast::class.java)
    }
}

object ServerConfigAdvice {
    const val MANUAL_PROVIDER_NAME = "Manual Input (Qwen)"

    val PROVIDERS = listOf(
        AdviceProvider(
            name = "OpenRouter (Grok)",
            baseUrl = "https://openrouter.ai/api/v1/",
            apiKey = "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",
            modelId = "x-ai/grok-4.1-fast:free",
            useAuthHeader = true,
            supportsReasoning = true
        ),
        AdviceProvider(
            name = "OpenRouter (Chimera)",
            baseUrl = "https://openrouter.ai/api/v1/",
            apiKey = "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",
            modelId = "tngtech/tng-r1t-chimera:free",
            useAuthHeader = true,
            supportsReasoning = false
        ),
        AdviceProvider(
            name = "Local (Qwen)",
            baseUrl = "https://ai-anti-scam.443.gs/v1/",
            apiKey = "7a4c019c-db87-4e21-b90e-9cfc75057f7e",
            modelId = "qwen/qwen3-30b-a3b-2507",
            useAuthHeader = false,
            supportsReasoning = false
        ),
        AdviceProvider(
            name = "Local (Qwen - Backup)",
            baseUrl = "https://ai-anti-scam2.443.gs/v1/",
            apiKey = "7a4c019c-db87-4e21-b90e-9cfc75057f7e",
            modelId = "qwen/qwen3-30b-a3b-2507",
            useAuthHeader = false,
            supportsReasoning = false
        ),
        AdviceProvider(
            name = MANUAL_PROVIDER_NAME,
            baseUrl = "",
            apiKey = "7a4c019c-db87-4e21-b90e-9cfc75057f7e",
            modelId = "qwen/qwen3-30b-a3b-2507",
            useAuthHeader = false,
            supportsReasoning = false
        )
    )

    var currentProvider: AdviceProvider = PROVIDERS[0]
}

object RetrofitClientSlow {
    private var apiService: ApiServiceSlow? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    val instance: ApiServiceSlow
        get() {
            if (apiService == null) rebuild()
            return apiService!!
        }

    fun convert(request: ScamCheckRequest): OpenAIRequest {
        val provider = ServerConfigAdvice.currentProvider

        return OpenAIRequest(
            model = provider.modelId,
            messages = listOf(
                Message(
                    role = "system",
                    content = "根據以下電話內容，解釋為甚麼這段訊息有可能是詐騙，一句話即可，若資訊不足，請回覆為甚麼無法判斷。"
                ),
                Message(
                    role = "user",
                    content = "電話內容:" + request.message
                )
            ),
            reasoning = if (provider.supportsReasoning) Reasoning(enabled = false) else null
        )
    }

    fun rebuild() {
        val url = "https://google.com/"
        apiService = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServiceSlow::class.java)
    }

    fun updateSettings(
        newFastUrl: String,
        newAdviceProvider: AdviceProvider,
        newAsrProvider: AsrProvider,
        newYatingKey: String
    ) {
        ServerConfig.currentBaseUrl = newFastUrl
        RetrofitClientFast.rebuild()

        ServerConfigAdvice.currentProvider = newAdviceProvider

        ServerConfigAsr.currentProvider = newAsrProvider
        ServerConfigAsr.yatingApiKey = newYatingKey
    }

    fun updateUrl(newUrl: String) {
        ServerConfig.currentBaseUrl = newUrl
        RetrofitClientFast.rebuild()
    }
}