package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookLongClickHelper
import io.legado.app.help.book.getLocalFileLastModified
import io.legado.app.help.book.getLocalFileSize
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout == 0) {
            BooksAdapterList(requireContext(), this)
        } else {
            BooksAdapterGrid(requireContext(), this)
        }
    }
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    private var searchKeyword: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books)
        }
        if (bookshelfLayout == 0) {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        } else {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout + 2)
        }
        binding.rvBookshelf.itemAnimator = null
        binding.rvBookshelf.adapter = booksAdapter
        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (positionStart == 0 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (toPosition == 0 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }
        })
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            booksAdapter.updateItems()
            binding.tvEmptyMsg.isGone = getItemCount() > 0
            binding.refreshLayout.isEnabled = enableRefresh && getItemCount() > 0
        }
    }

    override fun getCurrentBookGroup(): BookGroup? {
        return if (groupId == BookGroup.IdRoot) {
            // Root没有对应的BookGroup，返回"全部"分组
            appDb.bookGroupDao.getByID(BookGroup.IdAll)
        } else {
            appDb.bookGroupDao.getByID(groupId)
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    // 新排序：按大小
                    10 -> {
                        val sizeMap = list.associateWith { it.getLocalFileSize() }
                        list.sortedByDescending { sizeMap[it] ?: 0L }
                    }
                    11 -> {
                        val sizeMap = list.associateWith { it.getLocalFileSize() }
                        list.sortedBy { sizeMap[it] ?: 0L }
                    }
                    // 按阅读时间
                    12 -> list.sortedByDescending { it.durChapterTime }
                    13 -> list.sortedBy { it.durChapterTime }
                    // 按名称 (14=desc Z→A, 15=asc A→Z)
                    14 -> list.sortedWith { o1, o2 -> o2.name.cnCompare(o1.name) }
                    15 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
                    // 按导入时间: 分三层排列 addTime → 文件时间 → durChapterTime
                    16, 17 -> {
                        val asc = AppConfig.getBookSortByGroupId(groupId) == 17
                        val fileTimeMap = mutableMapOf<Book, Long>()
                        val tierAddTime = mutableListOf<Book>()
                        val tierFileTime = mutableListOf<Book>()
                        val tierFallback = mutableListOf<Book>()
                        for (book in list) {
                            when {
                                book.addTime > 0 -> tierAddTime.add(book)
                                else -> {
                                    val ft = book.getLocalFileLastModified()
                                    if (ft > 0) {
                                        fileTimeMap[book] = ft
                                        tierFileTime.add(book)
                                    } else {
                                        tierFallback.add(book)
                                    }
                                }
                            }
                        }
                        val sorted1 = if (asc) tierAddTime.sortedBy { it.addTime }
                            else tierAddTime.sortedByDescending { it.addTime }
                        val sorted2 = if (asc) tierFileTime.sortedBy { fileTimeMap[it] ?: 0L }
                            else tierFileTime.sortedByDescending { fileTimeMap[it] ?: 0L }
                        val sorted3 = if (asc) tierFallback.sortedBy { it.durChapterTime }
                            else tierFallback.sortedByDescending { it.durChapterTime }
                        sorted1 + sorted2 + sorted3
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.map { list ->
                val key = searchKeyword
                if (key.isNullOrBlank()) list
                else list.filter {
                    it.name.contains(key, true) || it.author.contains(key, true)
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                booksAdapter.updateItems()
                binding.tvEmptyMsg.isGone = getItemCount() > 0
                binding.refreshLayout.isEnabled = enableRefresh && getItemCount() > 0
                delay(100)
            }
        }
    }

    override fun back(): Boolean {
        if (super.back()) return true
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun onLocalSearchQueryChanged(query: String?) {
        searchKeyword = query
        initBooksData()
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(view: View, item: Any) {
        when (item) {
            is Book -> BookLongClickHelper.showPopupMenu(this, view, item)
            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    override fun getOtherGroupNames(book: Book): String? {
        val mask = if (groupId > 0L) {
            book.group and groupId.inv()
        } else {
            book.group
        }
        if (mask == 0L) return null
        val names = appDb.bookGroupDao.getGroupNames(mask)
        return names.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
        }
    }
}