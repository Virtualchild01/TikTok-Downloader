package com.example.tiktokdownloader.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TikTokApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /**
     * Extracts a valid TikTok URL from input text (handles raw links or text shared from the TikTok app).
     */
    fun extractTikTokUrl(text: String): String? {
        val pattern = Pattern.compile("https?://([a-zA-Z0-9._-]+\\.)?tiktok\\.com/\\S+")
        val matcher = pattern.matcher(text.trim())
        return if (matcher.find()) {
            matcher.group()
        } else {
            null
        }
    }

    /**
     * Resolves unwatermarked video information using TikWM API.
     */
    suspend fun fetchVideoData(tiktokUrl: String): Result<TikTokData> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("url", tiktokUrl)
                .add("hd", "1")
                .build()

            val request = Request.Builder()
                .url("https://www.tikwm.com/api/")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error code: ${response.code}"))
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty server response"))

            val apiResponse = gson.fromJson(responseBody, TikTokApiResponse::class.java)

            if (apiResponse != null && apiResponse.code == 0 && apiResponse.data != null) {
                val data = apiResponse.data
                // Ensure the play URL is present
                if (!data.playUrl.isNullOrEmpty()) {
                    // Resolve relative URLs if needed
                    val resolvedPlayUrl = if (data.playUrl.startsWith("http")) {
                        data.playUrl
                    } else {
                        "https://www.tikwm.com${data.playUrl}"
                    }
                    Result.success(data.copy(playUrl = resolvedPlayUrl))
                } else {
                    Result.failure(Exception("Direct video URL without watermark is missing"))
                }
            } else {
                val errorMsg = apiResponse?.msg ?: "Failed to parse video. Check if the link is public."
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
