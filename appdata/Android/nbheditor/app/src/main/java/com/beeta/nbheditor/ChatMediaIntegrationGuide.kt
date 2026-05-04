/**
 * INTEGRATION GUIDE: Adding Media Previews to Chat Messages
 * 
 * Add this code to your chat message adapter's onBindViewHolder method
 */

// In your ChatAdapter's onBindViewHolder method:

fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val message = messages[position]
    
    // ... existing code for text, timestamp, etc ...
    
    // Handle media attachments with preview
    if (message.mediaUrl != null && message.mediaUrl.isNotEmpty()) {
        val mimeType = message.mimeType
        
        // Show preview for supported media types
        if (ChatMediaHelper.shouldShowPreview(mimeType)) {
            // Load and display preview
            ChatMediaHelper.loadMediaPreviewFromUrl(
                context = holder.itemView.context,
                imageView = holder.ivMediaPreview,
                mediaUrl = message.mediaUrl,
                mimeType = mimeType,
                lifecycleScope = lifecycleScope
            )
            
            // Show media action bar
            holder.mediaActionBar.visibility = View.VISIBLE
            
            // Hide attachment card (we're showing preview instead)
            holder.attachmentCard.visibility = View.GONE
        } else {
            // Show attachment card for non-previewable files
            holder.ivMediaPreview.visibility = View.GONE
            holder.attachmentCard.visibility = View.VISIBLE
            holder.mediaActionBar.visibility = View.VISIBLE
            
            // Set attachment icon and name
            holder.tvAttachmentIcon.text = ChatMediaHelper.getMediaIcon(mimeType)
            holder.tvAttachmentName.text = message.fileName ?: ChatMediaHelper.getFileTypeName(mimeType)
        }
        
        // Setup media action buttons
        holder.btnOpenMedia.setOnClickListener {
            openMedia(message.mediaUrl, mimeType)
        }
        
        holder.btnDownloadMedia.setOnClickListener {
            downloadMedia(message.mediaUrl, message.fileName)
        }
    } else {
        // No media attachment
        holder.ivMediaPreview.visibility = View.GONE
        holder.attachmentCard.visibility = View.GONE
        holder.mediaActionBar.visibility = View.GONE
    }
}

// When sending media, generate preview immediately:
fun sendMediaMessage(uri: Uri, mimeType: String?) {
    lifecycleScope.launch {
        try {
            // Generate preview
            val preview = MediaPreviewGenerator.generatePreview(context, uri, mimeType)
            
            // Save preview to cache
            val cacheKey = uri.toString().hashCode().toString()
            preview?.let {
                MediaPreviewGenerator.savePreviewToCache(context, it, cacheKey)
            }
            
            // Upload media to Firebase Storage
            val mediaUrl = uploadMediaToStorage(uri)
            
            // Send message with media URL
            val message = ChatMessage(
                text = "",
                mediaUrl = mediaUrl,
                mimeType = mimeType,
                fileName = getFileName(uri),
                timestamp = System.currentTimeMillis()
            )
            
            sendMessage(message)
        } catch (e: Exception) {
            Log.e("Chat", "Error sending media", e)
        }
    }
}

// Clear old cached previews periodically (e.g., in onResume):
override fun onResume() {
    super.onResume()
    ChatMediaHelper.clearOldCache(requireContext(), lifecycleScope)
}

/**
 * REQUIRED: Add these fields to your ChatMessage data class:
 * 
 * data class ChatMessage(
 *     val text: String,
 *     val mediaUrl: String? = null,
 *     val mimeType: String? = null,
 *     val fileName: String? = null,
 *     val timestamp: Long,
 *     // ... other fields
 * )
 */

/**
 * REQUIRED: Add these views to your ViewHolder:
 * 
 * class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
 *     val ivMediaPreview: ImageView = view.findViewById(R.id.ivMediaPreview)
 *     val attachmentCard: LinearLayout = view.findViewById(R.id.attachmentCard)
 *     val tvAttachmentIcon: TextView = view.findViewById(R.id.tvAttachmentIcon)
 *     val tvAttachmentName: TextView = view.findViewById(R.id.tvAttachmentName)
 *     val mediaActionBar: LinearLayout = view.findViewById(R.id.mediaActionBar)
 *     val btnOpenMedia: TextView = view.findViewById(R.id.btnOpenMedia)
 *     val btnDownloadMedia: TextView = view.findViewById(R.id.btnDownloadMedia)
 *     // ... other views
 * }
 */
