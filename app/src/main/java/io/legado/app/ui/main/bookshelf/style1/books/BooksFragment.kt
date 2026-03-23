package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBooksBinding
import io.legado.app.help.book.getLocalFileLastModified
import io.legado.app.help.book.getLocalFileSize
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.bookshelf.BookLongClickHelper
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BooksFragment() : BaseFragment(R.layout.fragment_books),
    BaseBooksAdapter.CallBack {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBooksBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout == 0) {
            BooksAdapterList(requireContext(), this, this, viewLifecycleOwner.lifecycle)
        } else {
            BooksAdapterGrid(requireContext(), this)
        }
    }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true
    private var searchKeyword: String? = null
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            booksAdapter.exitSelectMode()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        initSelectMode()
        upRecyclerData()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSelectMode() {
        booksAdapter.setCallBack(this)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (isTouchOnBlank(e.x, e.y)) {
                        if (booksAdapter.inSelectMode) {
                            booksAdapter.exitSelectMode()
                        } else {
                            booksAdapter.enterSelectMode()
                        }
                    }
                }
            }
        )
        binding.rvBookshelf.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    /**
     * 判断触摸点是否在空白区域（item 之间的间距或最后一个 item 下方）
     */
    private fun isTouchOnBlank(x: Float, y: Float): Boolean {
        val rv = binding.rvBookshelf
        val child = rv.findChildViewUnder(x, y) ?: return true
        // 检查触摸点是否落在 item 的 decoration 间距内（即 item view 边界之外）
        val rect = Rect()
        child.getHitRect(rect)
        return !rect.contains(x.toInt(), y.toInt())
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(booksAdapter.getItems())
        }
        val spacingPx = resources.getDimensionPixelSize(R.dimen.bookshelf_item_spacing)
        if (bookshelfLayout == 0) {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
            binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
                ) {
                    outRect.bottom = spacingPx
                }
            })
        } else {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout + 2)
            binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
                ) {
                    outRect.set(spacingPx / 2, spacingPx / 2, spacingPx / 2, spacingPx / 2)
                }
            })
        }
        if (bookshelfLayout == 0) {
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksListRecycledViewPool)
        } else {
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksGridRecycledViewPool)
        }
        booksAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.rvBookshelf.adapter = booksAdapter
        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (positionStart == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (toPosition == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }
        })
        startLastUpdateTimeJob()
    }

    private fun upFastScrollerBar() {
        val showBookshelfFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showBookshelfFastScroller)
        if (showBookshelfFastScroller) {
            binding.rvBookshelf.scrollBarSize = 0
        } else {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    /**
     * 更新书籍列表信息
     */
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy { it.order }

                    // 综合排序 issue #3192
                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }
                    // 按作者排序
                    5 -> list.sortedWith { o1, o2 ->
                        o1.author.cnCompare(o2.author)
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
                        val asc = bookSort == 17
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

                    else -> list.sortedByDescending { it.durChapterTime }
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
                binding.tvEmptyMsg.isGone = list.isNotEmpty()
                binding.refreshLayout.isEnabled =
                            enableRefresh && list.isNotEmpty() && !booksAdapter.inSelectMode
                booksAdapter.setItems(list)
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || bookshelfLayout != 0) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    booksAdapter.upLastUpdateTime()
                    delay(30 * 1000)
                }
            }
        }
    }

    fun filterBooks(key: String?) {
        searchKeyword = key
        if (view != null) {
            upRecyclerData()
        }
    }

    fun getBooks(): List<Book> {
        return booksAdapter.getItems()
    }

    fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    fun getBooksCount(): Int {
        return booksAdapter.itemCount
    }

    override fun onDestroyView() {
        super.onDestroyView()
        /**
         * 将 RecyclerView 中的视图全部回收到 RecycledViewPool 中
         */
        binding.rvBookshelf.setItemViewCacheSize(0)
        binding.rvBookshelf.adapter = null
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openBookInfo(view: View, book: Book) {
        if (booksAdapter.inSelectMode) {
            val selectedBooks = booksAdapter.getSelectedBooks()
            if (selectedBooks.isEmpty()) {
                BookLongClickHelper.showPopupMenu(this, view, book)
            } else {
                BookLongClickHelper.showBatchPopupMenu(this, view, selectedBooks)
            }
        } else {
            BookLongClickHelper.showPopupMenu(this, view, book)
        }
    }

    override fun onSelectModeChanged(inSelectMode: Boolean) {
        backPressedCallback.isEnabled = inSelectMode
        binding.refreshLayout.isEnabled = enableRefresh && !inSelectMode
    }

    override fun onSelectionChanged(count: Int) {
        // 可扩展：显示选中数量等 UI 反馈
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

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}
