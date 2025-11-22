package com.example.testing

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// 1. What we SEND to Python
data class ScamCheckRequest(
    val message: String
)

// 2. What we RECEIVE from Python
data class ScamCheckResponse(
    val text_received: String,
    val scam_probability: Double,
    val is_risk: Boolean
)

// 3. Define the Interface
interface ApiService {
    @POST("/predict")
    suspend fun checkMessage(@Body request: ScamCheckRequest): ScamCheckResponse
}

// 4. Create the Retrofit Instance
object RetrofitClient {
    // ⚠️ IMPORTANT: Double check this IP address matches your computer's IP!
    private const val BASE_URL = "http://192.168.8.9:5000/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}