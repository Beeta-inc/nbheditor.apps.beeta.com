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
                // Glass card: semi-transparent dark background, white bold text
                b.root.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.glass_editor_surface)
                )
                b.root.strokeColor = ContextCompat.getColor(ctx, R.color.glass_border)
                b.fileName.setTextColor(Color.BLACK)
                b.fileName.textSize = 15f
                b.fileDate.setTextColor(Color.parseColor("#CC000000"))
                b.filePreview.setTextColor(Color.parseColor("#AA000000"))
            } else {
                b.root.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.editor_surface)
                )
                b.root.strokeColor = ContextCompat.getColor(ctx, R.color.divider)
                b.fileName.setTextColor(ContextCompat.getColor(ctx, R.color.editor_text))
                b.fileName.textSize = 15f
                b.fileDate.setTextColor(ContextCompat.getColor(ctx, R.color.editor_line_number_text))
                b.filePreview.setTextColor(ContextCompat.getColor(ctx, R.color.editor_hint))
            }

            b.root.setOnClickListener { onOpen(entry) }
            b.root.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}
