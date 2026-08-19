package com.translator.gemini

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class TranslatedBubble(
    val ymin: Float,
    val xmin: Float,
    val ymax: Float,
    val xmax: Float,
    val text: String
)

object GeminiApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun translateScreen(bitmap: Bitmap, apiKey: String): List<TranslatedBubble> = withContext(Dispatchers.IO) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val prompt = "You are a comic translator. Detect all speech bubbles in this raw manhwa/manga image. Return ONLY a valid JSON object with the format: {\"bubbles\": [{\"box_2d\": [ymin, xmin, ymax, xmax], \"text\": \"English translation\"}]}. Coordinates should be scaled 0 to 1000."

        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        }))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val request = Request.Builder().url(url).post(requestBody).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: return@withContext emptyList()

        parseGeminiResponse(responseBody)
    }

    private fun parseGeminiResponse(jsonString: String): List<TranslatedBubble> {
        val bubbles = mutableListOf<TranslatedBubble>()
        try {
            val root = JSONObject(jsonString)
            val textContent = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val parsedJson = JSONObject(textContent)
            val array = parsedJson.getJSONArray("bubbles")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val box = item.getJSONArray("box_2d")
                bubbles.add(
                    TranslatedBubble(
                        ymin = box.getDouble(0).toFloat(),
                        xmin = box.getDouble(1).toFloat(),
                        ymax = box.getDouble(2).toFloat(),
                        xmax = box.getDouble(3).toFloat(),
                        text = item.getString("text")
                    )
                )
            }
        } catch (_: Exception) {}
        return bubbles
    }
}

