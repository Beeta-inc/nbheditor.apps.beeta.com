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
import android.media.MediaMetadataRetriever
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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_INCOMING = 0
        private const val TYPE_OUTGOING = 1
    }

    private val senderColors = listOf(
        0xFF4C6EF5.toInt(), 0xFF51CF66.toInt(), 0xFFFF8787.toInt(),
        0xFF9775FA.toInt(), 0xFFFFD43B.toInt(), 0xFF20C997.toInt(),
        0xFFFF6B6B.toInt(), 0xFF74C0FC.toInt()
    )

    private var photoMap: Map<String, String> = emptyMap()
    private val bitmapCache = LruCache<String, Bitmap>(20)

    private var beetaAiBitmap: Bitmap? = null

    private fun getAiBitmap(ctx: Context): Bitmap? {
        if (beetaAiBitmap != null) return beetaAiBitmap
        return try {
            val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.beetaai)
            if (bmp != null) { beetaAiBitmap = circleCrop(bmp) }
            beetaAiBitmap
        } catch (_: Exception) { null }
    }

    fun updatePhotoMap(map: Map<String, String>) { photoMap = map; notifyDataSetChanged() }
    fun updateMessages(new: List<ChatMessage>) { messages = new; notifyDataSetChanged() }
    override fun getItemCount() = messages.size
    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        // AI messages always incoming regardless of userId
        if (msg.isAI || msg.userId == "__ai__") return TYPE_INCOMING
        return if (msg.userId == currentUserId) TYPE_OUTGOING else TYPE_INCOMING
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    class IncomingVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSenderName: TextView = v.findViewById(R.id.tvSenderName)
        val avatarCard: android.widget.FrameLayout = v.findViewById(R.id.avatarCard)
        val avatarBg: View = v.findViewById(R.id.avatarBg)
        val tvAvatarInitial: TextView = v.findViewById(R.id.tvAvatarInitial)
        val ivAvatar: ImageView = v.findViewById(R.id.ivAvatar)
        val messageContainer: LinearLayout = v.findViewById(R.id.messageContainer)
        val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        val tvTimestamp: TextView = v.findViewById(R.id.tvTimestamp)
        val tvReadTick: TextView = v.findViewById(R.id.tvReadTick)
        val tvImportantBadge: TextView = v.findViewById(R.id.tvImportantBadge)
        val tvLinkedTaskBadge: TextView = v.findViewById(R.id.tvLinkedTaskBadge)
        val messageActions: LinearLayout = v.findViewById(R.id.messageActions)
        val btnMarkImportant: TextView = v.findViewById(R.id.btnMarkImportant)
        val btnCreateTask: TextView = v.findViewById(R.id.btnCreateTask)
        val btnSetReminder: TextView = v.findViewById(R.id.btnSetReminder)
        val ivMediaPreview: ImageView = v.findViewById(R.id.ivMediaPreview)
        val attachmentCard: LinearLayout = v.findViewById(R.id.attachmentCard)
        val tvAttachmentIcon: TextView = v.findViewById(R.id.tvAttachmentIcon)
        val tvAttachmentName: TextView = v.findViewById(R.id.tvAttachmentName)
        val mediaActionBar: LinearLayout = v.findViewById(R.id.mediaActionBar)
        val btnOpenMedia: TextView = v.findViewById(R.id.btnOpenMedia)
        val btnDownloadMedia: TextView = v.findViewById(R.id.btnDownloadMedia)
    }

    class OutgoingVH(v: View) : RecyclerView.ViewHolder(v) {
        val messageContainer: LinearLayout = v.findViewById(R.id.messageContainerOut)
        val tvMessage: TextView = v.findViewById(R.id.tvMessageOut)
        val tvTimestamp: TextView = v.findViewById(R.id.tvTimestampOut)
        val messageActions: LinearLayout = v.findViewById(R.id.messageActionsOut)
        val btnMarkImportant: TextView = v.findViewById(R.id.btnMarkImportantOut)
        val btnCreateTask: TextView = v.findViewById(R.id.btnCreateTaskOut)
        val btnSetReminder: TextView = v.findViewById(R.id.btnSetReminderOut)
        val ivMediaPreview: ImageView = v.findViewById(R.id.ivMediaPreviewOut)
        val attachmentCard: LinearLayout = v.findViewById(R.id.attachmentCardOut)
        val tvAttachmentIcon: TextView = v.findViewById(R.id.tvAttachmentIconOut)
        val tvAttachmentName: TextView = v.findViewById(R.id.tvAttachmentNameOut)
        val mediaActionBar: LinearLayout = v.findViewById(R.id.mediaActionBarOut)
        val btnOpenMedia: TextView = v.findViewById(R.id.btnOpenMediaOut)
        val btnDownloadMedia: TextView = v.findViewById(R.id.btnDownloadMediaOut)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_OUTGOING)
            OutgoingVH(inf.inflate(R.layout.item_chat_message_out, parent, false))
        else
            IncomingVH(inf.inflate(R.layout.item_chat_message, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val ctx = holder.itemView.context
        if (holder is OutgoingVH) bindOutgoing(holder, msg, ctx)
        else bindIncoming(holder as IncomingVH, msg, ctx)
    }

    // ── Outgoing ──────────────────────────────────────────────────────────────

    private fun bindOutgoing(h: OutgoingVH, msg: ChatMessage, ctx: Context) {
        h.tvMessage.text = msg.message
        h.tvTimestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
        bindMedia(msg, h.tvMessage, h.ivMediaPreview, h.attachmentCard,
            h.tvAttachmentIcon, h.tvAttachmentName, h.mediaActionBar, h.btnOpenMedia, h.btnDownloadMedia, ctx)
        h.messageContainer.setOnLongClickListener {
            h.messageActions.visibility = if (h.messageActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        h.btnMarkImportant.setOnClickListener { onMarkImportant(msg); h.messageActions.visibility = View.GONE }
        h.btnCreateTask.setOnClickListener { onCreateTask(msg); h.messageActions.visibility = View.GONE }
        h.btnSetReminder.setOnClickListener { onSetReminder(msg); h.messageActions.visibility = View.GONE }
    }

    // ── Incoming ──────────────────────────────────────────────────────────────

    private fun bindIncoming(h: IncomingVH, msg: ChatMessage, ctx: Context) {
        h.tvTimestamp.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
        h.tvReadTick.visibility = View.GONE
        h.tvImportantBadge.visibility = if (msg.isImportant) View.VISIBLE else View.GONE
        h.tvLinkedTaskBadge.visibility = if (msg.linkedTaskId != null) View.VISIBLE else View.GONE

        if (msg.isAI || msg.userId == "__ai__") {
            // Beeta AI
            h.tvSenderName.visibility = View.VISIBLE
            h.tvSenderName.text = "Beeta AI"
            h.tvSenderName.setTextColor(0xFF4C6EF5.toInt())
            h.messageContainer.setBackgroundResource(R.drawable.bg_bubble_ai)
            h.tvMessage.setTextColor(0xFF1A1A1A.toInt())
            setCircleColor(h.avatarBg, 0xFF4C6EF5.toInt())
            val aiBmp = getAiBitmap(ctx)
            if (aiBmp != null) {
                h.ivAvatar.setImageBitmap(aiBmp)
                h.ivAvatar.visibility = View.VISIBLE
                h.tvAvatarInitial.visibility = View.GONE
            } else {
                h.ivAvatar.visibility = View.GONE
                h.tvAvatarInitial.visibility = View.VISIBLE
                h.tvAvatarInitial.text = "AI"
            }
        } else {
            // Human sender
            val color = senderColors[Math.abs(msg.userId.hashCode()) % senderColors.size]
            h.tvSenderName.visibility = View.VISIBLE
            h.tvSenderName.text = msg.userName
            h.tvSenderName.setTextColor(color)
            h.messageContainer.setBackgroundResource(R.drawable.bg_bubble_in)
            h.tvMessage.setTextColor(0xFF1A1A1A.toInt())
            setCircleColor(h.avatarBg, color)

            val photoUrl = photoMap[msg.userId]
            if (!photoUrl.isNullOrBlank()) {
                h.tvAvatarInitial.visibility = View.INVISIBLE
                h.ivAvatar.visibility = View.VISIBLE
                loadAvatar(ctx, photoUrl, h)
            } else {
                h.ivAvatar.visibility = View.GONE
                h.tvAvatarInitial.visibility = View.VISIBLE
                h.tvAvatarInitial.text = msg.userName.firstOrNull()?.uppercase() ?: "?"
            }
        }

        bindMedia(msg, h.tvMessage, h.ivMediaPreview, h.attachmentCard,
            h.tvAttachmentIcon, h.tvAttachmentName, h.mediaActionBar, h.btnOpenMedia, h.btnDownloadMedia, ctx)
        h.messageContainer.setOnLongClickListener {
            h.messageActions.visibility = if (h.messageActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }
        h.btnMarkImportant.setOnClickListener { onMarkImportant(msg); h.messageActions.visibility = View.GONE }
        h.btnCreateTask.setOnClickListener { onCreateTask(msg); h.messageActions.visibility = View.GONE }
        h.btnSetReminder.setOnClickListener { onSetReminder(msg); h.messageActions.visibility = View.GONE }
    }

    // ── Shared media binding ──────────────────────────────────────────────────

    private fun bindMedia(
        msg: ChatMessage,
        tvMessage: TextView, ivPreview: ImageView, attachCard: LinearLayout,
        tvIcon: TextView, tvName: TextView, actionBar: LinearLayout,
        btnOpen: TextView, btnSave: TextView, ctx: Context
    ) {
        val uri = msg.attachmentUri
        val type = msg.attachmentType
        if (!uri.isNullOrBlank() && !type.isNullOrBlank()) {
            tvMessage.visibility = if (msg.message.isNotBlank()) View.VISIBLE else View.GONE
            tvMessage.text = msg.message
            actionBar.visibility = View.VISIBLE
            when (type) {
                "image" -> { ivPreview.visibility = View.VISIBLE; attachCard.visibility = View.GONE; loadImage(ivPreview, uri) }
                "video" -> { ivPreview.visibility = View.VISIBLE; attachCard.visibility = View.GONE; loadVideoThumbnail(ivPreview, uri, ctx) }
                else -> {
                    ivPreview.visibility = View.GONE; attachCard.visibility = View.VISIBLE
                    tvIcon.text = when (type) { "audio" -> "Mic"; "video" -> "Vid"; else -> docIcon(uri) }
                    tvName.text = uri.substringAfterLast("/")
                }
            }
            btnOpen.setOnClickListener { openMedia(ctx, uri, type) }
            btnSave.setOnClickListener { downloadMedia(ctx, uri, type) }
        } else {
            tvMessage.visibility = View.VISIBLE
            tvMessage.text = msg.message
            ivPreview.visibility = View.GONE
            attachCard.visibility = View.GONE
            actionBar.visibility = View.GONE
        }
    }

    // ── Avatar helpers ────────────────────────────────────────────────────────

    private fun setCircleColor(view: View, color: Int) {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        view.background = d
    }

    private fun loadAvatar(ctx: Context, url: String, h: IncomingVH) {
        val cached = bitmapCache.get(url)
        if (cached != null) {
            h.ivAvatar.setImageBitmap(cached)
            h.ivAvatar.visibility = View.VISIBLE
            h.tvAvatarInitial.visibility = View.GONE
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val bmp = BitmapFactory.decodeStream(conn.getInputStream())
                val circle = circleCrop(bmp)
                bitmapCache.put(url, circle)
                withContext(Dispatchers.Main) {
                    h.ivAvatar.setImageBitmap(circle)
                    h.ivAvatar.visibility = View.VISIBLE
                    h.tvAvatarInitial.visibility = View.GONE
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    h.ivAvatar.visibility = View.GONE
                    h.tvAvatarInitial.visibility = View.VISIBLE
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

    // ── Media open/save ───────────────────────────────────────────────────────

    private fun loadVideoThumbnail(iv: ImageView, uriStr: String, ctx: Context) {
        iv.setImageResource(android.R.drawable.ic_media_play)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retriever = MediaMetadataRetriever()
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "content" || uri.scheme == "file") {
                    retriever.setDataSource(ctx, uri)
                } else {
                    retriever.setDataSource(uriStr, emptyMap())
                }
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (frame != null) {
                    val thumb = Bitmap.createScaledBitmap(frame, 320, 180, true)
                    withContext(Dispatchers.Main) { iv.setImageBitmap(thumb) }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { iv.setImageResource(android.R.drawable.ic_media_play) }
            }
        }
    }

    private fun loadImage(iv: ImageView, uriStr: String) {
        try {
            val uri = Uri.parse(uriStr)
            // Max size for chat thumbnail: 800x800
            val bmp = decodeSampledBitmap(iv.context, uri, uriStr, 800, 800)
            iv.setImageBitmap(bmp)
        } catch (_: Exception) { iv.setImageResource(android.R.drawable.ic_menu_gallery) }
    }

    private fun decodeSampledBitmap(ctx: Context, uri: Uri, uriStr: String, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(ctx, uri, uriStr)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.inSampleSize = calcSampleSize(opts, reqW, reqH)
        opts.inJustDecodeBounds = false
        return openStream(ctx, uri, uriStr)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun openStream(ctx: Context, uri: Uri, uriStr: String): java.io.InputStream? = try {
        if (uri.scheme == "content") ctx.contentResolver.openInputStream(uri)
        else java.io.FileInputStream(uriStr)
    } catch (_: Exception) { null }

    private fun calcSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (h, w) = opts.outHeight to opts.outWidth
        var sample = 1
        if (h > reqH || w > reqW) {
            val hRatio = Math.round(h.toFloat() / reqH)
            val wRatio = Math.round(w.toFloat() / reqW)
            sample = minOf(hRatio, wRatio)
        }
        return sample
    }

    private fun openMedia(ctx: Context, uriStr: String, type: String) {
        val activity = ctx as? androidx.fragment.app.FragmentActivity ?: return
        val name = uriStr.substringAfterLast("/").ifBlank { "attachment" }
        
        // Check if it's a Firebase reference
        if (uriStr.startsWith("firebase://")) {
            // Download from Firebase Realtime Database first
            val progressDialog = android.app.ProgressDialog(ctx).apply {
                setMessage("Loading...")
                setCancelable(false)
                show()
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Parse firebase://attachments/sessionId/messageId
                    val parts = uriStr.removePrefix("firebase://attachments/").split("/")
                    if (parts.size != 2) throw Exception("Invalid Firebase reference")
                    
                    val sessionId = parts[0]
                    val messageId = parts[1]
                    
                    val database = com.google.firebase.database.FirebaseDatabase.getInstance("https://nbheditior-default-rtdb.firebaseio.com").reference
                    val snapshot = database.child("collaborative_sessions")
                        .child(sessionId)
                        .child("attachments")
                        .child(messageId)
                        .get()
                        .await()
                    
                    val base64 = snapshot.child("base64").getValue(String::class.java)
                        ?: throw Exception("File data not found")
                    val fileName = snapshot.child("fileName").getValue(String::class.java) ?: "file"
                    
                    // Decode base64 to bytes
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    
                    // Save to cache
                    val cacheFile = java.io.File(ctx.cacheDir, "temp_$fileName")
                    cacheFile.writeBytes(bytes)
                    
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        val localUri = android.net.Uri.fromFile(cacheFile).toString()
                        val fragment = MediaViewerFragment.newInstance(localUri, type, fileName)
                        activity.supportFragmentManager.beginTransaction()
                            .setCustomAnimations(
                                android.R.anim.fade_in, android.R.anim.fade_out,
                                android.R.anim.fade_in, android.R.anim.fade_out
                            )
                            .add(android.R.id.content, fragment)
                            .addToBackStack("media_viewer")
                            .commit()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(ctx, "Failed to load: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            // Regular URI - open directly
            val fragment = MediaViewerFragment.newInstance(uriStr, type, name)
            activity.supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    android.R.anim.fade_in, android.R.anim.fade_out,
                    android.R.anim.fade_in, android.R.anim.fade_out
                )
                .add(android.R.id.content, fragment)
                .addToBackStack("media_viewer")
                .commit()
        }
    }

    private fun downloadMedia(ctx: Context, uriStr: String, type: String) {
        // Check if it's a Firebase reference
        if (uriStr.startsWith("firebase://")) {
            val progressDialog = android.app.ProgressDialog(ctx).apply {
                setMessage("Downloading...")
                setCancelable(false)
                show()
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val parts = uriStr.removePrefix("firebase://attachments/").split("/")
                    if (parts.size != 2) throw Exception("Invalid Firebase reference")
                    
                    val sessionId = parts[0]
                    val messageId = parts[1]
                    
                    val database = com.google.firebase.database.FirebaseDatabase.getInstance("https://nbheditior-default-rtdb.firebaseio.com").reference
                    val snapshot = database.child("collaborative_sessions")
                        .child(sessionId)
                        .child("attachments")
                        .child(messageId)
                        .get()
                        .await()
                    
                    val base64 = snapshot.child("base64").getValue(String::class.java)
                        ?: throw Exception("File data not found")
                    val fileName = snapshot.child("fileName").getValue(String::class.java) ?: "file"
                    
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val dest = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), fileName)
                    dest.writeBytes(bytes)
                    
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(ctx, "Saved to Downloads/$fileName", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(ctx, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        
        // Regular download for non-Firebase URIs
        try {
            val name = uriStr.substringAfterLast("/").ifBlank { "attachment_${System.currentTimeMillis()}" }
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse(uriStr)).apply {
                    setTitle(name); setDescription("Saving from NBH Chat")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    setMimeType(mimeFor(uriStr, type)); setAllowedOverMetered(true)
                }
            )
            Toast.makeText(ctx, "Saving to Downloads...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun docIcon(uri: String) = when {
        uri.endsWith(".pdf", true) -> "PDF"
        uri.endsWith(".rtf", true) -> "RTF"
        uri.endsWith(".doc", true) || uri.endsWith(".docx", true) -> "DOC"
        uri.endsWith(".xls", true) || uri.endsWith(".xlsx", true) -> "XLS"
        uri.endsWith(".ppt", true) || uri.endsWith(".pptx", true) -> "PPT"
        else -> "FILE"
    }

    private fun mimeFor(uri: String, type: String) = when (type) {
        "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"
        else -> when {
            uri.endsWith(".pdf", true) -> "application/pdf"
            uri.endsWith(".rtf", true) -> "application/rtf"
            uri.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            uri.endsWith(".doc", true) -> "application/msword"
            uri.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            uri.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "*/*"
        }
    }
}
