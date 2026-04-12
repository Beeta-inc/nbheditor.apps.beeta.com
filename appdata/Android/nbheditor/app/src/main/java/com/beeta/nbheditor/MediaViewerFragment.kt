package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
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

        android.util.Log.d("MediaViewer", "MediaViewerFragment created: uri=$uriStr, type=$type, name=$name")

        val header = view.findViewById<LinearLayout>(R.id.viewerHeader)
        val btnClose = view.findViewById<ImageButton>(R.id.btnViewerClose)
        val tvTitle = view.findViewById<TextView>(R.id.tvViewerTitle)
        val btnDownload = view.findViewById<ImageButton>(R.id.btnViewerDownload)
        val progress = view.findViewById<ProgressBar>(R.id.viewerProgress)
        val tvError = view.findViewById<TextView>(R.id.tvViewerError)

        // Push header below status bar
        val statusBarHeight = getStatusBarHeight()
        header.setPadding(
            header.paddingLeft,
            header.paddingTop + statusBarHeight,
            header.paddingRight,
            header.paddingBottom
        )

        tvTitle.text = name
        btnClose.setOnClickListener { parentFragmentManager.popBackStack() }
        btnDownload.setOnClickListener { downloadFile(uriStr, name, type) }

        // Slide up animation
        view.translationY = view.height.toFloat()
        view.animate().translationY(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        when (type) {
            "image" -> {
                android.util.Log.d("MediaViewer", "Showing image viewer")
                showImage(view, uriStr, progress, tvError)
            }
            "video" -> {
                android.util.Log.d("MediaViewer", "Showing video viewer")
                showVideo(view, uriStr, progress, tvError)
            }
            "audio" -> {
                android.util.Log.d("MediaViewer", "Showing audio viewer")
                showAudio(view, uriStr, name, progress, tvError)
            }
            "document" -> {
                android.util.Log.d("MediaViewer", "Showing document viewer")
                showDocument(view, uriStr, name, progress, tvError)
            }
            else -> {
                android.util.Log.d("MediaViewer", "Unknown type '$type', defaulting to document viewer")
                showDocument(view, uriStr, name, progress, tvError)
            }
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    private fun showImage(view: View, uriStr: String, progress: ProgressBar, tvError: TextView) {
        val iv = view.findViewById<ImageView>(R.id.imageViewer)
        iv.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        android.util.Log.d("MediaViewer", "showImage called with uri: $uriStr")

        lifecycleScope.launch {
            try {
                val dm = resources.displayMetrics
                val maxW = dm.widthPixels * 2 // Allow higher resolution
                val maxH = dm.heightPixels * 2
                val bmp = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriStr)
                    android.util.Log.d("MediaViewer", "Decoding image from URI: $uri, scheme: ${uri.scheme}, path: ${uri.path}")
                    decodeSampled(uriStr, uri, maxW, maxH)
                }
                progress.visibility = View.GONE
                if (bmp != null) {
                    android.util.Log.d("MediaViewer", "Image decoded successfully: ${bmp.width}x${bmp.height}")
                    iv.setImageBitmap(bmp)
                    setupPinchZoom(iv)
                } else {
                    android.util.Log.e("MediaViewer", "Failed to decode image - bitmap is null")
                    tvError.text = "Could not load image"
                    tvError.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaViewer", "Error loading image", e)
                progress.visibility = View.GONE
                tvError.text = "Error: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeSampled(uriStr: String, uri: Uri, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        val ctx = requireContext()
        fun stream() = try {
            android.util.Log.d("MediaViewer", "Opening stream for URI: $uri, scheme: ${uri.scheme}")
            val result = if (uri.scheme == "content") {
                android.util.Log.d("MediaViewer", "Using ContentResolver for content URI")
                ctx.contentResolver.openInputStream(uri)
            } else if (uri.scheme == "http" || uri.scheme == "https") {
                android.util.Log.d("MediaViewer", "Using HTTP connection for remote URL")
                val conn = URL(uriStr).openConnection()
                conn.connectTimeout = 30000 // 30 seconds for large images
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "NbhEditor/1.0")
                conn.getInputStream()
            } else {
                // For file:// URIs or plain paths, extract the actual file path
                val filePath = if (uri.scheme == "file") uri.path else uriStr
                android.util.Log.d("MediaViewer", "Using FileInputStream for local file: $filePath")
                java.io.FileInputStream(filePath)
            }
            android.util.Log.d("MediaViewer", "Stream opened successfully")
            result
        } catch (e: Exception) {
            android.util.Log.e("MediaViewer", "Failed to open stream", e)
            null 
        }

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream()?.use { BitmapFactory.decodeStream(it, null, opts) }
        
        android.util.Log.d("MediaViewer", "Image dimensions: ${opts.outWidth}x${opts.outHeight}")
        
        var sample = 1
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            val hRatio = Math.round(opts.outHeight.toFloat() / reqH)
            val wRatio = Math.round(opts.outWidth.toFloat() / reqW)
            sample = minOf(hRatio, wRatio).coerceAtLeast(1)
        }
        
        android.util.Log.d("MediaViewer", "Using sample size: $sample")
        
        opts.inSampleSize = sample
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // Use less memory for large images
        
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
        val tv = view.findViewById<TextureView>(R.id.videoViewer)
        val controls = view.findViewById<LinearLayout>(R.id.videoControls)
        val seekBar = view.findViewById<SeekBar>(R.id.videoSeekBar)
        val btnPlay = view.findViewById<ImageButton>(R.id.btnVideoPlayPause)
        val btnRew = view.findViewById<ImageButton>(R.id.btnVideoRewind)
        val btnFwd = view.findViewById<ImageButton>(R.id.btnVideoForward)
        val tvTime = view.findViewById<TextView>(R.id.tvVideoTime)

        tv.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE

        android.util.Log.d("MediaViewer", "showVideo called with uri: $uriStr")

        fun initPlayer(surface: Surface) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    try {
                        val uri = Uri.parse(uriStr)
                        android.util.Log.d("MediaViewer", "Setting video data source: $uri, scheme: ${uri.scheme}")
                        if (uri.scheme == "content" || uri.scheme == "file") {
                            setDataSource(requireContext(), uri)
                        } else if (uri.scheme == null || uriStr.startsWith("/")) {
                            // Plain file path
                            setDataSource(uriStr)
                        } else {
                            setDataSource(uriStr)
                        }
                        setSurface(surface)
                        setOnPreparedListener { mp ->
                            android.util.Log.d("MediaViewer", "Video prepared successfully")
                            progress.visibility = View.GONE
                            seekBar.max = mp.duration
                            mp.start()
                            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                            startVideoSeekUpdate(seekBar, tvTime, mp)
                        }
                        setOnErrorListener { _, what, extra ->
                            android.util.Log.e("MediaViewer", "Video playback error: what=$what, extra=$extra")
                            progress.visibility = View.GONE
                            tvError.text = "Cannot play video (error: $what/$extra). Try downloading the file."
                            tvError.visibility = View.VISIBLE
                            true
                        }
                        setOnCompletionListener { btnPlay.setImageResource(android.R.drawable.ic_media_play) }
                        setOnInfoListener { _, what, extra ->
                            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                progress.visibility = View.VISIBLE
                            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                                progress.visibility = View.GONE
                            }
                            true
                        }
                        prepareAsync()
                    } catch (e: Exception) {
                        android.util.Log.e("MediaViewer", "Error setting up video player", e)
                        progress.visibility = View.GONE
                        tvError.text = "Video error: ${e.message}"
                        tvError.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaViewer", "Failed to initialize video player", e)
                progress.visibility = View.GONE
                tvError.text = "Failed to initialize player: ${e.message}"
                tvError.visibility = View.VISIBLE
            }
        }

        if (tv.isAvailable) {
            initPlayer(Surface(tv.surfaceTexture))
        } else {
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = initPlayer(Surface(st))
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

        btnPlay.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) { mp.pause(); btnPlay.setImageResource(android.R.drawable.ic_media_play) }
            else { mp.start(); btnPlay.setImageResource(android.R.drawable.ic_media_pause) }
        }
        btnRew.setOnClickListener { mediaPlayer?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0)) } }
        btnFwd.setOnClickListener { mediaPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(it.duration)) } }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) mediaPlayer?.seekTo(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startVideoSeekUpdate(seekBar: SeekBar, tvTime: TextView, mp: MediaPlayer) {
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

    // ── Audio ─────────────────────────────────────────────────────────────────

    private fun showAudio(view: View, uriStr: String, name: String, progress: ProgressBar, tvError: TextView) {
        val audioView = view.findViewById<LinearLayout>(R.id.audioViewer)
        val seekBar = view.findViewById<SeekBar>(R.id.audioSeekBar)
        val btnPlay = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnAudioPlayPause)
        val btnRew = view.findViewById<ImageButton>(R.id.btnAudioRewind)
        val btnFwd = view.findViewById<ImageButton>(R.id.btnAudioForward)
        val tvTime = view.findViewById<TextView>(R.id.tvAudioTime)
        val tvDuration = view.findViewById<TextView>(R.id.tvAudioDuration)
        val tvName = view.findViewById<TextView>(R.id.tvAudioName)

        audioView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        tvName.text = name

        lifecycleScope.launch {
            try {
                mediaPlayer = MediaPlayer().apply {
                    try {
                        val uri = Uri.parse(uriStr)
                        if (uri.scheme == "content" || uri.scheme == "file") {
                            setDataSource(requireContext(), uri)
                        } else {
                            setDataSource(uriStr)
                        }
                        setOnPreparedListener { mp ->
                            progress.visibility = View.GONE
                            seekBar.max = mp.duration
                            tvDuration.text = formatTime(mp.duration)
                            mp.start()
                            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                            startAudioSeekUpdate(seekBar, tvTime, mp)
                        }
                        setOnErrorListener { _, what, extra ->
                            progress.visibility = View.GONE
                            tvError.text = "Cannot play audio (error: $what/$extra). Try downloading the file."
                            tvError.visibility = View.VISIBLE
                            true
                        }
                        setOnCompletionListener {
                            btnPlay.setImageResource(android.R.drawable.ic_media_play)
                        }
                        setOnInfoListener { _, what, _ ->
                            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                progress.visibility = View.VISIBLE
                            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                                progress.visibility = View.GONE
                            }
                            true
                        }
                        prepareAsync()
                    } catch (e: Exception) {
                        progress.visibility = View.GONE
                        tvError.text = "Audio error: ${e.message}"
                        tvError.visibility = View.VISIBLE
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
                    tvTime.text = formatTime(mp.currentPosition)
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
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                android.util.Log.d("MediaViewer", "WebView page finished loading: $url")
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                progress.visibility = View.GONE
                android.util.Log.e("MediaViewer", "WebView error: $description")
                tvError.text = "Could not load document: $description"
                tvError.visibility = View.VISIBLE
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                progress.visibility = View.GONE
                android.util.Log.e("MediaViewer", "WebView resource error: ${error?.description}")
                tvError.text = "Could not load document: ${error?.description}"
                tvError.visibility = View.VISIBLE
            }
        }

        val uri = Uri.parse(uriStr)
        android.util.Log.d("MediaViewer", "Opening document: uri=$uriStr, name=$name, scheme=${uri.scheme}")
        
        when {
            // Local file — read and display
            uri.scheme == "content" || uri.scheme == "file" || uriStr.startsWith("/") -> {
                lifecycleScope.launch {
                    try {
                        val mime = mimeForDoc(name)
                        android.util.Log.d("MediaViewer", "Detected MIME type: $mime for file: $name")
                        
                        when {
                            mime == "application/pdf" || name.endsWith(".pdf", true) -> {
                                // Use PdfRenderer for local PDFs
                                android.util.Log.d("MediaViewer", "Using PdfRenderer for PDF")
                                progress.visibility = View.GONE
                                wv.visibility = View.GONE
                                showPdfRenderer(view, uri, progress, tvError)
                            }
                            mime == "application/rtf" || name.endsWith(".rtf", true) -> {
                                // Convert RTF to HTML and display
                                val bytes = withContext(Dispatchers.IO) {
                                    if (uri.scheme == "content") {
                                        requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    } else {
                                        File(uriStr).readBytes()
                                    }
                                }
                                if (bytes != null) {
                                    val html = convertRtfToHtml(bytes)
                                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                } else {
                                    progress.visibility = View.GONE
                                    tvError.text = "Could not read RTF file"
                                    tvError.visibility = View.VISIBLE
                                }
                            }
                            mime == "text/plain" || mime.startsWith("text/") -> {
                                // Load text files directly without base64
                                val text = withContext(Dispatchers.IO) {
                                    if (uri.scheme == "content") {
                                        requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                    } else {
                                        File(uriStr).readText()
                                    }
                                }
                                if (text != null) {
                                    val html = "<html><head><meta charset='UTF-8'><style>body{font-family:monospace;padding:16px;white-space:pre-wrap;word-wrap:break-word;}</style></head><body>${text.replace("<", "&lt;").replace(">", "&gt;")}</body></html>"
                                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                } else {
                                    progress.visibility = View.GONE
                                    tvError.text = "Could not read file"
                                    tvError.visibility = View.VISIBLE
                                }
                            }
                            else -> {
                                // For other document types, use Google Docs Viewer for remote URLs
                                // or try to display locally
                                if (uri.scheme == "http" || uri.scheme == "https") {
                                    // Use Google Docs Viewer for remote documents
                                    val viewerUrl = "https://docs.google.com/viewer?url=${android.net.Uri.encode(uriStr)}&embedded=true"
                                    wv.loadUrl(viewerUrl)
                                } else {
                                    // Try to load local file directly
                                    wv.loadUrl(uriStr)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        progress.visibility = View.GONE
                        tvError.text = "Error: ${e.message}"
                        tvError.visibility = View.VISIBLE
                    }
                }
            }
            // Remote URL — use Google Docs Viewer for better compatibility
            else -> {
                if (name.endsWith(".pdf", true) || name.endsWith(".doc", true) || 
                    name.endsWith(".docx", true) || name.endsWith(".ppt", true) || 
                    name.endsWith(".pptx", true) || name.endsWith(".xls", true) || 
                    name.endsWith(".xlsx", true)) {
                    val viewerUrl = "https://docs.google.com/viewer?url=${android.net.Uri.encode(uriStr)}&embedded=true"
                    wv.loadUrl(viewerUrl)
                } else {
                    wv.loadUrl(uriStr)
                }
            }
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
        name.endsWith(".rtf", true) -> "application/rtf"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".html", true) || name.endsWith(".htm", true) -> "text/html"
        name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        name.endsWith(".doc", true) -> "application/msword"
        else -> "application/octet-stream"
    }

    // Simple RTF to HTML converter for basic formatting
    private fun convertRtfToHtml(rtfBytes: ByteArray): String {
        try {
            val rtfText = String(rtfBytes, Charsets.UTF_8)
            
            // Extract plain text and basic formatting from RTF
            var html = StringBuilder("<html><head><meta charset='UTF-8'><style>")
            html.append("body { font-family: Arial, sans-serif; padding: 16px; line-height: 1.6; }")
            html.append("p { margin: 8px 0; }")
            html.append("b { font-weight: bold; }")
            html.append("i { font-style: italic; }")
            html.append("u { text-decoration: underline; }")
            html.append("</style></head><body>")
            
            // Basic RTF parsing - remove control words and extract text
            var text = rtfText
                .replace(Regex("\\\\rtf[0-9]"), "")
                .replace(Regex("\\\\ansi"), "")
                .replace(Regex("\\\\deff[0-9]"), "")
                .replace(Regex("\\\\fonttbl[^}]*}"), "")
                .replace(Regex("\\\\colortbl[^}]*}"), "")
                .replace(Regex("\\\\\\*\\\\[^;]*;"), "")
            
            // Convert RTF formatting to HTML
            text = text
                .replace(Regex("\\\\b\\s"), "<b>")
                .replace(Regex("\\\\b0\\s"), "</b>")
                .replace(Regex("\\\\i\\s"), "<i>")
                .replace(Regex("\\\\i0\\s"), "</i>")
                .replace(Regex("\\\\ul\\s"), "<u>")
                .replace(Regex("\\\\ulnone\\s"), "</u>")
                .replace(Regex("\\\\par\\s*"), "</p><p>")
                .replace(Regex("\\\\line\\s*"), "<br>")
                .replace(Regex("\\\\tab\\s*"), "&nbsp;&nbsp;&nbsp;&nbsp;")
            
            // Remove remaining RTF control words
            text = text.replace(Regex("\\\\[a-z]+[0-9]*\\s*"), "")
            text = text.replace(Regex("[{}]"), "")
            text = text.replace(Regex("\\\\'"), "")
            
            html.append("<p>").append(text.trim()).append("</p>")
            html.append("</body></html>")
            
            return html.toString()
        } catch (e: Exception) {
            return "<html><body><p>Error parsing RTF: ${e.message}</p><pre>${String(rtfBytes, Charsets.UTF_8).take(1000)}</pre></body></html>"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        mediaPlayer?.apply { if (isPlaying) stop(); release() }
        mediaPlayer = null
    }
}
