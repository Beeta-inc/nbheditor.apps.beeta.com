package com.beeta.nbheditor

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.beeta.nbheditor.databinding.ItemFileCardBinding
import java.text.SimpleDateFormat
import java.util.*

class FileCardAdapter(
    private val onOpen: (FileEntry) -> Unit,
    private val onLongClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<FileCardAdapter.VH>() {

    data class FileEntry(
        val uri: android.net.Uri,
        val name: String,
        val preview: String,
        val lastModified: Long = 0L
    )

    private val items = mutableListOf<FileEntry>()
    var isGlassMode = false

    private val accents = listOf(
        "#FF89B4FA", "#FFA6E3A1", "#FFCBA6F7", "#FFFAB387",
        "#FF89DCEB", "#FFF38BA8", "#FFE6C384", "#FF94E2D5"
    )

    fun setFiles(files: List<FileEntry>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemFileCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemFileCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: FileEntry, pos: Int) {
            val ctx = b.root.context
            b.fileName.text = entry.name
            b.filePreview.text = entry.preview.ifBlank { "Empty file" }
            b.fileDate.text = if (entry.lastModified > 0)
                SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(entry.lastModified))
            else ""

            b.fileTypeIcon?.text = when (entry.name.substringAfterLast('.').lowercase()) {
                "kt", "java" -> "☕"
                "py" -> "🐍"
                "js", "ts" -> "📜"
                "html", "xml" -> "🌐"
                "json" -> "{ }"
                "md" -> "📝"
                "sh", "bash" -> "⚙"
                "cpp", "c", "h" -> "⚡"
                else -> "📄"
            }

            val accentColor = Color.parseColor(accents[pos % accents.size])
            b.accentBar.setBackgroundColor(accentColor)

            if (isGlassMode) {
                // Glass card: optimized for older Android devices (API 24+)
                b.root.apply {
                    setCardBackgroundColor(0xE80A0A18.toInt())
                    strokeColor = 0x33FFFFFF
                    strokeWidth = 2
                    cardElevation = 0f
                    radius = 18f
                }
                b.fileName.apply {
                    setTextColor(0xFFF0F2FF.toInt())
                    textSize = 15f
                    setShadowLayer(2f, 0f, 1f, 0x44000000)
                }
                b.fileDate.setTextColor(0xCCAABBFF.toInt())
                b.filePreview.setTextColor(0xAACCDDFF.toInt())
                b.fileTypeIcon?.apply {
                    setTextColor(0xFFF0F2FF.toInt())
                    setShadowLayer(2f, 0f, 1f, 0x44000000)
                }
            } else {
                b.root.apply {
                    setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.editor_surface))
                    strokeColor = ContextCompat.getColor(ctx, R.color.divider)
                    strokeWidth = 1
                    cardElevation = 2f
                    radius = 16f
                }
                b.fileName.apply {
                    setTextColor(ContextCompat.getColor(ctx, R.color.editor_text))
                    textSize = 15f
                    setShadowLayer(0f, 0f, 0f, 0)
                }
                b.fileDate.setTextColor(ContextCompat.getColor(ctx, R.color.editor_line_number_text))
                b.filePreview.setTextColor(ContextCompat.getColor(ctx, R.color.editor_hint))
                b.fileTypeIcon?.apply {
                    setTextColor(ContextCompat.getColor(ctx, R.color.editor_text))
                    setShadowLayer(0f, 0f, 0f, 0)
                }
            }

            b.root.setOnClickListener { onOpen(entry) }
            b.root.setOnLongClickListener { onLongClick(entry); true }
            
            // Add ripple effect for better touch feedback
            b.root.foreground = ContextCompat.getDrawable(ctx, R.drawable.ripple_card)
        }
    }
}
