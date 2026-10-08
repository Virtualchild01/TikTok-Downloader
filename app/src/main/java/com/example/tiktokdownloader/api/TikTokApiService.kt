package com.example.tiktokdownloader.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TikTokApiService {

    /**
     * Отказоустойчивый DNS-резолвер для обхода локальных блокировок операторов связи.
     * Если системный DNS не может найти домен (UnknownHostException), запрос перенаправляется
     * напрямую на надежные Anycast-узлы Cloudflare и публичный DNS over HTTPS (DoH).
     */
    private class ResilientDns : Dns {
        // Надежные публичные Anycast IP-адреса Cloudflare для tikwm.com
        private val tikwmFallbackIps = listOf(
            InetAddress.getByAddress("tikwm.com", byteArrayOf(104.toByte(), 21.toByte(), 70.toByte(), 241.toByte())),
            InetAddress.getByAddress("tikwm.com", byteArrayOf(172.toByte(), 67.toByte(), 176.toByte(), 178.toByte()))
        )

        override fun lookup(hostname: String): List<InetAddress> {
            // 1. Попытка стандартного системного DNS
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isNotEmpty()) return addresses
            } catch (ignored: Exception) {}

            // 2. Мгновенный возврат прямых Anycast IP для доменов tikwm при сбое DNS оператора
            if (hostname == "www.tikwm.com" || hostname == "tikwm.com" || hostname.endsWith(".tikwm.com")) {
                return listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(104.toByte(), 21.toByte(), 70.toByte(), 241.toByte())),
                    InetAddress.getByAddress(hostname, byteArrayOf(172.toByte(), 67.toByte(), 176.toByte(), 178.toByte()))
                )
            }

            // 3. Резервный опрос через защищенный DoH (1.1.1.1) для внешних CDN
            try {
                val dohAddresses = resolveViaDoh(hostname)
                if (dohAddresses.isNotEmpty()) return dohAddresses
            } catch (ignored: Exception) {}

            // Если все альтернативы исчерпаны, возвращаем стандартное исключение
            throw UnknownHostException("Не удалось определить IP-адрес для хоста: $hostname")
        }

        private fun resolveViaDoh(hostname: String): List<InetAddress> {
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(4, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return emptyList()
                    val body = response.body?.string() ?: return emptyList()
                    val json = Gson().fromJson(body, JsonObject::class.java)
                    if (json.has("Answer")) {
                        val answers = json.getAsJsonArray("Answer")
                        val list = mutableListOf<InetAddress>()
                        for (ans in answers) {
                            val obj = ans.asJsonObject
                            if (obj.get("type")?.asInt == 1) { // Type A (IPv4)
                                val ipStr = obj.get("data")?.asString
                                if (!ipStr.isNullOrBlank()) {
                                    list.add(InetAddress.getByName(ipStr))
                                }
                            }
                        }
                        list
                    } else emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /**
     * Извлечение корректной ссылки на видео TikTok из пользовательского ввода.
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
     * Отказоустойчивое получение данных видео без водяного знака.
     * Поочередно проверяет каскад независимых шлюзов:
     * 1. POST https://tikwm.com/api/
     * 2. POST https://www.tikwm.com/api/
     * 3. GET https://tikwm.com/api/
     * 4. GET https://www.tikwm.com/api/
     */
    suspend fun fetchVideoData(tiktokUrl: String): Result<TikTokData> = withContext(Dispatchers.IO) {
        val gateways = listOf(
            { requestTikWmPost("https://tikwm.com/api/", tiktokUrl) },
            { requestTikWmPost("https://www.tikwm.com/api/", tiktokUrl) },
            { requestTikWmGet("https://tikwm.com/api/", tiktokUrl) },
            { requestTikWmGet("https://www.tikwm.com/api/", tiktokUrl) }
        )

        var lastError: Throwable? = null

        for (gateway in gateways) {
            try {
                val data = gateway()
                if (data != null && !data.playUrl.isNullOrEmpty()) {
                    return@withContext Result.success(data)
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }

        val friendlyMessage = when {
            lastError is UnknownHostException ->
                "Не удалось подключиться к серверу загрузки. Проверьте подключение к сети или включите VPN."
            lastError is java.net.SocketTimeoutException ->
                "Превышено время ожидания ответа сервера. Попробуйте еще раз через несколько секунд."
            lastError != null && !lastError?.message.isNullOrBlank() ->
                lastError?.message ?: "Сбой при обращении к шлюзам TikTok"
            else ->
                "Видео не найдено или является приватным. Проверьте ссылку."
        }

        Result.failure(Exception(friendlyMessage))
    }

    private fun requestTikWmPost(endpoint: String, tiktokUrl: String): TikTokData? {
        val formBody = FormBody.Builder()
            .add("url", tiktokUrl)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            parseTikWmResponse(body, endpoint)
        }
    }

    private fun requestTikWmGet(endpoint: String, tiktokUrl: String): TikTokData? {
        val urlBuilder = endpoint.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("url", tiktokUrl)
            ?.addQueryParameter("hd", "1")
            ?: return null

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            parseTikWmResponse(body, endpoint)
        }
    }

    private fun parseTikWmResponse(responseBody: String, baseUrl: String): TikTokData? {
        val apiResponse = gson.fromJson(responseBody, TikTokApiResponse::class.java)

        if (apiResponse != null && apiResponse.code == 0 && apiResponse.data != null) {
            val data = apiResponse.data
            if (!data.playUrl.isNullOrEmpty()) {
                val host = if (baseUrl.contains("www.tikwm.com")) "https://www.tikwm.com" else "https://tikwm.com"
                val resolvedPlayUrl = if (data.playUrl.startsWith("http")) {
                    data.playUrl
                } else {
                    "$host${data.playUrl}"
                }
                return data.copy(playUrl = resolvedPlayUrl)
            }
        }
        return null
    }
}
