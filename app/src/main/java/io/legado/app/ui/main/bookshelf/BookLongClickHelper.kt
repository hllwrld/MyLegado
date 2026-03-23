package io.legado.app.ui.main.bookshelf

import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BookLongClickHelper {

    fun showPopupMenu(fragment: Fragment, anchor: View, book: Book) {
        val popupMenu = PopupMenu(fragment.requireContext(), anchor)
        popupMenu.inflate(R.menu.bookshelf_item_long_click)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_book_info -> {
                    fragment.startActivity<BookInfoActivity> {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                }
                R.id.menu_select_group -> {
                    showGroupSelectDialog(fragment, book)
                }
            }
            true
        }
        popupMenu.show()
    }

    private fun showGroupSelectDialog(fragment: Fragment, book: Book) {
        val context = fragment.requireContext()
        fragment.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                appDb.bookGroupDao.all.filter { it.groupId > 0 }
            }
            if (groups.isEmpty()) return@launch
            val groupNames = groups.map { it.groupName }.toTypedArray()
            val checkedItems = groups.map { book.group and it.groupId != 0L }.toBooleanArray()
            AlertDialog.Builder(context)
                .setTitle(R.string.group_select)
                .setMultiChoiceItems(groupNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    var newGroup = 0L
                    groups.forEachIndexed { index, bookGroup ->
                        if (checkedItems[index]) {
                            newGroup = newGroup or bookGroup.groupId
                        }
                    }
                    book.group = newGroup
                    book.groupTime = System.currentTimeMillis()
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        appDb.bookDao.update(book)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    fun showBatchPopupMenu(fragment: Fragment, anchor: View, books: List<Book>) {
        val popupMenu = PopupMenu(fragment.requireContext(), anchor)
        popupMenu.inflate(R.menu.bookshelf_batch_long_click)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_batch_group -> {
                    showBatchGroupSelectDialog(fragment, books)
                }
            }
            true
        }
        popupMenu.show()
    }

    private fun showBatchGroupSelectDialog(fragment: Fragment, books: List<Book>) {
        val context = fragment.requireContext()
        fragment.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                appDb.bookGroupDao.all.filter { it.groupId > 0 }
            }
            if (groups.isEmpty()) return@launch
            val groupNames = groups.map { it.groupName }.toTypedArray()
            // 初始状态：只有所有书都有该分组才勾选
            val checkedItems = groups.map { group ->
                books.all { it.group and group.groupId != 0L }
            }.toBooleanArray()
            AlertDialog.Builder(context)
                .setTitle(R.string.batch_group_select)
                .setMultiChoiceItems(groupNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    var newGroup = 0L
                    groups.forEachIndexed { index, bookGroup ->
                        if (checkedItems[index]) {
                            newGroup = newGroup or bookGroup.groupId
                        }
                    }
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        books.forEach {
                            it.group = newGroup
                            it.groupTime = System.currentTimeMillis()
                        }
                        appDb.bookDao.update(*books.toTypedArray())
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
