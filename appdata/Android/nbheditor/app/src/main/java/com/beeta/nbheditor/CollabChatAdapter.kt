package com.beeta.nbheditor

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class CollabChatAdapter(
    private var messages: List<ChatMessage>,
    private val currentUserId: String
) : RecyclerView.Adapter<CollabChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val isCurrentUser = message.userId == currentUserId
        
        // Set message content
        holder.tvMessage.text = message.message
        
        // Set sender name
        if (message.isAI) {
            holder.tvSenderName.text = "🤖 AI Assistant"
            holder.tvSenderName.setTextColor(0xFF4CAF50.toInt())
        } else {
            holder.tvSenderName.text = message.userName
            holder.tvSenderName.setTextColor(0xFF1976D2.toInt())
        }
        
        // Format timestamp
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.tvTimestamp.text = sdf.format(Date(message.timestamp))
        
        // Style based on sender
        val layoutParams = holder.messageContainer.layoutParams as LinearLayout.LayoutParams
        if (isCurrentUser) {
            // Current user - align right, blue background
            layoutParams.gravity = Gravity.END
            holder.messageContainer.setBackgroundColor(0xFFE3F2FD.toInt())
        } else if (message.isAI) {
            // AI - align left, green background
            layoutParams.gravity = Gravity.START
            holder.messageContainer.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            // Other users - align left, white background
            layoutParams.gravity = Gravity.START
            holder.messageContainer.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        holder.messageContainer.layoutParams = layoutParams
    }

    override fun getItemCount() = messages.size

    fun updateMessages(newMessages: List<ChatMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}
