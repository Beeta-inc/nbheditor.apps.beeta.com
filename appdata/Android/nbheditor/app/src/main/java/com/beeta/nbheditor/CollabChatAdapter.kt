package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class CollabChatAdapter(
    private var messages: List<ChatMessage>,
    private val currentUserId: String,
    private val onMarkImportant: (ChatMessage) -> Unit = {},
    private val onCreateTask: (ChatMessage) -> Unit = {},
    private val onSetReminder: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<CollabChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvImportantBadge: TextView = view.findViewById(R.id.tvImportantBadge)
        val tvLinkedTaskBadge: TextView = view.findViewById(R.id.tvLinkedTaskBadge)
        val messageActions: LinearLayout = view.findViewById(R.id.messageActions)
        val btnMarkImportant: TextView = view.findViewById(R.id.btnMarkImportant)
        val btnCreateTask: TextView = view.findViewById(R.id.btnCreateTask)
        val btnSetReminder: TextView = view.findViewById(R.id.btnSetReminder)
        val ivMediaPreview: ImageView = view.findViewById(R.id.ivMediaPreview)
        val attachmentCard: LinearLayout = view.findViewById(R.id.attachmentCard)
        val tvAttachmentIcon: TextView = view.findViewById(R.id.tvAttachmentIcon)
        val tvAttachmentName: TextView = view.findViewById(R.id.tvAttachmentName)
        val mediaActionBar: LinearLayout = view.findViewById(R.id.mediaActionBar)
        val btnOpenMedia: TextView = view.findViewById(R.id.btnOpenMedia)
        val btnDownloadMedia: TextView = view.findViewById(R.id.btnDownloadMedia)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val isCurrentUser = message.userId == currentUserId

        // Sender name
        if (message.isAI) {
            holder.tvSenderName.text = "🤖 AI Assistant"
            holder.tvSenderName.setTextColor(0xFF4CAF50.toInt())
        } else {
            holder.tvSenderName.text = message.userName
            holder.tvSenderName.setTextColor(0xFF1976D2.toInt())
        }

        // Badges
        holder.tvImportantBadge.visibility = if (message.isImportant) View.VISIBLE else View.GONE
        holder.tvLinkedTaskBadge.visibility = if (message.linkedTaskId != null) View.VISIBLE else View.GONE

        // Timestamp
        holder.tvTimestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))

        // Bubble alignment + color
        val lp = holder.messageContainer.layoutParams as LinearLayout.LayoutParams
        if (isCurrentUser) {
            lp.gravity = Gravity.END
            holder.messageContainer.setBackgroundColor(0xFFE3F2FD.toInt())
        } else if (message.isAI) {
            lp.gravity = Gravity.START
            holder.messageContainer.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            lp.gravity = Gravity.START
            holder.messageContainer.setBackgroundColor(0xFFFFFFFF.toInt())
        }
        holder.messageContainer.layoutParams = lp

        // Media rendering
        val uri = message.attachmentUri
        val type = message.attachmentType
        if (!uri.isNullOrBlank() && !type.isNullOrBlank()) {
            holder.tvMessage.visibility = if (message.message.isNotBlank()) View.VISIBLE else View.GONE
            holder.tvMessage.text = message.message
            holder.mediaActionBar.visibility = View.VISIBLE

            when (type) {
                "image" -> {
                    holder.ivMediaPreview.visibility = View.VISIBLE
                    holder.attachmentCard.visibility = View.GONE
                    loadImagePreview(holder.ivMediaPreview, uri)
                }
                "audio" -> {
                    holder.ivMediaPreview.visibility = View.GONE
                    holder.attachmentCard.visibility = View.VISIBLE
                    holder.tvAttachmentIcon.text = "🎤"
                    holder.tvAttachmentName.text = uri.substringAfterLast("/")
                }
                "video" -> {
                    holder.ivMediaPreview.visibility = View.GONE
                    holder.attachmentCard.visibility = View.VISIBLE
                    holder.tvAttachmentIcon.text = "🎥"
                    holder.tvAttachmentName.text = uri.substringAfterLast("/")
                }
                "document" -> {
                    holder.ivMediaPreview.visibility = View.GONE
                    holder.attachmentCard.visibility = View.VISIBLE
                    holder.tvAttachmentIcon.text = docIcon(uri)
                    holder.tvAttachmentName.text = uri.substringAfterLast("/")
                }
                else -> {
                    holder.ivMediaPreview.visibility = View.GONE
                    holder.attachmentCard.visibility = View.GONE
                }
            }

            holder.btnOpenMedia.setOnClickListener { openMedia(it.context, uri, type) }
            holder.btnDownloadMedia.setOnClickListener { downloadMedia(it.context, uri, type) }
        } else {
            // Plain text message
            holder.tvMessage.visibility = View.VISIBLE
            holder.tvMessage.text = message.message
            holder.ivMediaPreview.visibility = View.GONE
            holder.attachmentCard.visibility = View.GONE
            holder.mediaActionBar.visibility = View.GONE
        }

        // Long-press actions
        holder.messageContainer.setOnLongClickListener {
            holder.messageActions.visibility =
                if (holder.messageActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        holder.btnMarkImportant.setOnClickListener { onMarkImportant(message); holder.messageActions.visibility = View.GONE }
        holder.btnCreateTask.setOnClickListener { onCreateTask(message); holder.messageActions.visibility = View.GONE }
        holder.btnSetReminder.setOnClickListener { onSetReminder(message); holder.messageActions.visibility = View.GONE }
    }

    override fun getItemCount() = messages.size

    fun updateMessages(newMessages: List<ChatMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadImagePreview(iv: ImageView, uriStr: String) {
        try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "content") {
                val stream = iv.context.contentResolver.openInputStream(uri) ?: return
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                iv.setImageBitmap(bmp)
            } else {
                // local file path
                val bmp = BitmapFactory.decodeFile(uriStr)
                iv.setImageBitmap(bmp)
            }
        } catch (_: Exception) {
            iv.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun openMedia(context: Context, uriStr: String, type: String) {
        try {
            val uri = Uri.parse(uriStr)
            val mime = when (type) {
                "image" -> "image/*"
                "video" -> "video/*"
                "audio" -> "audio/*"
                "document" -> mimeForDoc(uriStr)
                else -> "*/*"
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadMedia(context: Context, uriStr: String, type: String) {
        try {
            val uri = Uri.parse(uriStr)
            val filename = uriStr.substringAfterLast("/").ifBlank { "attachment_${System.currentTimeMillis()}" }
            val mime = when (type) {
                "image" -> "image/*"
                "video" -> "video/*"
                "audio" -> "audio/*"
                else -> mimeForDoc(uriStr)
            }
            val request = DownloadManager.Request(uri).apply {
                setTitle(filename)
                setDescription("Saving from NBH Chat")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setMimeType(mime)
                setAllowedOverMetered(true)
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "⬇ Saving to Downloads...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun docIcon(uri: String) = when {
        uri.endsWith(".pdf", true) -> "📕"
        uri.endsWith(".doc", true) || uri.endsWith(".docx", true) -> "📘"
        uri.endsWith(".xls", true) || uri.endsWith(".xlsx", true) -> "📗"
        uri.endsWith(".ppt", true) || uri.endsWith(".pptx", true) -> "📙"
        uri.endsWith(".zip", true) || uri.endsWith(".rar", true) -> "🗜"
        else -> "📄"
    }

    private fun mimeForDoc(uri: String) = when {
        uri.endsWith(".pdf", true) -> "application/pdf"
        uri.endsWith(".doc", true) -> "application/msword"
        uri.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        uri.endsWith(".xls", true) -> "application/vnd.ms-excel"
        uri.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        uri.endsWith(".ppt", true) -> "application/vnd.ms-powerpoint"
        uri.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }
}
