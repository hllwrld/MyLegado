package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["bookUrl"])]
)
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String = "",
    val bookUrl: String = "",
    val role: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isComplete: Boolean = true
)
