package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ChatConversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatConversationDao {

    @Query("select * from chat_conversations where bookUrl = :bookUrl order by updatedAt desc")
    fun getByBookUrl(bookUrl: String): Flow<List<ChatConversation>>

    @Query("select * from chat_conversations where bookUrl = :bookUrl order by updatedAt desc")
    fun getByBookUrlDirect(bookUrl: String): List<ChatConversation>

    @Query("select * from chat_conversations where id = :id")
    fun getById(id: String): ChatConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(conversation: ChatConversation)

    @Update
    fun update(conversation: ChatConversation)

    @Delete
    fun delete(conversation: ChatConversation)

    @Query("delete from chat_conversations where bookUrl = :bookUrl")
    fun deleteByBookUrl(bookUrl: String)
}
