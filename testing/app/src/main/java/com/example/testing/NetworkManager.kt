package com.example.testing

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// 1. Request/Response Models
data class ScamCheckRequest(val message: String)

data class ScamCheckResponse(
    val text_received: String,
    val scam_probability: Double,
    val is_risk: Boolean,
    val advice: String? = null // Now usually null initially
)

// NEW: Response model for the secondary advice call
data class AdviceResponse(
    val advice: String
)

// 2. Interface
interface ApiServiceSlow {
    @POST("/v1/chat/completions?token=7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun getAdvice(@Body request: ScamCheckRequest): AdviceResponse
}
interface ApiServiceFast {
    @POST("/predict?7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun checkMessage(@Body request: ScamCheckRequest): ScamCheckResponse
}

// 3. Server Configuration & Presets (Unchanged)
object ServerConfig {
    val PRESETS = listOf(
        "https://detect.443.gs/" to "(Default)",
        "http2://detect2.443.gs/" to "(backup)",
        "http://192.168.0.169:5000/" to "(Localhost)"
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
        else
            "${ServerConfig.currentBaseUrl}/"

        apiService = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServiceFast::class.java)
    }
}
object RetrofitClientSlow {
    private var apiService: ApiServiceSlow? = null

    val instance: ApiServiceSlow
        get() {
            if (apiService == null) rebuild()
            return apiService!!
        }

    fun rebuild() {
        val url = if (ServerConfigAdvice.baseUrl.endsWith("/"))
            ServerConfigAdvice.baseUrl
        else
            "${ServerConfigAdvice.baseUrl}/"

        apiService = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServiceSlow::class.java)
    }
    fun updateUrl(newUrl: String) {
        ServerConfig.currentBaseUrl = newUrl
        apiService = null
    }
}
object ServerConfigAdvice {
    var baseUrl: String = "https://ai-anti-scam.443.gs"
}
