package com.example.tiktokdownloader.api

import com.google.gson.annotations.SerializedName

/**
 * Data models for TikTok API unwatermarked video resolution.
 */
data class TikTokApiResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("msg") val msg: String?,
    @SerializedName("data") val data: TikTokData?
)

data class TikTokData(
    @SerializedName("id") val id: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("play") val playUrl: String?,      // Direct unwatermarked video URL
    @SerializedName("wmplay") val wmPlayUrl: String?,  // Video with watermark (fallback)
    @SerializedName("music") val musicUrl: String?,
    @SerializedName("author") val author: TikTokAuthor?
)

data class TikTokAuthor(
    @SerializedName("id") val id: String?,
    @SerializedName("unique_id") val uniqueId: String?,
    @SerializedName("nickname") val nickname: String?,
    @SerializedName("avatar") val avatar: String?
)
