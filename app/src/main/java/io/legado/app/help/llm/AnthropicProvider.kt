package io.legado.app.help.llm

import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.IOException

class AnthropicProvider : LlmApiProvider {

    override fun streamMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): Flow<String> = callbackFlow {
        val requestBody = buildRequestBody(messages, systemPrompt, config, stream = true)
        val request = buildRequest(requestBody, config)
        val maxRetries = 10

        var lastCall: okhttp3.Call? = null
        try {
            var success = false
            for (attempt in 0 until maxRetries) {
                val call = okHttpClient.newCall(request)
                lastCall = call
                val response = withContext(Dispatchers.IO) { call.execute() }
                Log.d("AiChat", "HTTP response: code=${response.code}, contentType=${response.header("content-type")}")

                if (response.isSuccessful) {
                    val reader = response.body?.byteStream()?.bufferedReader()
                    if (reader == null) {
                        close(Exception("Empty response body"))
                        return@callbackFlow
                    }
                    withContext(Dispatchers.IO) {
                        parseAnthropicSseStream(reader) { delta ->
                            trySend(delta)
                        }
                    }
                    success = true
                    break
                }

                val code = response.code
                val errorBody = response.body?.string() ?: "Unknown error"
                response.close()

                if (isRetryable(code) && attempt < maxRetries - 1) {
                    val delayMs = getRetryDelay(code, attempt)
                    Log.w("AiChat", "流式请求失败(code=$code), ${delayMs}ms后重试(${attempt + 1}/$maxRetries)")
                    kotlinx.coroutines.delay(delayMs)
                    continue
                }
                close(Exception("API error $code: $errorBody"))
                return@callbackFlow
            }
            if (success) close()
        } catch (e: IOException) {
            close(e)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { lastCall?.cancel() }
    }

    override suspend fun sendMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): String = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(messages, systemPrompt, config, stream = false)
        val request = buildRequest(requestBody, config)

        val maxRetries = 10
        var lastException: Exception? = null

        for (attempt in 0 until maxRetries) {
            try {
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body!!.string())
                    val content = json.getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.getString("type") == "text") {
                            sb.append(block.getString("text"))
                        }
                    }
                    return@withContext sb.toString()
                }

                val code = response.code
                val errorBody = response.body?.string() ?: "Unknown error"
                response.close()

                if (isRetryable(code) && attempt < maxRetries - 1) {
                    val delayMs = getRetryDelay(code, attempt)
                    Log.w("AiChat", "请求失败(code=$code), ${delayMs}ms后重试(${attempt + 1}/$maxRetries)")
                    delay(delayMs)
                    continue
                }
                throw Exception("API error $code: $errorBody")
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    val delayMs = getRetryDelay(0, attempt)
                    Log.w("AiChat", "网络异常, ${delayMs}ms后重试(${attempt + 1}/$maxRetries): ${e.message}")
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: Exception("重试耗尽")
    }

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig,
        stream: Boolean
    ): String {
        val json = JSONObject()
        json.put("model", config.model)
        json.put("max_tokens", 4096)
        json.put("stream", stream)

        if (!systemPrompt.isNullOrBlank()) {
            json.put("system", systemPrompt)
        }

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        return json.toString()
    }

    private fun buildRequest(body: String, config: LlmConfig): Request {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/v1/messages"

        return Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .addHeader("accept", "text/event-stream")
            .build()
    }

    private fun isRetryable(code: Int): Boolean {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 529
    }

    private fun getRetryDelay(code: Int, attempt: Int): Long {
        val base = if (code == 429) 5000L else 2000L
        return base * (attempt + 1)
    }

    private fun parseAnthropicSseStream(
        reader: BufferedReader,
        onDelta: (String) -> Unit
    ) {
        var line: String?
        var lineCount = 0
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            lineCount++
            if (lineCount <= 10) {
                Log.d("AiChat", "SSE line[$lineCount]: ${l.take(200)}")
            }
            if (!l.startsWith("data:")) continue
            val data = l.removePrefix("data:").trimStart()
            if (data == "[DONE]") break

            try {
                val event = JSONObject(data)
                val type = event.optString("type", "")
                if (type == "content_block_delta") {
                    val delta = event.optJSONObject("delta")
                    val text = delta?.optString("text", "") ?: ""
                    if (text.isNotEmpty()) {
                        onDelta(text)
                    }
                } else if (type == "message_stop") {
                    break
                }
            } catch (_: Exception) {
                // skip malformed events
            }
        }
        Log.d("AiChat", "SSE stream ended, total lines=$lineCount")
    }
}
