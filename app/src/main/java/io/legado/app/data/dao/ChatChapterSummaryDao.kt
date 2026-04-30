package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ChatChapterSummary

@Dao
interface ChatChapterSummaryDao {

    @Query("select * from chat_chapter_summaries where bookUrl = :bookUrl order by chapterIndex asc")
    fun getByBookUrl(bookUrl: String): List<ChatChapterSummary>

    @Query("select * from chat_chapter_summaries where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    fun getByChapter(bookUrl: String, chapterIndex: Int): ChatChapterSummary?

    @Query("select * from chat_chapter_summaries where bookUrl = :bookUrl and keywords like '%' || :keyword || '%'")
    fun searchByKeyword(bookUrl: String, keyword: String): List<ChatChapterSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(summary: ChatChapterSummary)

    @Update
    fun update(summary: ChatChapterSummary)

    @Query("delete from chat_chapter_summaries where bookUrl = :bookUrl")
    fun deleteByBookUrl(bookUrl: String)
}
