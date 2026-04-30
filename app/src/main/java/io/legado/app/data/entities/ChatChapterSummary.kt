package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "chat_chapter_summaries",
    indices = [Index(value = ["bookUrl", "chapterIndex"], unique = true)]
)
data class ChatChapterSummary(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val summary: String = "",
    val keywords: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
