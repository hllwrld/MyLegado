package io.legado.app.ui.book.chat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.ChatMessage
import io.legado.app.databinding.ItemChatMessageAiBinding
import io.legado.app.databinding.ItemChatMessageUserBinding

class ChatMessageAdapter(
    private val context: Context,
    private val onSelectionChanged: ((Boolean, Int) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatDiffCallback()) {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
    }

    var isSelectionMode = false
        private set
    private val selectedIds = mutableSetOf<String>()

    var streamingText: String? = null
        set(value) {
            field = value
            val list = currentList
            if (list.isNotEmpty()) {
                val lastIndex = list.lastIndex
                val last = list[lastIndex]
                if (last.role == "assistant" && !last.isComplete) {
                    notifyItemChanged(lastIndex)
                }
            }
        }

    fun enterSelectionMode() {
        if (isSelectionMode) return
        isSelectionMode = true
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!isSelectionMode) return
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(false, 0)
    }

    fun getSelectedMessages(): List<ChatMessage> {
        return currentList.filter { it.id in selectedIds }
    }

    fun getSelectedCount(): Int = selectedIds.size

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") TYPE_USER else TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return when (viewType) {
            TYPE_USER -> {
                val binding = ItemChatMessageUserBinding.inflate(inflater, parent, false)
                UserViewHolder(binding)
            }
            else -> {
                val binding = ItemChatMessageAiBinding.inflate(inflater, parent, false)
                AiViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AiViewHolder -> holder.bind(item)
        }

        val checkbox = when (holder) {
            is UserViewHolder -> holder.binding.cbSelect
            is AiViewHolder -> holder.binding.cbSelect
            else -> null
        }

        checkbox?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        checkbox?.isChecked = item.id in selectedIds
        checkbox?.setOnCheckedChangeListener(null)
        checkbox?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(item.id) else selectedIds.remove(item.id)
            onSelectionChanged?.invoke(true, selectedIds.size)
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                enterSelectionMode()
                selectedIds.add(item.id)
                notifyDataSetChanged()
                onSelectionChanged?.invoke(true, selectedIds.size)
            }
            true
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                val newState = item.id !in selectedIds
                if (newState) selectedIds.add(item.id) else selectedIds.remove(item.id)
                checkbox?.isChecked = newState
                onSelectionChanged?.invoke(true, selectedIds.size)
            }
        }
    }

    inner class UserViewHolder(val binding: ItemChatMessageUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvContent.text = message.content
        }
    }

    inner class AiViewHolder(val binding: ItemChatMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            if (!message.isComplete && streamingText != null) {
                binding.tvContent.text = streamingText
            } else {
                binding.tvContent.text = message.content.ifEmpty { "..." }
            }
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
