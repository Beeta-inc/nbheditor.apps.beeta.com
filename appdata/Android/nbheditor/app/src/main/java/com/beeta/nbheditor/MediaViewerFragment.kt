package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MediaViewerFragment : Fragment() {

    companion object {
        private const val ARG_URI = "uri"
        private const val ARG_TYPE = "type"
        private const val ARG_NAME = "name"

        fun newInstance(uri: String, type: String, name: String) = MediaViewerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URI, uri)
                putString(ARG_TYPE, type)
                putString(ARG_NAME, name)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_media_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uriStr = arguments?.getString(ARG_URI) ?: return
        val type = arguments?.getString(ARG_TYPE) ?: return
        val name = arguments?.getString(ARG_NAME) ?: uriStr.substringAfterLast("/")

        val btnClose = view.findViewById<ImageButton>(R.id.btnViewerClose)
        val tvTitle = view.findViewById<TextView>(R.id.tvViewerTitle)
        val btnDownload = view.findViewById<ImageButton>(R.id.btnViewerDownload)
        val progress = view.findViewById<ProgressBar>(R.id.viewerProgress)
        val tvError = view.findViewById<TextView>(R.id.tvViewerError)

        tvTitle.text = name
        btnClose.setOnClickListener { parentFragmentManager.popBackStack() }
        btnDownload.setOnClickListener { downloadFile(uriStr, name, type) }

        // Slide-in animation
        view.translationY = view.height.toFloat()
        view.animate().translationY(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        when (type) {
            "image" -> showImage(view, uriStr, progress, tvError)
            "video" -> showVideo(view, uriStr, progress, tvError)
            "audio" -> showAudio(view, uriStr, name, progress, tvError)
            "document" -> showDocument(view, uriStr, name, progress, tvError)
            else -> showDocument(view, uriStr, name, progress, tvError)
        }
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun showImage(view: View, uriStr: String, progress: ProgressBar, tvError: TextView) {
        val iv = view.findViewById<ImageView>(R.id.imageViewer)
        iv.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val dm = resources.displayMetrics
                val maxW = dm.widthPixels
                val maxH = dm.heightPixels
                val bmp = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriStr)
                    decodeSampled(uriStr, uri, maxW, maxH)
                }
                progress.visibility = View.GONE
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    setupPinchZoom(iv)
                } else {
                    tvError.text = "Could not load image"
                    tvError.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvError.text = "Error: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeSampled(uriStr: String, uri: Uri, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        val ctx = requireContext()
        fun stream() = try {
            if (uri.scheme == "content") ctx.contentResolver.openInputStream(uri)
            else if (uri.scheme == "http" || uri.scheme == "https") {
                val conn = URL(uriStr).openConnection()
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.getInputStream()
            } else java.io.FileInputStream(uriStr)
        } catch (_: Exception) { null }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream()?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            val hRatio = Math.round(opts.outHeight.toFloat() / reqH)
            val wRatio = Math.round(opts.outWidth.toFloat() / reqW)
            sample = minOf(hRatio, wRatio).coerceAtLeast(1)
        }
        opts.inSampleSize = sample
        opts.inJustDecodeBounds = false
        return stream()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun setupPinchZoom(iv: ImageView) {
        val matrix = Matrix()
        var scale = 1f
        var lastX = 0f; var lastY = 0f
        var isDragging = false

        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scale *= detector.scaleFactor
                    scale = scale.coerceIn(0.5f, 8f)
                    matrix.setScale(scale, scale, detector.focusX, detector.focusY)
                    iv.imageMatrix = matrix
                    return true
                }
            })

        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; isDragging = true }
                MotionEvent.ACTION_MOVE -> if (isDragging && !scaleDetector.isInProgress) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    iv.imageMatrix = matrix
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
            }
            true
        }
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private fun showVideo(view: View, uriStr: String, progress: ProgressBar, tvError: TextView) {
        val vv = view.findViewById<VideoView>(R.id.videoViewer)
        val controls = view.findViewById<LinearLayout>(R.id.videoControls)
        val seekBar = view.findViewById<SeekBar>(R.id.videoSeekBar)
        val btnPlay = view.findViewById<ImageButton>(R.id.btnVideoPlayPause)
        val btnRew = view.findViewById<ImageButton>(R.id.btnVideoRewind)
        val btnFwd = view.findViewById<ImageButton>(R.id.btnVideoForward)
        val tvTime = view.findViewById<TextView>(R.id.tvVideoTime)

        vv.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        vv.setVideoURI(Uri.parse(uriStr))
        vv.setOnPreparedListener { mp ->
            progress.visibility = View.GONE
            seekBar.max = mp.duration
            mp.start()
            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            startSeekUpdate(seekBar, tvTime, vv)
        }
        vv.setOnErrorListener { _, _, _ ->
            progress.visibility = View.GONE
            tvError.text = "Cannot play this video"
            tvError.visibility = View.VISIBLE
            true
        }

        btnPlay.setOnClickListener {
            if (vv.isPlaying) {
                vv.pause()
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                vv.start()
                btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
        btnRew.setOnClickListener { vv.seekTo((vv.currentPosition - 10000).coerceAtLeast(0)) }
        btnFwd.setOnClickListener { vv.seekTo((vv.currentPosition + 10000).coerceAtMost(vv.duration)) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) vv.seekTo(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startSeekUpdate(seekBar: SeekBar, tvTime: TextView, vv: VideoView) {
        seekRunnable = object : Runnable {
            override fun run() {
                if (vv.isPlaying) {
                    seekBar.progress = vv.currentPosition
                    tvTime.text = "${formatTime(vv.currentPosition)} / ${formatTime(vv.duration)}"
                }
                seekHandler.postDelayed(this, 500)
            }
        }
        seekHandler.post(seekRunnable!!)
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private fun showAudio(view: View, uriStr: String, name: String, progress: ProgressBar, tvError: TextView) {
        val audioView = view.findViewById<LinearLayout>(R.id.audioViewer)
        val seekBar = view.findViewById<SeekBar>(R.id.audioSeekBar)
        val btnPlay = view.findViewById<ImageButton>(R.id.btnAudioPlayPause)
        val btnRew = view.findViewById<ImageButton>(R.id.btnAudioRewind)
        val btnFwd = view.findViewById<ImageButton>(R.id.btnAudioForward)
        val tvTime = view.findViewById<TextView>(R.id.tvAudioTime)
        val tvName = view.findViewById<TextView>(R.id.tvAudioName)

        audioView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvName.text = name

        lifecycleScope.launch {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(requireContext(), Uri.parse(uriStr))
                    prepareAsync()
                    setOnPreparedListener { mp ->
                        progress.visibility = View.GONE
                        seekBar.max = mp.duration
                        mp.start()
                        btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                        startAudioSeekUpdate(seekBar, tvTime, mp)
                    }
                    setOnErrorListener { _, _, _ ->
                        progress.visibility = View.GONE
                        tvError.text = "Cannot play this audio"
                        tvError.visibility = View.VISIBLE
                        true
                    }
                    setOnCompletionListener {
                        btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvError.text = "Error: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }

        btnPlay.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                mp.start()
                btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
        btnRew.setOnClickListener { mediaPlayer?.seekTo((mediaPlayer!!.currentPosition - 10000).coerceAtLeast(0)) }
        btnFwd.setOnClickListener { mediaPlayer?.seekTo((mediaPlayer!!.currentPosition + 10000).coerceAtMost(mediaPlayer!!.duration)) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) mediaPlayer?.seekTo(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startAudioSeekUpdate(seekBar: SeekBar, tvTime: TextView, mp: MediaPlayer) {
        seekRunnable = object : Runnable {
            override fun run() {
                if (mp.isPlaying) {
                    seekBar.progress = mp.currentPosition
                    tvTime.text = "${formatTime(mp.currentPosition)} / ${formatTime(mp.duration)}"
                }
                seekHandler.postDelayed(this, 500)
            }
        }
        seekHandler.post(seekRunnable!!)
    }

    // ── Document (PDF / text / etc.) ──────────────────────────────────────────

    private fun showDocument(view: View, uriStr: String, name: String, progress: ProgressBar, tvError: TextView) {
        val wv = view.findViewById<WebView>(R.id.documentViewer)
        wv.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        wv.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                progress.visibility = View.GONE
                tvError.text = "Could not load document"
                tvError.visibility = View.VISIBLE
            }
        }

        val uri = Uri.parse(uriStr)
        when {
            // Local file — read bytes and load as base64 data URI
            uri.scheme == "content" || uri.scheme == "file" || uriStr.startsWith("/") -> {
                lifecycleScope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            if (uri.scheme == "content") {
                                requireContext().contentResolver.openInputStream(uri)?.readBytes()
                            } else {
                                File(uriStr).readBytes()
                            }
                        }
                        if (bytes != null) {
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                            val mime = mimeForDoc(name)
                            if (mime == "application/pdf") {
                                // Use PdfRenderer for local PDFs
                                progress.visibility = View.GONE
                                wv.visibility = View.GONE
                                showPdfRenderer(view, uri, progress, tvError)
                            } else {
                                wv.loadData(base64, mime, "base64")
                            }
                        } else {
                            progress.visibility = View.GONE
                            tvError.text = "Could not read file"
                            tvError.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) {
                        progress.visibility = View.GONE
                        tvError.text = "Error: ${e.message}"
                        tvError.visibility = View.VISIBLE
                    }
                }
            }
            // Remote URL — load directly in WebView
            else -> wv.loadUrl(uriStr)
        }
    }

    // ── PDF Renderer (local PDFs) ─────────────────────────────────────────────

    private fun showPdfRenderer(view: View, uri: Uri, progress: ProgressBar, tvError: TextView) {
        lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    val fd = requireContext().contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                    val renderer = android.graphics.pdf.PdfRenderer(fd)
                    val bitmaps = mutableListOf<android.graphics.Bitmap>()
                    val screenW = resources.displayMetrics.widthPixels
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        // Scale to screen width, cap height to avoid OOM
                        val scale = screenW.toFloat() / page.width
                        val bmpW = screenW
                        val bmpH = (page.height * scale).toInt().coerceAtMost(screenW * 2)
                        val bmp = android.graphics.Bitmap.createBitmap(
                            bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        bitmaps.add(bmp)
                    }
                    renderer.close()
                    fd.close()
                    bitmaps
                }
                progress.visibility = View.GONE
                if (pages.isNullOrEmpty()) {
                    tvError.text = "Empty PDF"
                    tvError.visibility = View.VISIBLE
                    return@launch
                }
                // Show pages in a vertical ScrollView
                val scroll = android.widget.ScrollView(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                val container = android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                pages.forEach { bmp ->
                    val iv = ImageView(requireContext()).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.bottomMargin = 4 }
                    }
                    container.addView(iv)
                }
                scroll.addView(container)
                // Add scroll to the root constraint layout
                val root = view as? ViewGroup ?: return@launch
                root.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // Position below header
                (scroll.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
                    lp.topToBottom = R.id.viewerHeader
                    scroll.layoutParams = lp
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                tvError.text = "PDF error: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadFile(uriStr: String, name: String, type: String) {
        try {
            val uri = Uri.parse(uriStr)
            // If it's already a local file, just toast the path
            if (uri.scheme == "file" || uriStr.startsWith("/")) {
                Toast.makeText(requireContext(), "File is at: $uriStr", Toast.LENGTH_LONG).show()
                return
            }
            if (uri.scheme == "content") {
                // Copy to Downloads
                lifecycleScope.launch {
                    try {
                        val bytes = withContext(Dispatchers.IO) {
                            requireContext().contentResolver.openInputStream(uri)?.readBytes()
                        }
                        if (bytes != null) {
                            val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                            withContext(Dispatchers.IO) { dest.writeBytes(bytes) }
                            Toast.makeText(requireContext(), "Saved to Downloads/$name", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            // Remote URL
            val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(DownloadManager.Request(uri).apply {
                setTitle(name)
                setDescription("Downloading from NBH Chat")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
            })
            Toast.makeText(requireContext(), "Downloading to Downloads/$name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun mimeForDoc(name: String) = when {
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        name.endsWith(".doc", true) -> "application/msword"
        else -> "application/octet-stream"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
    }
}
