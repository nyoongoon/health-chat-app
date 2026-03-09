package com.healthchat.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ChatApiClient(private var proxyUrl: String = "http://192.168.45.207:8787") {

    companion object {
        private const val TAG = "ChatApiClient"
    }

    fun updateProxyUrl(url: String) {
        proxyUrl = url
    }

    suspend fun sendMessage(message: String, healthContext: String?): String = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/chat")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 60000

            val json = JSONObject().apply {
                put("message", message)
                if (!healthContext.isNullOrBlank()) {
                    put("healthContext", healthContext)
                }
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(json.toString())
                it.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Error $responseCode: $response")
                return@withContext "서버 오류 ($responseCode): ${try { JSONObject(response).optString("error", response) } catch (e: Exception) { response }}"
            }

            val jsonResponse = JSONObject(response)
            jsonResponse.optString("response", "응답을 받지 못했습니다.")
        } catch (e: java.net.ConnectException) {
            "프록시 서버에 연결할 수 없습니다. 맥북이 켜져 있고 같은 WiFi인지 확인하세요."
        } catch (e: java.net.SocketTimeoutException) {
            "응답 시간 초과. 다시 시도해 주세요."
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            "오류: ${e.message}"
        }
    }

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    data class ChatMessage(val role: String, val content: String, val time: String)

    data class FoodAnalysisResult(
        val displayText: String,
        val foodName: String?,
        val weightGrams: Double?,
        val calories: Double?,
        val carbs: Double?,
        val protein: Double?,
        val fat: Double?,
        val mealType: String?
    )

    data class SessionInfo(val id: String, val createdAt: String, val updatedAt: String, val messageCount: Int, val preview: String, val title: String = "대화")

    suspend fun getSessions(): Pair<List<SessionInfo>, String?> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/sessions")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return@withContext Pair(emptyList(), null)
            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val obj = JSONObject(response)
            val arr = obj.getJSONArray("sessions")
            val list = mutableListOf<SessionInfo>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                list.add(SessionInfo(
                    id = s.optString("id", ""),
                    createdAt = s.optString("createdAt", ""),
                    updatedAt = s.optString("updatedAt", ""),
                    messageCount = s.optInt("messageCount", 0),
                    preview = s.optString("preview", ""),
                    title = s.optString("title", "대화")
                ))
            }
            Pair(list, obj.optString("current", null))
        } catch (e: Exception) {
            Log.e(TAG, "getSessions failed", e)
            Pair(emptyList(), null)
        }
    }

    suspend fun getSessionMessages(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/sessions/$sessionId")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return@withContext emptyList()
            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val arr = JSONObject(response).getJSONArray("messages")
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ChatMessage(
                    role = obj.optString("role", "user"),
                    content = obj.optString("content", ""),
                    time = obj.optString("time", "")
                ))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "getSessionMessages failed", e)
            emptyList()
        }
    }

    suspend fun createNewSession(): String = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/sessions/new")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return@withContext ""
            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            JSONObject(response).optString("sessionId", "")
        } catch (e: Exception) {
            Log.e(TAG, "createNewSession failed", e)
            ""
        }
    }

    suspend fun switchSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/sessions/switch/$sessionId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "switchSession failed", e)
            false
        }
    }

    suspend fun updateSessionTitle(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/sessions/title/$sessionId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000

            val json = JSONObject().apply { put("title", title) }
            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(json.toString())
                it.flush()
            }

            conn.responseCode == 200
        } catch (e: Exception) {
            Log.e(TAG, "updateSessionTitle failed", e)
            false
        }
    }

    suspend fun getHistory(): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/history")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            if (conn.responseCode != 200) return@withContext emptyList()
            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            val arr = JSONObject(response).getJSONArray("messages")
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ChatMessage(
                    role = obj.optString("role", "user"),
                    content = obj.optString("content", ""),
                    time = obj.optString("time", "")
                ))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "getHistory failed", e)
            emptyList()
        }
    }

    suspend fun sendImageMessage(imageBase64: String, mimeType: String, message: String, healthContext: String?): FoodAnalysisResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/analyze-food")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 90000

            val json = JSONObject().apply {
                put("imageBase64", imageBase64)
                put("mimeType", mimeType)
                put("message", message)
                if (!healthContext.isNullOrBlank()) {
                    put("healthContext", healthContext)
                }
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use {
                it.write(json.toString())
                it.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

            if (responseCode !in 200..299) {
                Log.e(TAG, "Image Error $responseCode: $response")
                val errMsg = "서버 오류 ($responseCode): ${try { JSONObject(response).optString("error", response) } catch (e: Exception) { response }}"
                return@withContext FoodAnalysisResult(displayText = errMsg, foodName = null, weightGrams = null, calories = null, carbs = null, protein = null, fat = null, mealType = null)
            }

            val jsonResponse = JSONObject(response)
            val displayText = jsonResponse.optString("response", "분석 결과를 받지 못했습니다.")
            val nutrition = jsonResponse.optJSONObject("nutrition")

            FoodAnalysisResult(
                displayText = displayText,
                foodName = nutrition?.optString("foodName")?.takeIf { it.isNotBlank() },
                weightGrams = nutrition?.takeIf { it.has("weightGrams") }?.getDouble("weightGrams"),
                calories = nutrition?.takeIf { it.has("calories") }?.getDouble("calories"),
                carbs = nutrition?.takeIf { it.has("carbs") }?.getDouble("carbs"),
                protein = nutrition?.takeIf { it.has("protein") }?.getDouble("protein"),
                fat = nutrition?.takeIf { it.has("fat") }?.getDouble("fat"),
                mealType = nutrition?.optString("mealType")?.takeIf { it.isNotBlank() }
            )
        } catch (e: java.net.ConnectException) {
            FoodAnalysisResult(displayText = "프록시 서버에 연결할 수 없습니다.", foodName = null, weightGrams = null, calories = null, carbs = null, protein = null, fat = null, mealType = null)
        } catch (e: java.net.SocketTimeoutException) {
            FoodAnalysisResult(displayText = "응답 시간 초과. 이미지 분석에 시간이 걸릴 수 있습니다. 다시 시도해 주세요.", foodName = null, weightGrams = null, calories = null, carbs = null, protein = null, fat = null, mealType = null)
        } catch (e: Exception) {
            Log.e(TAG, "sendImageMessage failed", e)
            FoodAnalysisResult(displayText = "오류: ${e.message}", foodName = null, weightGrams = null, calories = null, carbs = null, protein = null, fat = null, mealType = null)
        }
    }

    suspend fun clearHistory(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$proxyUrl/clear")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
}
