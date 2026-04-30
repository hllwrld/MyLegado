package io.legado.app.help.llm

import android.util.Log
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ChatChapterSummary
import io.legado.app.help.book.BookHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 书籍内容处理策略 - 支持大文本 Map-Reduce
 *
 * 三种场景:
 * 1. 小书 (< contextLimit): 直接全文传入
 * 2. 大书-全局问题 (总结/概括): Map-Reduce, 先逐章摘要再汇总
 * 3. 大书-局部问题 (特定章节/情节): 定位相关章节传原文
 */
class BookContentStrategy(
    private val llmProvider: LlmApiProvider,
    private val config: LlmConfig
) {
    companion object {
        private const val TAG = "AiChat"
        private const val CONTEXT_CHAR_LIMIT = 80000
        private const val CHAPTER_SUMMARIZE_LIMIT = 12000
    }

    enum class QueryType {
        FULL_SUMMARY,
        SCENE_SEARCH,
        CHAPTER_SPECIFIC,
        GENERAL
    }

    /**
     * 根据用户问题获取相关上下文 - 核心入口
     * 对于小书直接全文传入，大书根据问题类型分策略处理
     */
    suspend fun getRelevantContext(
        book: Book,
        userQuestion: String,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapters.isEmpty()) {
            return@withContext "这是一本名为《${book.name}》的书，作者：${book.author}。暂无章节内容。"
        }

        val allContents = loadAllChapterContents(book, chapters)
        Log.d(TAG, "加载章节: ${allContents.size}/${chapters.size}, 总字符=${allContents.values.sumOf { it.length }}")

        if (allContents.isEmpty()) {
            return@withContext "这是一本名为《${book.name}》的书，作者：${book.author}。" +
                    "未能读取到章节内容，可能需要先打开书籍阅读以缓存内容。"
        }

        val totalChars = allContents.values.sumOf { it.length }

        if (totalChars <= CONTEXT_CHAR_LIMIT) {
            return@withContext buildFullContentPrompt(book, chapters, allContents)
        }

        val queryType = classifyQuery(userQuestion)
        Log.d(TAG, "大文本模式: totalChars=$totalChars, queryType=$queryType")

        when (queryType) {
            QueryType.FULL_SUMMARY -> handleFullSummary(book, chapters, allContents, onProgress)
            QueryType.SCENE_SEARCH -> handleSceneSearch(book, chapters, allContents, userQuestion, onProgress)
            QueryType.CHAPTER_SPECIFIC -> handleChapterSpecific(book, chapters, allContents, userQuestion)
            QueryType.GENERAL -> handleGeneral(book, chapters, allContents, userQuestion, onProgress)
        }
    }

    /**
     * 全文总结 - 分块 Map-Reduce
     * 把多章合并成大块(每块~50K字符)，一次API调用总结一整块
     * 最后汇总所有块摘要作为 context
     */
    private suspend fun handleFullSummary(
        book: Book,
        chapters: List<BookChapter>,
        allContents: Map<Int, String>,
        onProgress: ((String) -> Unit)?
    ): String {
        onProgress?.invoke("正在分析全书内容...")

        // 分块：每块不超过 CHUNK_SIZE 字符
        val chunkSize = 50000
        val chunks = mutableListOf<Pair<String, String>>() // chunkLabel, chunkContent
        var currentChunk = StringBuilder()
        var chunkStartTitle = ""
        var chunkEndTitle = ""

        for ((index, content) in allContents.entries.sortedBy { it.key }) {
            val title = chapters[index].title
            if (currentChunk.isEmpty()) chunkStartTitle = title

            if (currentChunk.length + content.length > chunkSize && currentChunk.isNotEmpty()) {
                chunks.add("$chunkStartTitle ~ $chunkEndTitle" to currentChunk.toString())
                currentChunk = StringBuilder()
                chunkStartTitle = title
            }
            currentChunk.appendLine("【$title】")
            currentChunk.appendLine(content)
            currentChunk.appendLine()
            chunkEndTitle = title
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add("$chunkStartTitle ~ $chunkEndTitle" to currentChunk.toString())
        }

        Log.d(TAG, "全文分为 ${chunks.size} 块进行摘要")
        onProgress?.invoke("全书分为 ${chunks.size} 部分，正在并行分析...")

        // Map: 并行处理，限制并发数为4
        val semaphore = Semaphore(4)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val chunkSummaries = coroutineScope {
            chunks.map { chunk ->
                async {
                    semaphore.withPermit {
                        val truncated = if (chunk.second.length > 55000) chunk.second.take(55000) else chunk.second
                        val prompt = "请用中文对以下书籍内容进行总结(500字以内)，保留关键情节、人物和事件：\n\n${truncated}"
                        val result = try {
                            val response = llmProvider.sendMessage(
                                messages = listOf(LlmMessage("user", prompt)),
                                systemPrompt = "你是一个书籍内容分析助手，请简洁准确地总结内容。",
                                config = config
                            )
                            chunk.first to response
                        } catch (e: Exception) {
                            Log.e(TAG, "块摘要失败: ${chunk.first}", e)
                            chunk.first to "(分析失败)"
                        }
                        val done = completed.incrementAndGet()
                        onProgress?.invoke("已完成 $done/${chunks.size} 部分...")
                        result
                    }
                }
            }.awaitAll()
        }

        // Reduce: 汇总所有块摘要
        val sb = StringBuilder()
        sb.appendLine("这是一本名为《${book.name}》的书，作者：${book.author}。")
        sb.appendLine("全书共 ${chapters.size} 章，以下是各部分的内容总结：")
        sb.appendLine()
        for ((label, summary) in chunkSummaries) {
            sb.appendLine("【$label】")
            sb.appendLine(summary)
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 场景搜索 - 先用摘要定位章节，再传相关章节原文
     */
    private suspend fun handleSceneSearch(
        book: Book,
        chapters: List<BookChapter>,
        allContents: Map<Int, String>,
        userQuestion: String,
        onProgress: ((String) -> Unit)?
    ): String {
        onProgress?.invoke("正在定位相关章节...")

        val keywords = extractKeywords(userQuestion)

        // 先在原文中关键词搜索
        val matchedIndices = mutableSetOf<Int>()
        for ((index, content) in allContents) {
            for (keyword in keywords) {
                if (content.contains(keyword, ignoreCase = true)) {
                    matchedIndices.add(index)
                    break
                }
            }
        }

        // 如果关键词没命中，用摘要做二次匹配
        if (matchedIndices.isEmpty()) {
            for ((index, content) in allContents) {
                val summary = getOrCreateChapterSummary(book, chapters[index], index, content)
                if (summary != null) {
                    for (keyword in keywords) {
                        if (summary.contains(keyword, ignoreCase = true)) {
                            matchedIndices.add(index)
                            break
                        }
                    }
                }
            }
        }

        // 如果还是没命中，取前后文较多的章节
        if (matchedIndices.isEmpty()) {
            matchedIndices.addAll(allContents.keys.take(5))
        }

        // 在 context limit 内尽量多传匹配章节的原文
        val sb = StringBuilder()
        sb.appendLine("这是一本名为《${book.name}》的书，作者：${book.author}。")
        sb.appendLine("以下是与问题相关的章节原文内容：")
        sb.appendLine()

        var totalLen = sb.length
        val sortedIndices = matchedIndices.sorted()
        for (index in sortedIndices) {
            val content = allContents[index] ?: continue
            val title = chapters[index].title
            val header = "【$title】\n"
            val chunkLen = header.length + content.length + 2

            if (totalLen + chunkLen > CONTEXT_CHAR_LIMIT) {
                val remaining = CONTEXT_CHAR_LIMIT - totalLen - header.length - 50
                if (remaining > 500) {
                    sb.appendLine(header)
                    sb.appendLine(content.take(remaining))
                    sb.appendLine("...(本章后续内容省略)")
                }
                break
            }
            sb.appendLine(header)
            sb.appendLine(content)
            sb.appendLine()
            totalLen += chunkLen
        }

        return sb.toString()
    }

    /**
     * 按章节提问 - 直接传目标章节原文
     */
    private suspend fun handleChapterSpecific(
        book: Book,
        chapters: List<BookChapter>,
        allContents: Map<Int, String>,
        userQuestion: String
    ): String {
        val targetIndex = findTargetChapter(chapters, userQuestion)

        val sb = StringBuilder()
        sb.appendLine("这是一本名为《${book.name}》的书，作者：${book.author}。")

        if (targetIndex != null) {
            val content = allContents[targetIndex]
            if (content != null) {
                sb.appendLine("以下是【${chapters[targetIndex].title}】的完整内容：")
                sb.appendLine()
                sb.appendLine(content)
            } else {
                sb.appendLine("未能读取到【${chapters[targetIndex].title}】的内容。")
            }
        } else {
            // 没精确命中，传章节列表让LLM辅助
            sb.appendLine("全书章节列表：")
            for ((i, ch) in chapters.withIndex()) {
                sb.appendLine("${i + 1}. ${ch.title}")
            }
        }

        return sb.toString()
    }

    /**
     * 一般性问题 - 关键词匹配相关章节原文 + 其余章节摘要
     */
    private suspend fun handleGeneral(
        book: Book,
        chapters: List<BookChapter>,
        allContents: Map<Int, String>,
        userQuestion: String,
        onProgress: ((String) -> Unit)?
    ): String {
        val keywords = extractKeywords(userQuestion)

        // 按关键词匹配度排序
        val scored = allContents.map { (index, content) ->
            val score = keywords.count { kw -> content.contains(kw, ignoreCase = true) }
            index to score
        }.sortedByDescending { it.second }

        val sb = StringBuilder()
        sb.appendLine("这是一本名为《${book.name}》的书，作者：${book.author}。")
        sb.appendLine()

        var totalLen = sb.length
        var fullContentCount = 0

        // 高匹配度的传原文
        for ((index, score) in scored) {
            if (score == 0 && fullContentCount > 0) break
            val content = allContents[index] ?: continue
            val title = chapters[index].title
            val header = "【$title】(原文)\n"
            val chunkLen = header.length + content.length + 2

            if (totalLen + chunkLen > CONTEXT_CHAR_LIMIT * 0.7) break

            sb.appendLine(header)
            sb.appendLine(content)
            sb.appendLine()
            totalLen += chunkLen
            fullContentCount++
        }

        // 剩余空间填充其他章节：优先用已缓存摘要，无摘要则传章节标题
        if (totalLen < CONTEXT_CHAR_LIMIT * 0.9) {
            val usedIndices = scored.take(fullContentCount).map { it.first }.toSet()
            val remaining = allContents.keys.filter { it !in usedIndices }.sorted()

            if (remaining.isNotEmpty()) {
                sb.appendLine("--- 其他章节 ---")
                sb.appendLine()
                for (index in remaining) {
                    // 只用已缓存的摘要，不发API请求
                    val cached = appDb.chatChapterSummaryDao.getByChapter(book.bookUrl, index)
                    val line = if (cached != null) {
                        "【${chapters[index].title}】${cached.summary}\n"
                    } else {
                        "【${chapters[index].title}】\n"
                    }
                    if (totalLen + line.length > CONTEXT_CHAR_LIMIT) break
                    sb.append(line)
                    totalLen += line.length
                }
            }
        }

        return sb.toString()
    }

    // ============ 工具方法 ============

    private fun loadAllChapterContents(
        book: Book,
        chapters: List<BookChapter>
    ): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for ((index, chapter) in chapters.withIndex()) {
            val content = BookHelp.getContent(book, chapter)
            if (!content.isNullOrBlank()) {
                result[index] = content
            }
        }
        return result
    }

    private fun buildFullContentPrompt(
        book: Book,
        chapters: List<BookChapter>,
        allContents: Map<Int, String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("这是一本名为《${book.name}》的书，作者：${book.author}。")
        sb.appendLine("以下是书籍的完整内容：")
        sb.appendLine()
        for ((index, content) in allContents.entries.sortedBy { it.key }) {
            val title = chapters[index].title
            sb.appendLine("【$title】")
            sb.appendLine(content)
            sb.appendLine()
        }
        return sb.toString()
    }

    private suspend fun getOrCreateChapterSummary(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int,
        content: String
    ): String? {
        val existing = appDb.chatChapterSummaryDao.getByChapter(book.bookUrl, chapterIndex)
        if (existing != null) return existing.summary

        val truncated = if (content.length > CHAPTER_SUMMARIZE_LIMIT) {
            content.take(CHAPTER_SUMMARIZE_LIMIT)
        } else {
            content
        }

        val prompt = "请用中文对以下章节内容进行简要总结(300字以内)，并提取5-10个关键词(逗号分隔)。\n" +
                "格式：\n摘要：<摘要内容>\n关键词：<关键词1>,<关键词2>,...\n\n" +
                "章节标题：${chapter.title}\n内容：\n$truncated"

        return try {
            val response = llmProvider.sendMessage(
                messages = listOf(LlmMessage("user", prompt)),
                systemPrompt = null,
                config = config
            )

            val parsed = parseSummaryResponse(response)
            val entity = ChatChapterSummary(
                bookUrl = book.bookUrl,
                chapterIndex = chapterIndex,
                chapterTitle = chapter.title,
                summary = parsed.first,
                keywords = parsed.second
            )
            appDb.chatChapterSummaryDao.insert(entity)
            parsed.first
        } catch (e: Exception) {
            Log.e(TAG, "摘要生成失败: ${chapter.title}", e)
            null
        }
    }

    private fun classifyQuery(question: String): QueryType {
        val summaryKeywords = listOf("总结", "概括", "概述", "梗概", "大意", "讲了什么", "说了什么",
            "全文", "整本", "这本书", "主要内容", "故事线", "剧情")
        val sceneKeywords = listOf("哪里", "哪一", "找到", "场景", "情节", "片段", "在哪",
            "什么时候", "谁说了", "出现", "描写", "提到")
        val chapterKeywords = listOf("第.*章", "第.*节", "章节", "开头", "结尾", "最后一章", "第一章")

        for (kw in chapterKeywords) {
            if (Regex(kw).containsMatchIn(question)) return QueryType.CHAPTER_SPECIFIC
        }
        for (kw in summaryKeywords) {
            if (question.contains(kw)) return QueryType.FULL_SUMMARY
        }
        for (kw in sceneKeywords) {
            if (question.contains(kw)) return QueryType.SCENE_SEARCH
        }
        return QueryType.GENERAL
    }

    private fun findTargetChapter(chapters: List<BookChapter>, question: String): Int? {
        // 匹配 "第X章" 模式
        val numMatch = Regex("第([一二三四五六七八九十百千\\d]+)[章节]").find(question)
        if (numMatch != null) {
            val numStr = numMatch.groupValues[1]
            val num = chineseNumToInt(numStr) ?: numStr.toIntOrNull()
            if (num != null && num > 0 && num <= chapters.size) {
                return num - 1
            }
        }

        // 标题关键词匹配
        for ((i, ch) in chapters.withIndex()) {
            if (question.contains(ch.title) || ch.title.contains(question.take(10))) {
                return i
            }
        }

        // "最后一章" / "开头"
        if (question.contains("最后") || question.contains("结尾")) return chapters.lastIndex
        if (question.contains("开头") || question.contains("第一")) return 0

        return null
    }

    private fun chineseNumToInt(str: String): Int? {
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
            '零' to 0)
        if (str.length == 1) return map[str[0]]
        if (str.length == 2 && str[0] == '十') return 10 + (map[str[1]] ?: 0)
        if (str.length == 2 && str[1] == '十') return (map[str[0]] ?: 1) * 10
        if (str.length == 3 && str[1] == '十') {
            return (map[str[0]] ?: 0) * 10 + (map[str[2]] ?: 0)
        }
        return null
    }

    private fun extractKeywords(question: String): List<String> {
        val stopWords = setOf(
            "的", "了", "是", "在", "有", "和", "就", "不", "人", "都",
            "一", "这", "中", "大", "到", "说", "要", "为", "上", "个",
            "吗", "吧", "呢", "啊", "哦", "嗯", "请", "帮", "我", "你",
            "what", "is", "the", "a", "an", "how", "why", "who", "when",
            "where", "do", "does", "did", "can", "could", "will", "would"
        )
        return question
            .replace(Regex("""[，。？！、；：""''（）\[\]{},.?!;:'"()\s]+"""), " ")
            .split(" ")
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
            .take(8)
    }

    private fun parseSummaryResponse(response: String): Pair<String, String> {
        val summaryMatch = Regex("摘要[：:](.+?)(?=关键词|$)", RegexOption.DOT_MATCHES_ALL)
            .find(response)
        val keywordsMatch = Regex("关键词[：:](.+)")
            .find(response)

        val summary = summaryMatch?.groupValues?.get(1)?.trim() ?: response.take(300)
        val keywords = keywordsMatch?.groupValues?.get(1)?.trim() ?: ""

        return Pair(summary, keywords)
    }

    @Suppress("unused")
    suspend fun summarizeChaptersInBackground(
        book: Book,
        maxChapters: Int = 50
    ) = withContext(Dispatchers.IO) {
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        val toSummarize = chapters.take(maxChapters)

        for ((index, chapter) in toSummarize.withIndex()) {
            val existing = appDb.chatChapterSummaryDao.getByChapter(book.bookUrl, index)
            if (existing != null) continue
            val content = BookHelp.getContent(book, chapter) ?: continue
            if (content.isBlank()) continue
            getOrCreateChapterSummary(book, chapter, index, content)
        }
    }
}
