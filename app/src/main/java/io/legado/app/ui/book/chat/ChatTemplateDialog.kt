package io.legado.app.ui.book.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ChatTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatTemplateDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    companion object {
        private var callback: ((ChatTemplate) -> Unit)? = null

        fun show(fragmentManager: FragmentManager, onSelect: (ChatTemplate) -> Unit) {
            callback = onSelect
            ChatTemplateDialog().show(fragmentManager, "chatTemplate")
        }
    }

    private var templates: List<ChatTemplate> = emptyList()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val adapter = TemplateListAdapter { template ->
            callback?.invoke(template)
            callback = null
            dismissAllowingStateLoss()
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            appDb.chatTemplateDao.getAll().collectLatest { list ->
                templates = list
                adapter.setItems(list)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        callback = null
    }

    private inner class TemplateListAdapter(
        private val onItemClick: (ChatTemplate) -> Unit
    ) : RecyclerView.Adapter<TemplateListAdapter.VH>() {

        private var items: List<ChatTemplate> = emptyList()

        fun setItems(list: List<ChatTemplate>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < items.size) {
                val template = items[position]
                holder.textView.text = template.title.ifEmpty { template.content.take(30) }
                holder.itemView.setOnClickListener { onItemClick(template) }
                holder.itemView.setOnLongClickListener {
                    showDeleteDialog(template)
                    true
                }
            } else {
                holder.textView.text = "+ 新建模板"
                holder.itemView.setOnClickListener { showAddDialog() }
            }
        }

        inner class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }

    private fun showAddDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.ai_chat_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.save_as_template)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val content = editText.text.toString().trim()
                if (content.isNotEmpty()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            appDb.chatTemplateDao.insert(
                                ChatTemplate(title = content.take(20), content = content)
                            )
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(template: ChatTemplate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除模板")
            .setMessage("确定删除「${template.title}」？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        appDb.chatTemplateDao.delete(template)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
