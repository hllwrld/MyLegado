package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query("select * from chat_messages where conversationId = :conversationId order by timestamp asc")
    fun getByConversation(conversationId: String): Flow<List<ChatMessage>>

    @Query("select * from chat_messages where conversationId = :conversationId order by timestamp asc")
    fun getByConversationDirect(conversationId: String): List<ChatMessage>

    @Query("select * from chat_messages where bookUrl = :bookUrl order by timestamp asc")
    fun getByBookUrl(bookUrl: String): List<ChatMessage>

    @Query("select * from chat_messages where id = :id")
    fun getById(id: String): ChatMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: ChatMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg messages: ChatMessage)

    @Update
    fun update(message: ChatMessage)

    @Delete
    fun delete(message: ChatMessage)

    @Query("delete from chat_messages where conversationId = :conversationId")
    fun deleteByConversation(conversationId: String)
}
