package com.example.nbheditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ChatAdapter(
    private val onInsert: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<MainActivity.ChatMessage>()

    fun addMessage(message: MainActivity.ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (messages[position].role == "user") 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layout = if (viewType == 0) R.layout.item_chat_user else R.layout.item_chat_ai
        return ChatViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false), viewType, onInsert)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) = holder.bind(messages[position])

    override fun getItemCount() = messages.size

    class ChatViewHolder(
        itemView: View,
        private val viewType: Int,
        private val onInsert: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val insertBtn: MaterialButton? = itemView.findViewById(R.id.insertBtn)

        fun bind(message: MainActivity.ChatMessage) {
            messageText.text = message.content
            if (viewType == 1) {
                insertBtn?.setOnClickListener { onInsert(message.content) }
            }
        }
    }
}
