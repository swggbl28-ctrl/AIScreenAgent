package com.aiagent.screencontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DeepSeekApiClient(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        Ты автономный ИИ-агент Android. Анализируй иерархию элементов и задачу. Возвращай ИСКЛЮЧИТЕЛЬНО валидный JSON без markdown-тегов: {"action": "click"|"swipe"|"input"|"finish", "x": Int, "y": Int, "text": "String", "target_bounds": [left, top, right, bottom]}.
        Для swipe добавь "x2" и "y2". Для finish остальные поля не нужны.
        Координаты x,y — центр целевого элемента. target_bounds — [left,top,right,bottom].
        Отвечай ТОЛЬКО JSON-объектом.
    """.trimIndent()

    suspend fun decide(task: String, screenJson: String, history: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val userMsg = "ЗАДАЧА: $task\n\nИСТОРИЯ: $history\n\nЭКРАН (JSON):\n$screenJson\n\nВерни следующее действие JSON."
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userMsg))
                }
                val body = JSONObject().apply {
                    put("model", "deepseek-chat")
                    put("messages", messages)
                    put("temperature", 0.1)
                    put("max_tokens", 512)
                    put("stream", false)
                }
                val req = Request.Builder()
                    .url("https://api.deepseek.com/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(raw)
                    if (!json.has("choices")) return@withContext null
                    val content = json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                    parseAction(content)
                }
            } catch (e: Exception) { null }
        }

    private fun parseAction(content: String): JSONObject? {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.dropLast(3).trim()
        }
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try { JSONObject(s.substring(a, b + 1)) } catch (e: Exception) { null }
    }
}
