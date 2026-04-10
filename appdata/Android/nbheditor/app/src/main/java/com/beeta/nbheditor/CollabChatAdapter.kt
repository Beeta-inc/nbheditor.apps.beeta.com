package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class CollabChatAdapter(
    private var messages: List<ChatMessage>,
    private val currentUserId: String,
    private val onMarkImportant: (ChatMessage) -> Unit = {},
    private val onCreateTask: (ChatMessage) -> Unit = {},
    private val onSetReminder: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<CollabChatAdapter.MessageViewHolder>() {

    private val senderColors = listOf(
        0xFF4C6EF5.toInt(), 0xFF51CF66.toInt(), 0xFFFF8787.toInt(),
        0xFF9775FA.toInt(), 0xFFFFD43B.toInt(), 0xFF20C997.toInt(),
        0xFFFF6B6B.toInt(), 0xFF74C0FC.toInt()
    )

    private var photoMap: Map<String, String> = emptyMap()
    private val bitmapCache = LruCache<String, Bitmap>(20)

    fun updatePhotoMap(map: Map<String, String>) { photoMap = map; notifyDataSetChanged() }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSenderName: TextView = view.findViewById(R.id.tvSenderName)
        val avatarCard: android.widget.FrameLayout = view.findViewById(R.id.avatarCard)
        val avatarBg: View = view.findViewById(R.id.avatarBg)
        val tvAvatarInitial: TextView = view.findViewById(R.id.tvAvatarInitial)
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val avatarSpacer: View = view.findViewById(R.id.avatarSpacer)
        val messageContainer: LinearLayout = view.findViewById(R.id.messageContainer)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvReadTick: TextView = view.findViewById(R.id.tvReadTick)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        MessageViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false))

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val isMe = msg.userId == currentUserId
        val ctx = holder.itemView.context

        if (isMe) {
            // ── Outgoing ──────────────────────────────────────────────────────
            holder.tvSenderName.visibility = View.GONE
            holder.avatarCard.visibility = View.GONE
            // Spacer takes all remaining space, pushing bubble to the right
            holder.avatarSpacer.visibility = View.VISIBLE
            (holder.avatarSpacer.layoutParams as LinearLayout.LayoutParams).weight = 1f
            holder.messageContainer.background = ctx.getDrawable(R.drawable.bg_bubble_out)
            holder.tvReadTick.visibility = View.VISIBLE
            holder.tvMessage.setTextColor(0xFF1A1A1A.toInt())
        } else {
            // ── Incoming ──────────────────────────────────────────────────────
            holder.avatarSpacer.visibility = View.GONE
            holder.avatarCard.visibility = View.VISIBLE
            holder.tvReadTick.visibility = View.GONE
            holder.tvMessage.setTextColor(0xFF1A1A1A.toInt())

            val color: Int
            if (msg.isAI) {
                color = 0xFF4C6EF5.toInt()
                holder.messageContainer.background = ctx.getDrawable(R.drawable.bg_bubble_ai)
                holder.tvSenderName.visibility = View.VISIBLE
                holder.tvSenderName.text = "Beeta AI"
                holder.tvSenderName.setTextColor(color)
                // AI avatar: beetaai.png
                setAvatarBgColor(holder.avatarBg, color)
                holder.tvAvatarInitial.visibility = View.GONE
                holder.ivAvatar.visibility = View.VISIBLE
                val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.beetaai)
                holder.ivAvatar.setImageBitmap(circleCrop(bmp))
            } else {
                color = senderColors[Math.abs(msg.userId.hashCode()) % senderColors.size]
                holder.messageContainer.background = ctx.getDrawable(R.drawable.bg_bubble_in)
                holder.tvSenderName.visibility = View.VISIBLE
                holder.tvSenderName.text = msg.userName
                holder.tvSenderName.setTextColor(color)
                setAvatarBgColor(holder.avatarBg, color)
                val photoUrl = photoMap[msg.userId]
                if (!photoUrl.isNullOrBlank()) {
                    holder.tvAvatarInitial.visibility = View.INVISIBLE
                    holder.ivAvatar.visibility = View.VISIBLE
                    loadAvatar(ctx, photoUrl, holder)
                } else {
                    holder.ivAvatar.visibility = View.GONE
                    holder.tvAvatarInitial.visibility = View.VISIBLE
                    holder.tvAvatarInitial.text = msg.userName.firstOrNull()?.uppercase() ?: "?"
                }
            }
        }

        // ── Badges + timestamp ────────────────────────────────────────────────
        holder.tvImportantBadge.visibility = if (msg.isImportant) View.VISIBLE else View.GONE
        holder.tvLinkedTaskBadge.visibility = if (msg.linkedTaskId != null) View.VISIBLE else View.GONE
        holder.tvTimestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

        // ── Media ─────────────────────────────────────────────────────────────
        val uri = msg.attachmentUri
        val type = msg.attachmentType
        if (!uri.isNullOrBlank() && !type.isNullOrBlank()) {
            holder.tvMessage.visibility = if (msg.message.isNotBlank()) View.VISIBLE else View.GONE
            holder.tvMessage.text = msg.message
            holder.mediaActionBar.visibility = View.VISIBLE
            when (type) {
                "image" -> {
                    holder.ivMediaPreview.visibility = View.VISIBLE
                    holder.attachmentCard.visibility = View.GONE
                    loadImage(holder.ivMediaPreview, uri)
                }
                else -> {
                    holder.ivMediaPreview.visibility = View.GONE
                    holder.attachmentCard.visibility = View.VISIBLE
                    holder.tvAttachmentIcon.text = when (type) { "audio" -> "Mic"; "video" -> "Vid"; else -> docIcon(uri) }
                    holder.tvAttachmentName.text = uri.substringAfterLast("/")
                }
            }
            holder.btnOpenMedia.setOnClickListener { openMedia(ctx, uri, type) }
            holder.btnDownloadMedia.setOnClickListener { downloadMedia(ctx, uri, type) }
        } else {
            holder.tvMessage.visibility = View.VISIBLE
            holder.tvMessage.text = msg.message
            holder.ivMediaPreview.visibility = View.GONE
            holder.attachmentCard.visibility = View.GONE
            holder.mediaActionBar.visibility = View.GONE
        }

        // ── Long-press ────────────────────────────────────────────────────────
        holder.messageContainer.setOnLongClickListener {
            holder.messageActions.visibility =
                if (holder.messageActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        holder.btnMarkImportant.setOnClickListener { onMarkImportant(msg); holder.messageActions.visibility = View.GONE }
        holder.btnCreateTask.setOnClickListener { onCreateTask(msg); holder.messageActions.visibility = View.GONE }
        holder.btnSetReminder.setOnClickListener { onSetReminder(msg); holder.messageActions.visibility = View.GONE }
    }

    override fun getItemCount() = messages.size
    fun updateMessages(new: List<ChatMessage>) { messages = new; notifyDataSetChanged() }

    private fun setAvatarBgColor(view: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        view.background = d
    }

    private fun loadAvatar(ctx: Context, url: String, holder: MessageViewHolder) {
        val cached = bitmapCache.get(url)
        if (cached != null) {
            holder.ivAvatar.setImageBitmap(cached)
            holder.ivAvatar.visibility = View.VISIBLE
            holder.tvAvatarInitial.visibility = View.GONE
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val bmp = BitmapFactory.decodeStream(conn.getInputStream())
                val circle = circleCrop(bmp)
                bitmapCache.put(url, circle)
                withContext(Dispatchers.Main) {
                    holder.ivAvatar.setImageBitmap(circle)
                    holder.ivAvatar.visibility = View.VISIBLE
                    holder.tvAvatarInitial.visibility = View.GONE
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    holder.ivAvatar.visibility = View.GONE
                    holder.tvAvatarInitial.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun loadImage(iv: ImageView, uriStr: String) {
        try {
            val uri = Uri.parse(uriStr)
            val bmp = if (uri.scheme == "content")
                BitmapFactory.decodeStream(iv.context.contentResolver.openInputStream(uri))
            else BitmapFactory.decodeFile(uriStr)
            iv.setImageBitmap(bmp)
        } catch (_: Exception) { iv.setImageResource(android.R.drawable.ic_menu_gallery) }
    }

    private fun openMedia(ctx: Context, uriStr: String, type: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriStr), mimeFor(uriStr, type))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { Toast.makeText(ctx, "No app to open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun downloadMedia(ctx: Context, uriStr: String, type: String) {
        try {
            val name = uriStr.substringAfterLast("/").ifBlank { "attachment_${System.currentTimeMillis()}" }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse(uriStr)).apply {
                    setTitle(name)
                    setDescription("Saving from NBH Chat")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    setMimeType(mimeFor(uriStr, type))
                    setAllowedOverMetered(true)
                }
            )
            Toast.makeText(ctx, "Saving to Downloads...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun docIcon(uri: String) = when {
        uri.endsWith(".pdf", true) -> "PDF"
        uri.endsWith(".doc", true) || uri.endsWith(".docx", true) -> "DOC"
        uri.endsWith(".xls", true) || uri.endsWith(".xlsx", true) -> "XLS"
        uri.endsWith(".ppt", true) || uri.endsWith(".pptx", true) -> "PPT"
        else -> "FILE"
    }

    private fun mimeFor(uri: String, type: String) = when (type) {
        "image" -> "image/*"
        "video" -> "video/*"
        "audio" -> "audio/*"
        else -> when {
            uri.endsWith(".pdf", true) -> "application/pdf"
            uri.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            uri.endsWith(".doc", true) -> "application/msword"
            uri.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            uri.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "*/*"
        }
    }
}
