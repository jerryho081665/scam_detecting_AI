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
interface ApiService {
    // Fast call (Local BERT)
    @POST("/predict?7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun checkMessage(@Body request: ScamCheckRequest): ScamCheckResponse

    // Slow call (External LLM) - NEW
    @POST("/advice?7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun getAdvice(@Body request: ScamCheckRequest): AdviceResponse
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

// 4. Dynamic Retrofit Client (Unchanged)
object RetrofitClient {
    private var apiService: ApiService? = null

    val instance: ApiService
        get() {
            if (apiService == null) {
                rebuild()
            }
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
            .create(ApiService::class.java)
    }

    fun updateUrl(newUrl: String) {
        ServerConfig.currentBaseUrl = newUrl
        apiService = null
    }
}