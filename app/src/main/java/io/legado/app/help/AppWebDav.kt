package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private const val hardcodedAccount = "1726646194@qq.com"
    private const val hardcodedPassword = "aeew24y854k4htkj"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"
    private val syncDataUrl get() = "${rootWebDavUrl}syncData/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
                .takeUnless { it.isNullOrEmpty() } ?: hardcodedAccount
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
                .takeUnless { it.isNullOrEmpty() } ?: hardcodedPassword
            if (account.isNotEmpty() && password.isNotEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                WebDav(syncDataUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            Restore.restoreLocked(Backup.backupPath)
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath)
        }
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    /**
     * 增量同步书源
     * 按 bookSourceUrl 合并，冲突时 lastUpdateTime 晚的优先
     */
    suspend fun syncBookSources() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        try {
            // 1. 下载远端书源
            val remoteUrl = "${syncDataUrl}bookSource.json"
            val remoteSources: List<BookSource> = try {
                val byteArray = WebDav(remoteUrl, authorization).download()
                val json = String(byteArray)
                if (json.isJsonArray()) {
                    GSON.fromJsonArray<BookSource>(json).getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            // 2. 获取本地书源
            val localSources = appDb.bookSourceDao.all

            // 3. 按 bookSourceUrl 建立映射
            val remoteMap = remoteSources.associateBy { it.bookSourceUrl }
            val localMap = localSources.associateBy { it.bookSourceUrl }

            val allKeys = (remoteMap.keys + localMap.keys).toSet()
            val mergedList = mutableListOf<BookSource>()
            val toInsert = mutableListOf<BookSource>()

            // 4. 合并
            for (key in allKeys) {
                val local = localMap[key]
                val remote = remoteMap[key]
                when {
                    local != null && remote == null -> {
                        mergedList.add(local)
                    }
                    local == null && remote != null -> {
                        mergedList.add(remote)
                        toInsert.add(remote)
                    }
                    local != null && remote != null -> {
                        if (remote.lastUpdateTime > local.lastUpdateTime
                            && remote.lastUpdateTime > 0L
                        ) {
                            mergedList.add(remote)
                            toInsert.add(remote)
                        } else {
                            mergedList.add(local)
                        }
                    }
                }
            }

            // 写入本地DB
            if (toInsert.isNotEmpty()) {
                appDb.bookSourceDao.insert(*toInsert.toTypedArray())
            }

            // 5. 上传合并后的完整列表
            val json = GSON.toJson(mergedList)
            WebDav(remoteUrl, authorization).upload(
                json.toByteArray(), "application/json"
            )
            AppLog.put("书源同步完成，共${mergedList.size}个，新增/更新${toInsert.size}个")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("书源同步失败\n${e.localizedMessage}", e)
        }
    }

    /**
     * 增量同步书架
     * 按 bookUrl 合并，冲突时 durChapterTime 晚的优先（只更新进度字段）
     */
    suspend fun syncBookshelf() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        try {
            // 1. 下载远端书架
            val remoteUrl = "${syncDataUrl}bookshelf.json"
            val remoteBooks: List<Book> = try {
                val byteArray = WebDav(remoteUrl, authorization).download()
                val json = String(byteArray)
                if (json.isJsonArray()) {
                    GSON.fromJsonArray<Book>(json).getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }

            // 2. 获取本地书架
            val localBooks = appDb.bookDao.all

            // 3. 按 bookUrl 建立映射
            val remoteMap = remoteBooks.associateBy { it.bookUrl }
            val localMap = localBooks.associateBy { it.bookUrl }

            val allKeys = (remoteMap.keys + localMap.keys).toSet()
            val mergedList = mutableListOf<Book>()
            val toInsert = mutableListOf<Book>()
            val toUpdate = mutableListOf<Book>()

            // 4. 合并
            for (key in allKeys) {
                val local = localMap[key]
                val remote = remoteMap[key]
                when {
                    local != null && remote == null -> {
                        mergedList.add(local)
                    }
                    local == null && remote != null -> {
                        mergedList.add(remote)
                        toInsert.add(remote)
                    }
                    local != null && remote != null -> {
                        if (remote.durChapterTime > local.durChapterTime) {
                            local.durChapterIndex = remote.durChapterIndex
                            local.durChapterPos = remote.durChapterPos
                            local.durChapterTitle = remote.durChapterTitle
                            local.durChapterTime = remote.durChapterTime
                            local.syncTime = System.currentTimeMillis()
                            mergedList.add(local)
                            toUpdate.add(local)
                        } else {
                            mergedList.add(local)
                        }
                    }
                }
            }

            // 写入本地DB
            if (toInsert.isNotEmpty()) {
                appDb.bookDao.insert(*toInsert.toTypedArray())
            }
            if (toUpdate.isNotEmpty()) {
                appDb.bookDao.update(*toUpdate.toTypedArray())
            }

            // 5. 上传合并后的完整列表
            val json = GSON.toJson(mergedList)
            WebDav(remoteUrl, authorization).upload(
                json.toByteArray(), "application/json"
            )
            AppLog.put("书架同步完成，共${mergedList.size}本，新增${toInsert.size}本，更新${toUpdate.size}本")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("书架同步失败\n${e.localizedMessage}", e)
        }
    }

}