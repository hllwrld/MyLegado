package io.legado.app.help.llm

import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class OpenAiCompatProvider : LlmApiProvider {

    override fun streamMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): Flow<String> = callbackFlow {
        val requestBody = buildRequestBody(messages, systemPrompt, config, stream = true)
        val request = buildRequest(requestBody, config)

        val call = okHttpClient.newCall(request)
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                close(Exception("API error ${response.code}: $errorBody"))
                return@callbackFlow
            }

            val reader = response.body?.byteStream()?.bufferedReader()
            if (reader == null) {
                close(Exception("Empty response body"))
                return@callbackFlow
            }

            withContext(Dispatchers.IO) {
                parseOpenAiSseStream(reader) { delta ->
                    trySend(delta)
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { call.cancel() }
    }

    override suspend fun sendMessage(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        config: LlmConfig
    ): String = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(messages, systemPrompt, config, stream = false)
        val request = buildRequest(requestBody, config)

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("API error ${response.code}: $errorBody")
        }

        val json = JSONObject(response.body!!.string())
        val choices = json.getJSONArray("choices")
        if (choices.length() > 0) {
            choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } else {
            ""
        }
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

        val messagesArray = JSONArray()

        if (!systemPrompt.isNullOrBlank()) {
            val sysMsg = JSONObject()
            sysMsg.put("role", "system")
            sysMsg.put("content", systemPrompt)
            messagesArray.put(sysMsg)
        }

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
        val url = "$baseUrl/v1/chat/completions"

        return Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("content-type", "application/json")
            .addHeader("accept", "text/event-stream")
            .build()
    }

    private fun parseOpenAiSseStream(
        reader: BufferedReader,
        onDelta: (String) -> Unit
    ) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line ?: continue
            if (!l.startsWith("data: ")) continue
            val data = l.removePrefix("data: ")
            if (data == "[DONE]") break

            try {
                val event = JSONObject(data)
                val choices = event.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    onDelta(content)
                }
            } catch (_: Exception) {
                // skip malformed events
            }
        }
    }
}
