package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ChatTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatTemplateDao {

    @Query("select * from chat_templates order by sortOrder asc, createdAt desc")
    fun getAll(): Flow<List<ChatTemplate>>

    @Query("select * from chat_templates order by sortOrder asc, createdAt desc")
    fun getAllDirect(): List<ChatTemplate>

    @Query("select * from chat_templates where id = :id")
    fun getById(id: String): ChatTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(template: ChatTemplate)

    @Update
    fun update(template: ChatTemplate)

    @Delete
    fun delete(template: ChatTemplate)

    @Query("delete from chat_templates")
    fun clear()
}
