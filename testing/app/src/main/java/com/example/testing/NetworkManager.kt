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
    val advice: String? = null
)

// 2. Interface
interface ApiService {
    @POST("/predict?7a4c019c-db87-4e21-b90e-9cfc75057f7e")
    suspend fun checkMessage(@Body request: ScamCheckRequest): ScamCheckResponse
}

// 3. Server Configuration & Presets
object ServerConfig {
    // Define your 3 Presets here
    val PRESETS = listOf(
        "https://detect.443.gs/" to "(Default)",
        "http2://detect2.443.gs/" to "(backup)",
        "http://192.168.0.169:5000/" to "(Localhost)"
    )

    // Current URL (Starts with the first preset)
    var currentBaseUrl: String = PRESETS[0].first
}

// 4. Dynamic Retrofit Client
object RetrofitClient {
    private var apiService: ApiService? = null

    // We use a getter so we can check if it exists or needs rebuilding
    val instance: ApiService
        get() {
            if (apiService == null) {
                rebuild()
            }
            return apiService!!
        }

    fun rebuild() {
        // Ensure URL ends with /
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

    // Call this when the user changes settings
    fun updateUrl(newUrl: String) {
        ServerConfig.currentBaseUrl = newUrl
        apiService = null // Force null so next call triggers rebuild
    }
}