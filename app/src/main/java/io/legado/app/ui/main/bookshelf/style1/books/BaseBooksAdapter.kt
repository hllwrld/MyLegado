package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.view.View
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.viewbinding.ViewBinding
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book

abstract class BaseBooksAdapter<VB : ViewBinding>(context: Context) :
    DiffRecyclerAdapter<Book, VB>(context) {

    override val keepScrollPosition = true

    var inSelectMode = false
        private set
    val selectedBooks = HashSet<String>()

    fun enterSelectMode() {
        if (inSelectMode) return
        inSelectMode = true
        selectedBooks.clear()
        notifyItemRangeChanged(0, itemCount, bundleOf("selectMode" to true))
        (callBack as? CallBack)?.onSelectModeChanged(true)
    }

    fun exitSelectMode() {
        if (!inSelectMode) return
        inSelectMode = false
        selectedBooks.clear()
        notifyItemRangeChanged(0, itemCount, bundleOf("selectMode" to true))
        (callBack as? CallBack)?.onSelectModeChanged(false)
    }

    fun toggleSelection(book: Book) {
        val url = book.bookUrl
        if (selectedBooks.contains(url)) {
            selectedBooks.remove(url)
        } else {
            selectedBooks.add(url)
        }
        getItems().forEachIndexed { i, it ->
            if (it.bookUrl == url) {
                notifyItemChanged(i, bundleOf("selectMode" to true))
                return@forEachIndexed
            }
        }
        (callBack as? CallBack)?.onSelectionChanged(selectedBooks.size)
    }

    fun isSelected(book: Book): Boolean {
        return selectedBooks.contains(book.bookUrl)
    }

    fun getSelectedBooks(): List<Book> {
        return getItems().filter { selectedBooks.contains(it.bookUrl) }
    }

    private var callBack: Any? = null

    fun setCallBack(cb: CallBack) {
        callBack = cb
    }

    override val diffItemCallback: DiffUtil.ItemCallback<Book> =
        object : DiffUtil.ItemCallback<Book>() {

            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return when {
                    oldItem.durChapterTime != newItem.durChapterTime -> false
                    oldItem.name != newItem.name -> false
                    oldItem.author != newItem.author -> false
                    oldItem.durChapterTitle != newItem.durChapterTitle -> false
                    oldItem.latestChapterTitle != newItem.latestChapterTitle -> false
                    oldItem.lastCheckCount != newItem.lastCheckCount -> false
                    oldItem.getDisplayCover() != newItem.getDisplayCover() -> false
                    oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum() -> false
                    else -> true
                }
            }

            override fun getChangePayload(oldItem: Book, newItem: Book): Any? {
                val bundle = bundleOf()
                if (oldItem.name != newItem.name) {
                    bundle.putString("name", newItem.name)
                }
                if (oldItem.author != newItem.author) {
                    bundle.putString("author", newItem.author)
                }
                if (oldItem.durChapterTitle != newItem.durChapterTitle) {
                    bundle.putString("dur", newItem.durChapterTitle)
                }
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
                    bundle.putString("last", newItem.latestChapterTitle)
                }
                if (oldItem.getDisplayCover() != newItem.getDisplayCover()) {
                    bundle.putString("cover", newItem.getDisplayCover())
                }
                if (oldItem.lastCheckCount != newItem.lastCheckCount
                    || oldItem.durChapterTime != newItem.durChapterTime
                    || oldItem.getUnreadChapterNum() != newItem.getUnreadChapterNum()
                    || oldItem.lastCheckCount != newItem.lastCheckCount
                ) {
                    bundle.putBoolean("refresh", true)
                }
                if (oldItem.latestChapterTime != newItem.latestChapterTime) {
                    bundle.putBoolean("lastUpdateTime", true)
                }
                if (bundle.isEmpty) return null
                return bundle
            }

        }

    override fun onViewRecycled(holder: ItemViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
    }

    fun notification(bookUrl: String) {
        getItems().forEachIndexed { i, it ->
            if (it.bookUrl == bookUrl) {
                notifyItemChanged(i, bundleOf(Pair("refresh", null), Pair("lastUpdateTime", null)))
                return
            }
        }
    }

    fun upLastUpdateTime() {
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("lastUpdateTime", null)))
    }

    interface CallBack {
        fun open(book: Book)
        fun openBookInfo(view: View, book: Book)
        fun isUpdate(bookUrl: String): Boolean
        fun onSelectModeChanged(inSelectMode: Boolean) {}
        fun onSelectionChanged(count: Int) {}
    }
}
