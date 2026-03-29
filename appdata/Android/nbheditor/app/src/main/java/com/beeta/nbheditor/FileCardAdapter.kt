package com.beeta.nbheditor

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
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

    // Accent colors cycling per card — Catppuccin palette
    private val accents = listOf(
        "#FF89B4FA", // blue
        "#FFA6E3A1", // green
        "#FFCBA6F7", // purple
        "#FFFAB387", // peach
        "#FF89DCEB", // sky
        "#FFF38BA8", // pink
        "#FFE6C384", // yellow
        "#FF94E2D5"  // teal
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
            b.fileName.text = entry.name
            b.filePreview.text = entry.preview.ifBlank { "Empty file" }
            b.fileDate.text = if (entry.lastModified > 0)
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(entry.lastModified))
            else ""

            // Cycle accent color
            val color = Color.parseColor(accents[pos % accents.size])
            b.accentBar.setBackgroundColor(color)
            b.fileCard.strokeColor = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.argb(80, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
            ).defaultColor

            b.root.setOnClickListener { onOpen(entry) }
            b.root.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}
