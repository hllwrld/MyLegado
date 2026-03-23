package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.model.remote.RemoteBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.defaultSharedPreferences
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    private val bookshelfMutex = Mutex()
    private val bookSourcesMutex = Mutex()

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
    suspend fun syncBookSources() { bookSourcesMutex.withLock {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        isSyncingBookSources = true
        try {
            // 同步删除记录
            val (_, deletedSources) = syncDeletions(authorization)

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
            val toDeleteLocal = mutableListOf<String>()

            // 4. 合并（考虑删除记录）
            for (key in allKeys) {
                val local = localMap[key]
                val remote = remoteMap[key]
                val isDeleted = deletedSources.containsKey(key)
                when {
                    local != null && remote == null -> {
                        if (isDeleted) {
                            toDeleteLocal.add(key)
                            AppLog.put("同步删除书源(来自其他设备): ${local.bookSourceName}")
                        } else {
                            mergedList.add(local)
                        }
                    }
                    local == null && remote != null -> {
                        if (isDeleted) {
                            AppLog.put("跳过已删除的远端书源: ${remote.bookSourceName}")
                        } else {
                            mergedList.add(remote)
                            toInsert.add(remote)
                        }
                    }
                    local != null && remote != null -> {
                        if (isDeleted) {
                            toDeleteLocal.add(key)
                            AppLog.put("同步删除书源(已标记删除): ${local.bookSourceName}")
                        } else {
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
            }

            // 删除本地已标记删除的书源
            if (toDeleteLocal.isNotEmpty()) {
                toDeleteLocal.forEach { appDb.bookSourceDao.delete(it) }
                AppLog.put("同步删除本地书源: ${toDeleteLocal.size}个")
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
            AppLog.put("书源同步完成，共${mergedList.size}个，新增/更新${toInsert.size}个，删除${toDeleteLocal.size}个")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("书源同步失败\n${e.localizedMessage}", e)
        } finally {
            isSyncingBookSources = false
        }
    } }

    /**
     * 增量同步书架
     * 按 bookUrl 合并:
     * - 进度: durChapterTime 晚的优先
     * - 分组: 上传时取并集，但不覆盖本地已有分组（本地优先）
     */
    suspend fun syncBookshelf() { bookshelfMutex.withLock {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        isSyncingBookshelf = true
        try {
            // 同步删除记录和分组定义
            val (deletedBooks, _) = syncDeletions(authorization)
            syncBookGroups(authorization)

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

            // 2. 获取本地书架（尽量晚读取，减少与用户操作的竞争窗口）
            val localBooks = appDb.bookDao.all

            // 3. 按 bookUrl 建立映射
            val remoteMap = remoteBooks.associateBy { it.bookUrl }
            val localMap = localBooks.associateBy { it.bookUrl }

            val allKeys = (remoteMap.keys + localMap.keys).toSet()
            val mergedList = mutableListOf<Book>()
            val toInsert = mutableListOf<Book>()
            val toUpdate = mutableListOf<Book>()
            val toDeleteLocal = mutableListOf<Book>()

            // 4. 合并（考虑删除记录）
            for (key in allKeys) {
                val local = localMap[key]
                val remote = remoteMap[key]
                val isDeleted = deletedBooks.containsKey(key)
                when {
                    local != null && remote == null -> {
                        if (isDeleted) {
                            // 本地有但已标记删除（其他设备删除的）→ 删除本地
                            toDeleteLocal.add(local)
                            AppLog.put("同步删除书籍(来自其他设备): ${local.name}")
                        } else {
                            mergedList.add(local)
                        }
                    }
                    local == null && remote != null -> {
                        if (isDeleted) {
                            // 远端有但已标记删除 → 不添加到本地，也不加入合并列表
                            AppLog.put("跳过已删除的远端书籍: ${remote.name}")
                        } else {
                            mergedList.add(remote)
                            toInsert.add(remote)
                        }
                    }
                    local != null && remote != null -> {
                        if (isDeleted) {
                            // 两端都有但已标记删除 → 删除本地，不加入合并列表
                            toDeleteLocal.add(local)
                            AppLog.put("同步删除书籍(已标记删除): ${local.name}")
                        } else {
                            var dbChanged = false
                            // 进度: 远端更新则覆盖本地
                            if (remote.durChapterTime > local.durChapterTime) {
                                local.durChapterIndex = remote.durChapterIndex
                                local.durChapterPos = remote.durChapterPos
                                local.durChapterTitle = remote.durChapterTitle
                                local.durChapterTime = remote.durChapterTime
                                local.syncTime = System.currentTimeMillis()
                                dbChanged = true
                            }
                            // 分组: 更新时间更晚的一方胜出
                            if (remote.groupTime > local.groupTime) {
                                local.group = remote.group
                                local.groupTime = remote.groupTime
                                dbChanged = true
                            } else if (local.groupTime > remote.groupTime) {
                                // 本地更新，保持不变
                            } else {
                                // 两边groupTime相同(旧数据均为0)，保持原有OR逻辑兼容过渡
                                val mergedGroup = local.group or remote.group
                                if (mergedGroup != local.group) {
                                    local.group = mergedGroup
                                    dbChanged = true
                                }
                            }
                            mergedList.add(local)
                            if (dbChanged) {
                                toUpdate.add(local)
                            }
                        }
                    }
                }
            }

            // 删除本地已标记删除的书籍
            if (toDeleteLocal.isNotEmpty()) {
                appDb.bookDao.delete(*toDeleteLocal.toTypedArray())
                AppLog.put("同步删除本地书籍: ${toDeleteLocal.size}本")
            }

            // 写入本地DB
            if (toInsert.isNotEmpty()) {
                appDb.bookDao.insert(*toInsert.toTypedArray())
            }
            if (toUpdate.isNotEmpty()) {
                appDb.bookDao.update(*toUpdate.toTypedArray())
            }

            // 确保书引用的分组在本地都存在
            ensureBookGroupsExist(mergedList)

            // 5. 上传合并后的完整列表
            val booksWithGroup = mergedList.filter { it.group != 0L }
            AppLog.put("有分组的书: ${booksWithGroup.joinToString { "${it.name}(group=${it.group})" }}")
            val json = GSON.toJson(mergedList)
            WebDav(remoteUrl, authorization).upload(
                json.toByteArray(), "application/json"
            )
            AppLog.put("书架同步完成，共${mergedList.size}本，新增${toInsert.size}本，更新${toUpdate.size}本")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("书架同步失败\n${e.localizedMessage}", e)
        } finally {
            isSyncingBookshelf = false
        }
    } }

    /**
     * 同步本地书籍文件到WebDAV（上传/下载）
     * 独立于 syncBookshelf() 运行，避免大文件传输阻塞书架元数据同步
     */
    suspend fun syncLocalBookFiles() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookWebDav = defaultBookWebDav
        if (bookWebDav == null) {
            AppLog.put("syncLocalBookFiles: defaultBookWebDav 为空，跳过文件同步")
            return
        }

        val allBooks = appDb.bookDao.all

        // 上传本地书文件到WebDAV（仅origin为loc_book的纯本地书）
        val booksToUpload = allBooks.filter {
            it.isLocal && it.origin == BookType.localTag && it.originName.isNotEmpty()
        }
        if (booksToUpload.isNotEmpty()) {
            AppLog.put("开始上传本地书文件: ${booksToUpload.size}本")
            appCtx.toastOnUi("正在上传本地书文件(${booksToUpload.size}本)…")
            var uploadCount = 0
            for (book in booksToUpload) {
                try {
                    bookWebDav.upload(book)
                    uploadCount++
                    AppLog.put("上传本地书文件成功(${uploadCount}/${booksToUpload.size}): ${book.name}(${book.originName})")
                    appCtx.toastOnUi("已上传: ${book.name} (${uploadCount}/${booksToUpload.size})")
                } catch (e: Exception) {
                    AppLog.put("上传本地书文件失败: ${book.name}(${book.originName}) bookUrl=${book.bookUrl} ${e.localizedMessage}", e)
                    appCtx.toastOnUi("上传失败: ${book.name}")
                }
            }
            if (uploadCount > 0) {
                appCtx.toastOnUi("本地书文件上传完成: ${uploadCount}/${booksToUpload.size}")
            }
        }

        // 下载远端同步过来的本地书文件（origin以webDav::开头但本地文件不存在）
        val booksToDownload = allBooks.filter {
            it.isLocal && it.getRemoteUrl() != null
        }.filter { book ->
            // 检查本地文件是否存在
            try {
                val uri = Uri.parse(book.bookUrl)
                if (uri.isContentScheme()) {
                    appCtx.contentResolver.openInputStream(uri)?.close()
                    false // 文件存在，不需要下载
                } else {
                    !java.io.File(uri.path!!).exists()
                }
            } catch (_: Exception) {
                true // 文件不存在或不可访问，需要下载
            }
        }
        if (booksToDownload.isNotEmpty()) {
            AppLog.put("开始下载远端书文件: ${booksToDownload.size}本")
            appCtx.toastOnUi("正在下载远端书文件(${booksToDownload.size}本)…")
            var downloadCount = 0
            for (book in booksToDownload) {
                try {
                    val remoteBookUrl = book.getRemoteUrl()!!
                    val remoteBook = bookWebDav.getRemoteBook(remoteBookUrl)
                    if (remoteBook != null) {
                        val localUri = downloadBookFile(bookWebDav, remoteBook)
                        book.bookUrl = if (localUri.isContentScheme()) localUri.toString() else localUri.path!!
                        book.save()
                        downloadCount++
                        AppLog.put("下载远端书文件成功(${downloadCount}/${booksToDownload.size}): ${book.name}(${book.originName})")
                        appCtx.toastOnUi("已下载: ${book.name} (${downloadCount}/${booksToDownload.size})")
                    } else {
                        AppLog.put("下载远端书文件: 远端文件不存在 ${book.name} url=$remoteBookUrl")
                    }
                } catch (e: Exception) {
                    AppLog.put("下载远端书文件失败: ${book.name} ${e.localizedMessage}", e)
                    appCtx.toastOnUi("下载失败: ${book.name}")
                }
            }
            if (downloadCount > 0) {
                appCtx.toastOnUi("远端书文件下载完成: ${downloadCount}/${booksToDownload.size}")
            }
        }
    }

    /**
     * 下载书籍文件，优先保存到用户设置的书籍目录，没设置则保存到应用内部存储
     */
    private suspend fun downloadBookFile(
        bookWebDav: RemoteBookWebDav,
        remoteBook: RemoteBook
    ): Uri {
        if (!NetworkUtils.isAvailable()) throw Exception("网络不可用")
        // 优先使用用户设置的书籍保存目录
        if (!AppConfig.defaultBookTreeUri.isNullOrBlank()) {
            return bookWebDav.downloadRemoteBook(remoteBook)
        }
        // 没有设置书籍保存目录，保存到应用内部存储
        val booksDir = File(appCtx.filesDir, "books")
        if (!booksDir.exists()) booksDir.mkdirs()
        val targetFile = File(booksDir, remoteBook.filename)
        val webdav = WebDav(remoteBook.path, bookWebDav.authorization)
        webdav.downloadInputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return Uri.fromFile(targetFile)
    }

    /**
     * 检查书籍引用的分组是否都存在，不存在则自动创建
     */
    private fun ensureBookGroupsExist(books: List<Book>) {
        val allGroupBits = books.fold(0L) { acc, book -> acc or book.group }
        if (allGroupBits == 0L) return
        val existingGroups = appDb.bookGroupDao.all.map { it.groupId }.toSet()
        var bit = 1L
        val toCreate = mutableListOf<BookGroup>()
        while (bit <= allGroupBits && bit > 0) {
            if (allGroupBits and bit != 0L && bit !in existingGroups) {
                toCreate.add(
                    BookGroup(
                        groupId = bit,
                        groupName = "同步分组$bit",
                        order = appDb.bookGroupDao.maxOrder + toCreate.size + 1
                    )
                )
            }
            bit = bit shl 1
        }
        if (toCreate.isNotEmpty()) {
            appDb.bookGroupDao.insert(*toCreate.toTypedArray())
            AppLog.put("自动创建缺失分组: ${toCreate.joinToString { "${it.groupName}(id=${it.groupId})" }}")
        }
    }

    /**
     * 同步书籍分组
     * 按 groupId 合并，本地和远端取并集
     */
    private suspend fun syncBookGroups(authorization: Authorization) {
        try {
            val remoteUrl = "${syncDataUrl}bookGroups.json"
            val remoteGroups: List<BookGroup> = try {
                val byteArray = WebDav(remoteUrl, authorization).download()
                val json = String(byteArray)
                if (json.isJsonArray()) {
                    GSON.fromJsonArray<BookGroup>(json).getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                AppLog.put("下载远端分组失败(可能是首次同步): ${e.localizedMessage}")
                emptyList()
            }

            val localGroups = appDb.bookGroupDao.all
            AppLog.put("分组同步: 本地${localGroups.size}个, 远端${remoteGroups.size}个")
            val remoteMap = remoteGroups.associateBy { it.groupId }
            val localMap = localGroups.associateBy { it.groupId }

            val allKeys = (remoteMap.keys + localMap.keys).toSet()
            val mergedList = mutableListOf<BookGroup>()
            val toInsert = mutableListOf<BookGroup>()

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
                        mergedList.add(local)
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                appDb.bookGroupDao.insert(*toInsert.toTypedArray())
            }

            val json = GSON.toJson(mergedList)
            WebDav(remoteUrl, authorization).upload(
                json.toByteArray(), "application/json"
            )
            AppLog.put("分组同步完成: 共${mergedList.size}个, 新增${toInsert.size}个")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("分组同步失败\n${e.localizedMessage}", e)
        }
    }

    private val autoSyncScope = MainScope()
    private var autoSyncBookshelfJob: Job? = null
    private var autoSyncBookSourcesJob: Job? = null
    private var isSyncingBookshelf = false
    private var isSyncingBookSources = false

    fun autoSyncBookshelf() {
        if (isSyncingBookshelf) return
        AppLog.put("触发自动同步书架（5秒后执行）")
        autoSyncBookshelfJob?.cancel()
        autoSyncBookshelfJob = autoSyncScope.launch {
            delay(5000)
            try {
                syncBookshelf()
            } catch (e: Exception) {
                AppLog.put("自动同步书架失败\n${e.localizedMessage}", e)
            }
        }
    }

    fun autoSyncBookSources() {
        if (isSyncingBookSources) return
        AppLog.put("触发自动同步书源（5秒后执行）")
        autoSyncBookSourcesJob?.cancel()
        autoSyncBookSourcesJob = autoSyncScope.launch {
            delay(5000)
            try {
                syncBookSources()
            } catch (e: Exception) {
                AppLog.put("自动同步书源失败\n${e.localizedMessage}", e)
            }
        }
    }

    // ============ 删除记录跟踪 ============

    private const val PREF_DELETED_BOOKS = "webdav_deleted_books"
    private const val PREF_DELETED_SOURCES = "webdav_deleted_sources"
    private const val DELETION_EXPIRE_DAYS = 30L

    /**
     * 记录书籍删除（在Book.delete()和批量删除时调用）
     */
    fun recordBookDeletion(bookUrl: String) {
        if (bookUrl.isBlank()) return
        val deletions = getLocalDeletedBooks().toMutableMap()
        deletions[bookUrl] = System.currentTimeMillis()
        saveLocalDeletedBooks(deletions)
        AppLog.put("记录书籍删除: $bookUrl")
    }

    /**
     * 记录书源删除
     */
    fun recordSourceDeletion(sourceUrl: String) {
        if (sourceUrl.isBlank()) return
        val deletions = getLocalDeletedSources().toMutableMap()
        deletions[sourceUrl] = System.currentTimeMillis()
        saveLocalDeletedSources(deletions)
        AppLog.put("记录书源删除: $sourceUrl")
    }

    /**
     * 当书籍重新加入书架时，移除删除记录
     */
    fun removeBookDeletion(bookUrl: String) {
        val deletions = getLocalDeletedBooks().toMutableMap()
        if (deletions.remove(bookUrl) != null) {
            saveLocalDeletedBooks(deletions)
            AppLog.put("移除书籍删除记录（重新加入书架）: $bookUrl")
        }
    }

    private fun getLocalDeletedBooks(): Map<String, Long> {
        val json = appCtx.defaultSharedPreferences.getString(PREF_DELETED_BOOKS, null)
            ?: return emptyMap()
        return try {
            GSON.fromJsonObject<Map<String, Long>>(json).getOrNull() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveLocalDeletedBooks(deletions: Map<String, Long>) {
        val cleaned = cleanExpiredDeletions(deletions)
        appCtx.defaultSharedPreferences.edit()
            .putString(PREF_DELETED_BOOKS, GSON.toJson(cleaned))
            .apply()
    }

    private fun getLocalDeletedSources(): Map<String, Long> {
        val json = appCtx.defaultSharedPreferences.getString(PREF_DELETED_SOURCES, null)
            ?: return emptyMap()
        return try {
            GSON.fromJsonObject<Map<String, Long>>(json).getOrNull() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveLocalDeletedSources(deletions: Map<String, Long>) {
        val cleaned = cleanExpiredDeletions(deletions)
        appCtx.defaultSharedPreferences.edit()
            .putString(PREF_DELETED_SOURCES, GSON.toJson(cleaned))
            .apply()
    }

    private fun cleanExpiredDeletions(deletions: Map<String, Long>): Map<String, Long> {
        val expireTime = System.currentTimeMillis() -
            java.util.concurrent.TimeUnit.DAYS.toMillis(DELETION_EXPIRE_DAYS)
        return deletions.filter { it.value > expireTime }
    }

    /**
     * 同步删除记录（在syncBookshelf/syncBookSources中调用）
     * 合并本地和远端的删除记录，返回合并后的集合
     */
    private suspend fun syncDeletions(
        authorization: Authorization
    ): Pair<Map<String, Long>, Map<String, Long>> {
        val remoteUrl = "${syncDataUrl}deletions.json"

        // 下载远端删除记录
        data class DeletionData(
            val books: Map<String, Long> = emptyMap(),
            val sources: Map<String, Long> = emptyMap()
        )

        val remoteDeletions: DeletionData = try {
            val byteArray = WebDav(remoteUrl, authorization).download()
            val json = String(byteArray)
            if (json.isJson()) {
                GSON.fromJsonObject<DeletionData>(json).getOrNull() ?: DeletionData()
            } else {
                DeletionData()
            }
        } catch (_: Exception) {
            DeletionData()
        }

        // 合并本地和远端删除记录（取较新的时间戳）
        val localDeletedBooks = getLocalDeletedBooks()
        val localDeletedSources = getLocalDeletedSources()

        val mergedBooks = (localDeletedBooks.keys + remoteDeletions.books.keys)
            .associateWith { key ->
                maxOf(
                    localDeletedBooks[key] ?: 0L,
                    remoteDeletions.books[key] ?: 0L
                )
            }.let { cleanExpiredDeletions(it) }

        val mergedSources = (localDeletedSources.keys + remoteDeletions.sources.keys)
            .associateWith { key ->
                maxOf(
                    localDeletedSources[key] ?: 0L,
                    remoteDeletions.sources[key] ?: 0L
                )
            }.let { cleanExpiredDeletions(it) }

        // 保存合并后的记录到本地
        saveLocalDeletedBooks(mergedBooks)
        saveLocalDeletedSources(mergedSources)

        // 上传合并后的记录到远端
        val uploadData = DeletionData(mergedBooks, mergedSources)
        val json = GSON.toJson(uploadData)
        WebDav(remoteUrl, authorization).upload(json.toByteArray(), "application/json")

        AppLog.put("删除记录同步: 书籍${mergedBooks.size}条, 书源${mergedSources.size}条")
        return Pair(mergedBooks, mergedSources)
    }

}