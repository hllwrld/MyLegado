package io.legado.app.ui.book.chat

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAiChatBinding
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiChatActivity :
    VMBaseActivity<ActivityAiChatBinding, AiChatViewModel>() {

    companion object {
        const val BOOK_URL = "bookUrl"
    }

    override val binding by viewBinding(ActivityAiChatBinding::inflate)
    override val viewModel by viewModels<AiChatViewModel>()

    private lateinit var adapter: ChatMessageAdapter
    private var deleteMenuItem: MenuItem? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initInputBar()
        observeData()

        val bookUrl = intent.getStringExtra(BOOK_URL) ?: ""
        viewModel.initBook(bookUrl)

        onBackPressedDispatcher.addCallback(this) {
            when {
                adapter.isSelectionMode -> adapter.exitSelectionMode()
                viewModel.isLoading.value == true -> moveTaskToBack(true)
                else -> finish()
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ai_chat, menu)
        deleteMenuItem = menu.findItem(R.id.menu_delete_selected)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_clear -> {
                AlertDialog.Builder(this)
                    .setTitle("清空对话")
                    .setMessage("确定清空所有对话记录？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.clearConversation()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.menu_delete_selected -> {
                val selected = adapter.getSelectedMessages()
                if (selected.isEmpty()) return true
                AlertDialog.Builder(this)
                    .setTitle("删除消息")
                    .setMessage("确定删除选中的 ${selected.size} 条消息？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.deleteMessages(selected)
                        adapter.exitSelectionMode()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        adapter = ChatMessageAdapter(context = this) { inSelection, count ->
            deleteMenuItem?.isVisible = inSelection && count > 0
            if (inSelection) {
                binding.titleBar.title = "已选择 $count 条"
            } else {
                binding.titleBar.title = "AI 对话"
                deleteMenuItem?.isVisible = false
            }
        }
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvChat.layoutManager = layoutManager
        binding.rvChat.adapter = adapter
    }

    private fun initInputBar() {
        binding.ivSend.setOnClickListener { sendMessage() }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
        binding.ivTemplate.setOnClickListener {
            ChatTemplateDialog.show(supportFragmentManager) { template ->
                binding.etInput.setText(template.content)
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        binding.etInput.text?.clear()
        binding.etInput.hideSoftInput()
        viewModel.sendMessage(text)
    }

    private fun observeData() {
        viewModel.messagesLiveData.observe(this) { messages ->
            adapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.rvChat.scrollToPosition(messages.lastIndex)
                }
            }
        }
        viewModel.streamingContent.observe(this) { content ->
            adapter.streamingText = content
            val list = adapter.currentList
            if (list.isNotEmpty()) {
                binding.rvChat.scrollToPosition(list.lastIndex)
            }
        }
        viewModel.isLoading.observe(this) { loading ->
            binding.ivSend.isEnabled = !loading
            binding.ivSend.alpha = if (loading) 0.5f else 1.0f
        }
        viewModel.errorMessage.observe(this) { msg ->
            if (msg != null) {
                toastOnUi(msg)
            }
        }
    }
}
