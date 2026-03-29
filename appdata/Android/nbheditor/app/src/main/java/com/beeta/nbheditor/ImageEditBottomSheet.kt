package com.beeta.nbheditor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.beeta.nbheditor.databinding.DialogImageEditBinding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class ImageEditBottomSheet(
    private val original: Bitmap,
    private val onApply: (bitmap: Bitmap, caption: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _b: DialogImageEditBinding? = null
    private val b get() = _b!!

    // Working copy — rotate/compress ops mutate this
    private var working: Bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
    // Scale factor from seekbar (50 = 100%, 0 = 10%, 100 = 200%)
    private var scalePct = 100

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = DialogImageEditBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.cropImageView.bitmap = working

        // Size seekbar: 0→10%, 50→100%, 100→200%
        b.sizeSeekBar.progress = 50
        b.sizeLabel.text = "100%"
        b.sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                scalePct = when {
                    p <= 50 -> 10 + (p * 90 / 50)   // 10%..100%
                    else    -> 100 + ((p - 50) * 100 / 50) // 100%..200%
                }
                b.sizeLabel.text = "$scalePct%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        b.btnRotateLeft.setOnClickListener  { rotate(-90f) }
        b.btnRotateRight.setOnClickListener { rotate(90f) }

        b.btnCompress.setOnClickListener {
            // Compress = re-encode at 60% JPEG quality then decode back
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val out = java.io.ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, 60, out)
                val bytes = out.toByteArray()
                val compressed = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                withContext(Dispatchers.Main) {
                    working = compressed
                    b.cropImageView.bitmap = working
                    Toast.makeText(requireContext(), "Compressed ✓", Toast.LENGTH_SHORT).show()
                }
            }
        }

        b.btnCancel.setOnClickListener { dismiss() }

        b.btnApply.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                var result = b.cropImageView.getCroppedBitmap() ?: working

                // Apply scale
                if (scalePct != 100) {
                    val factor = scalePct / 100f
                    val nw = (result.width  * factor).toInt().coerceAtLeast(1)
                    val nh = (result.height * factor).toInt().coerceAtLeast(1)
                    result = Bitmap.createScaledBitmap(result, nw, nh, true)
                }

                val caption = withContext(Dispatchers.Main) {
                    b.captionEdit.text.toString().trim()
                }

                withContext(Dispatchers.Main) {
                    onApply(result, caption)
                    dismiss()
                }
            }
        }
    }

    private fun rotate(degrees: Float) {
        val m = Matrix().apply { postRotate(degrees) }
        working = Bitmap.createBitmap(working, 0, 0, working.width, working.height, m, true)
        b.cropImageView.bitmap = working
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
