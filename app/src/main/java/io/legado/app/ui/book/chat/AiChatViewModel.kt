package io.legado.app.ui.book.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ChatConversation
import io.legado.app.data.entities.ChatMessage
import io.legado.app.help.llm.BookContentStrategy
import io.legado.app.help.llm.LlmApiProvider
import io.legado.app.help.llm.LlmConfig
import io.legado.app.help.llm.LlmMessage
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.flowOn

class AiChatViewModel(application: Application) : BaseViewModel(application) {

    var bookUrl: String = ""
    var book: Book? = null
    val messagesLiveData = MutableLiveData<List<ChatMessage>>(emptyList())
    val streamingContent = MutableLiveData<String>()
    val isLoading = MutableLiveData(false)
    val errorMessage = MutableLiveData<String?>()

    private var conversation: ChatConversation? = null
    private val config = LlmConfig()
    private val provider: LlmApiProvider = LlmApiProvider.create(config)
    private val contentStrategy = BookContentStrategy(provider, config)

    fun initBook(bookUrl: String) {
        this.bookUrl = bookUrl
        Log.d("AiChat", "initBook: $bookUrl")
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            Log.d("AiChat", "book loaded: ${book?.name}")
            val conversations = appDb.chatConversationDao.getByBookUrlDirect(bookUrl)
            conversation = conversations.firstOrNull() ?: createConversation()
            Log.d("AiChat", "conversation ready: ${conversation?.id}")
            // 修复进程被kill后遗留的未完成消息
            fixIncompleteMessages()
            loadMessages()
        }.onError {
            Log.e("AiChat", "initBook failed", it)
        }
    }

    private fun fixIncompleteMessages() {
        val convId = conversation?.id ?: return
        val messages = appDb.chatMessageDao.getByConversationDirect(convId)
        for (msg in messages) {
            if (!msg.isComplete) {
                val fixed = msg.copy(
                    content = if (msg.content.isBlank()) "请求未完成" else msg.content,
                    isComplete = true
                )
                appDb.chatMessageDao.update(fixed)
            }
        }
    }

    private fun createConversation(): ChatConversation {
        val conv = ChatConversation(
            bookUrl = bookUrl,
            bookName = book?.name ?: "",
            bookAuthor = book?.author ?: ""
        )
        appDb.chatConversationDao.insert(conv)
        return conv
    }

    private fun loadMessages() {
        val convId = conversation?.id ?: return
        val messages = appDb.chatMessageDao.getByConversationDirect(convId)
        messagesLiveData.postValue(messages)
    }

    fun sendMessage(content: String) {
        Log.d("AiChat", "sendMessage called: content='${content.take(20)}', isLoading=${isLoading.value}, conversation=${conversation?.id}")
        if (content.isBlank() || isLoading.value == true) return

        val convId = conversation?.id ?: run {
            Log.e("AiChat", "conversation is null, cannot send")
            return
        }
        Log.d("AiChat", "开始发送, convId=$convId")
        isLoading.postValue(true)
        errorMessage.postValue(null)

        val userMessage = ChatMessage(
            conversationId = convId,
            bookUrl = bookUrl,
            role = "user",
            content = content
        )

        execute {
            appDb.chatMessageDao.insert(userMessage)
            val currentMessages = appDb.chatMessageDao.getByConversationDirect(convId)
            messagesLiveData.postValue(currentMessages)

            val aiMessage = ChatMessage(
                conversationId = convId,
                bookUrl = bookUrl,
                role = "assistant",
                content = "",
                isComplete = false
            )
            appDb.chatMessageDao.insert(aiMessage)
            messagesLiveData.postValue(currentMessages + aiMessage)

            val bookContext = book?.let {
                contentStrategy.getRelevantContext(it, content) { progress ->
                    streamingContent.postValue(progress)
                }
            } ?: ""
            Log.d("AiChat", "bookContext长度=${bookContext.length}, 前100字=${bookContext.take(100)}")
            val systemPrompt = buildSystemPrompt(bookContext)

            val historyMessages = currentMessages.map {
                LlmMessage(role = it.role, content = it.content)
            }

            val fullContent = StringBuilder()
            streamingContent.postValue("")

            Log.d("AiChat", "开始请求LLM: url=${config.baseUrl}, model=${config.model}")
            try {
                provider.streamMessage(historyMessages, systemPrompt, config)
                    .flowOn(IO)
                    .collect { delta ->
                        Log.d("AiChat", "收到delta: ${delta.take(20)}")
                        fullContent.append(delta)
                        streamingContent.postValue(fullContent.toString())
                    }
                Log.d("AiChat", "流式响应完成, 总长度=${fullContent.length}")
            } catch (e: Exception) {
                Log.e("AiChat", "请求失败", e)
                AppLog.put("AI对话请求失败: ${e.message}", e)
                val errorText = "请求失败: ${e.message}"
                errorMessage.postValue(errorText)
                val failedMsg = aiMessage.copy(
                    content = errorText,
                    isComplete = true
                )
                appDb.chatMessageDao.update(failedMsg)
                loadMessages()
                isLoading.postValue(false)
                return@execute
            }

            val completedMsg = aiMessage.copy(
                content = fullContent.toString(),
                isComplete = true
            )
            appDb.chatMessageDao.update(completedMsg)
            conversation?.let { conv ->
                appDb.chatConversationDao.update(
                    conv.copy(updatedAt = System.currentTimeMillis())
                )
            }
            isLoading.postValue(false)
            loadMessages()
        }.onError {
            Log.e("AiChat", "execute onError", it)
            AppLog.put("AI对话执行异常: ${it.message}", it)
            isLoading.postValue(false)
            errorMessage.postValue(it.message ?: "未知错误")
        }
    }

    fun deleteMessage(message: ChatMessage) {
        execute {
            appDb.chatMessageDao.delete(message)
            loadMessages()
        }
    }

    fun deleteMessages(messages: List<ChatMessage>) {
        execute {
            for (msg in messages) {
                appDb.chatMessageDao.delete(msg)
            }
            loadMessages()
        }
    }

    fun clearConversation() {
        execute {
            conversation?.let { conv ->
                appDb.chatMessageDao.deleteByConversation(conv.id)
                loadMessages()
            }
        }
    }

    private fun buildSystemPrompt(bookContext: String): String {
        val sb = StringBuilder()
        sb.appendLine("你是一个智能阅读助手，帮助用户理解和讨论书籍内容。")
        sb.appendLine("请用中文回答用户的问题。回答要准确、简洁、有帮助。")
        if (bookContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("以下是这本书的相关信息：")
            sb.appendLine(bookContext)
        }
        return sb.toString()
    }
}
