package com.example.emotiondetectionapp.network

import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("predict")
    suspend fun predictEmotion(
        @Body request: TextRequest
    ): EmotionResponse
}
