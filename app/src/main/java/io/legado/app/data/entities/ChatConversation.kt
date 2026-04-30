package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_conversations",
    indices = [Index(value = ["bookUrl"])]
)
data class ChatConversation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val bookUrl: String = "",
    val bookName: String = "",
    val bookAuthor: String = "",
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
