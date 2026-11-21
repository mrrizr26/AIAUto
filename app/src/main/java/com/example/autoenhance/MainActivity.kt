package com.example.autoenhance

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private enum class Mode { CUSTOM, BUILTIN, NONE }

    private val accent = Color.parseColor("#25F4EE")
    private val gray = Color.parseColor("#8A8A90")

    // views
    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvBadge: TextView
    private lateinit var tvTime: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPickTop: TextView
    private lateinit var btnPickBig: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnSettings: TextView
    private lateinit var tvUpdateBanner: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnCompare: TextView
    private lateinit var seekPlay: SeekBar

    private lateinit var tabs: List<TextView>
    private lateinit var panels: List<View>

    private lateinit var swAuto: Switch
    private lateinit var seekStrength: SeekBar
    private lateinit var tvStrength: TextView
    private lateinit var seekDetails: SeekBar
    private lateinit var tvDetails: TextView
    private lateinit var cbCleanup: CheckBox

    private lateinit var llAdjChips: LinearLayout
    private lateinit var tvAdjValue: TextView
    private lateinit var seekAdjust: SeekBar
    private lateinit var btnAdjReset: TextView

    private lateinit var llFilters: LinearLayout
    private lateinit var seekIntensity: SeekBar
    private lateinit var tvIntensity: TextView

    private lateinit var tvTrimStart: TextView
    private lateinit var tvTrimEnd: TextView
    private lateinit var tvTrimInfo: TextView
    private lateinit var seekTrimStart: SeekBar
    private lateinit var seekTrimEnd: SeekBar

    private val chipViews = ArrayList<TextView>()
    private val tileSwatches = ArrayList<View>()

    // state
    private var originalUri: Uri? = null
    private var stats: VideoStats? = null

    private var preset = Preset.NATURAL
    private var presetIntensity = 1f
    private var autoOn = true
    private var strength = 0.8f
    private var deepDetails = 0.6f
    private var cleanupOn = true
    private val adj = IntArray(ADJ_ITEMS.size)
    private var adjIndex = 0
    private var suppressAdj = false

    private var trimStartMs = 0L
    private var trimEndMs = 0L

    private var exportLimit = 1080   // 720 / 1080 / 0 = original
    private var previewMode = Mode.CUSTOM
    private var showingOriginal = false
    private var exporting = false
    private var seekDragging = false
    private var analysisToken = 0

    private var player: ExoPlayer? = null
    private var lastPosition = 0L
    private var lastVideoSize: VideoSize? = null

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private val progressPoll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                progressBar.progress = progressHolder.progress
            }
            handler.postDelayed(this, 300)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updatePlaybackUi()
            handler.postDelayed(this, 250)
        }
    }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onVideoPicked(uri)
        }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        btnPickTop.setOnClickListener { if (!exporting) pickVideo.launch("video/*") }
        btnPickBig.setOnClickListener { pickVideo.launch("video/*") }
        btnExport.setOnClickListener { onExportClicked() }
        val openSettings = View.OnClickListener {
            if (!exporting) startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSettings.setOnClickListener(openSettings)
        tvUpdateBanner.setOnClickListener(openSettings)

        btnPlay.setOnClickListener {
            player?.let { it.playWhenReady = !it.playWhenReady }
        }

        // Chepe dhorle ORIGINAL, charle ENHANCED
        val holdListener = View.OnTouchListener { _, ev ->
            if (stats == null || exporting) return@OnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> setShowingOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setShowingOriginal(false)
            }
            true
        }
        btnCompare.setOnTouchListener(holdListener)
        container.setOnTouchListener(holdListener)

        seekPlay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seekDragging = false
                val p = player ?: return
                val d = p.duration
                if (d != C.TIME_UNSET && d > 0) p.seekTo(d * sb.progress / 1000)
            }
        })

        setupTabs()
        setupAutoPanel()
        setupAdjustPanel()
        setupFiltersPanel()
        setupTrimPanel()

        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        selectTab(0)
        setControlsEnabled(false)
        handler.post(uiTick)
        autoCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateBanner()
    }

    /** App khulle background e update check (3 ghonta por por, setting on thakle). */
    private fun autoCheckForUpdate() {
        if (!UpdateManager.shouldAutoCheckNow(this)) return
        Thread {
            val r = UpdateManager.checkLatest(this)
            UpdateManager.saveResult(this, r)
            runOnUiThread { refreshUpdateBanner() }
        }.start()
    }

    private fun refreshUpdateBanner() {
        val name = if (UpdateManager.isAutoCheckEnabled(this)) UpdateManager.pendingUpdateName(this) else null
        if (name == null) {
            tvUpdateBanner.visibility = View.GONE
        } else {
            tvUpdateBanner.text = "🎉 Update available: $name  —  tap to install"
            tvUpdateBanner.visibility = View.VISIBLE
        }
    }

    private fun bindViews() {
        container = findViewById(R.id.videoContainer)
        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        tvBadge = findViewById(R.id.tvBadge)
        tvTime = findViewById(R.id.tvTime)
        progressBar = findViewById(R.id.progressBar)
        btnPickTop = findViewById(R.id.btnPickTop)
        btnPickBig = findViewById(R.id.btnPickBig)
        btnExport = findViewById(R.id.btnExport)
        btnSettings = findViewById(R.id.btnSettings)
        tvUpdateBanner = findViewById(R.id.tvUpdateBanner)
        btnPlay = findViewById(R.id.btnPlay)
        btnCompare = findViewById(R.id.btnCompare)
        seekPlay = findViewById(R.id.seekPlay)

        tabs = listOf(
            findViewById(R.id.tabAuto), findViewById(R.id.tabAdjust),
            findViewById(R.id.tabFilters), findViewById(R.id.tabTrim)
        )
        panels = listOf(
            findViewById(R.id.panelAuto), findViewById(R.id.panelAdjust),
            findViewById(R.id.panelFilters), findViewById(R.id.panelTrim)
        )

        swAuto = findViewById(R.id.swAuto)
        seekStrength = findViewById(R.id.seekStrength)
        tvStrength = findViewById(R.id.tvStrength)
        seekDetails = findViewById(R.id.seekDetails)
        tvDetails = findViewById(R.id.tvDetails)
        cbCleanup = findViewById(R.id.cbCleanup)

        llAdjChips = findViewById(R.id.llAdjChips)
        tvAdjValue = findViewById(R.id.tvAdjValue)
        seekAdjust = findViewById(R.id.seekAdjust)
        btnAdjReset = findViewById(R.id.btnAdjReset)

        llFilters = findViewById(R.id.llFilters)
        seekIntensity = findViewById(R.id.seekIntensity)
        tvIntensity = findViewById(R.id.tvIntensity)

        tvTrimStart = findViewById(R.id.tvTrimStart)
        tvTrimEnd = findViewById(R.id.tvTrimEnd)
        tvTrimInfo = findViewById(R.id.tvTrimInfo)
        seekTrimStart = findViewById(R.id.seekTrimStart)
        seekTrimEnd = findViewById(R.id.seekTrimEnd)
    }

    override fun onStart() {
        super.onStart()
        if (originalUri != null && !exporting) initPlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        releasePlayer()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ tabs / panels

    private fun setupTabs() {
        tabs.forEachIndexed { i, t -> t.setOnClickListener { selectTab(i) } }
    }

    private fun selectTab(index: Int) {
        panels.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t -> t.setTextColor(if (i == index) accent else gray) }
    }

    private fun setupAutoPanel() {
        swAuto.setOnCheckedChangeListener { _, checked ->
            autoOn = checked
            applyPreview()
        }
        seekStrength.setOnSeekBarChangeListener(simpleSeek(
            onChange = { v ->
                strength = v / 100f
                tvStrength.text = "Strength  $v%"
            },
            onDone = { applyPreview() }
        ))
        seekDetails.setOnSeekBarChangeListener(simpleSeek(
            onChange = { v ->
                deepDetails = v / 100f
                tvDetails.text = "🔍 Deep Details  $v%"
            },
            onDone = { applyPreview() }
        ))
        cbCleanup.setOnCheckedChangeListener { _, checked ->
            cleanupOn = checked
            applyPreview()
        }
    }

    // ---- Adjust ----
    private fun setupAdjustPanel() {
        ADJ_ITEMS.forEachIndexed { i, item ->
            val chip = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                text = item.name
                setOnClickListener { if (stats != null && !exporting) selectAdjust(i) }
            }
            chipViews.add(chip)
            llAdjChips.addView(chip)
        }
        seekAdjust.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (suppressAdj) return
                val item = ADJ_ITEMS[adjIndex]
                adj[adjIndex] = if (item.bipolar) v - 100 else v
                refreshAdjustUi()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { applyPreview() }
        })
        btnAdjReset.setOnClickListener {
            if (stats == null || exporting) return@setOnClickListener
            adj.fill(0)
            selectAdjust(adjIndex)
            applyPreview()
        }
        selectAdjust(0)
    }

    private fun selectAdjust(i: Int) {
        adjIndex = i
        val item = ADJ_ITEMS[i]
        suppressAdj = true
        seekAdjust.max = if (item.bipolar) 200 else 100
        seekAdjust.progress = if (item.bipolar) adj[i] + 100 else adj[i]
        suppressAdj = false
        refreshAdjustUi()
    }

    private fun refreshAdjustUi() {
        val item = ADJ_ITEMS[adjIndex]
        val v = adj[adjIndex]
        tvAdjValue.text = "${item.name}  " + (if (item.bipolar && v > 0) "+$v" else "$v")
        chipViews.forEachIndexed { i, tv ->
            val sel = i == adjIndex
            tv.text = ADJ_ITEMS[i].name + if (adj[i] != 0) " •" else ""
            tv.setTextColor(if (sel) accent else Color.WHITE)
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(if (sel) "#1F3A3D" else "#232327"))
                if (sel) setStroke(dp(1), accent)
            }
        }
    }

    // ---- Filters ----
    private fun setupFiltersPanel() {
        Preset.values().forEach { p ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                background = swatchDrawable(p, false)
            }
            tileSwatches.add(swatch)
            val label = TextView(this).apply {
                text = p.label
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
                addView(swatch)
                addView(label)
                setOnClickListener {
                    if (stats == null || exporting) return@setOnClickListener
                    preset = p
                    refreshFilterUi()
                    applyPreview()
                }
            }
            llFilters.addView(tile)
        }
        seekIntensity.setOnSeekBarChangeListener(simpleSeek(
            onChange = { v ->
                presetIntensity = v / 100f
                tvIntensity.text = "Intensity  $v%"
            },
            onDone = { applyPreview() }
        ))
        refreshFilterUi()
    }

    private fun swatchDrawable(p: Preset, selected: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor(p.c1), Color.parseColor(p.c2))
    ).apply {
        cornerRadius = dp(12).toFloat()
        if (selected) setStroke(dp(3), accent)
    }

    private fun refreshFilterUi() {
        Preset.values().forEachIndexed { i, p ->
            tileSwatches[i].background = swatchDrawable(p, p == preset)
        }
    }

    // ---- Trim ----
    private fun setupTrimPanel() {
        val l = simpleSeek(
            onChange = { _ -> updateTrimFromSeekBars() },
            onDone = { onTrimChanged() }
        )
        seekTrimStart.setOnSeekBarChangeListener(l)
        seekTrimEnd.setOnSeekBarChangeListener(l)
    }

    private fun updateTrimFromSeekBars() {
        val d = stats?.durationMs ?: return
        if (d <= 0) return
        var s = seekTrimStart.progress
        var e = seekTrimEnd.progress
        if (e - s < 50) {   // minimum 5% clip
            if (seekTrimStart.isPressed) s = max(0, e - 50) else e = min(1000, s + 50)
            seekTrimStart.progress = s
            seekTrimEnd.progress = e
        }
        trimStartMs = d * s / 1000
        trimEndMs = d * e / 1000
        tvTrimStart.text = "Start  %.1fs".format(trimStartMs / 1000f)
        tvTrimEnd.text = "End  %.1fs".format(trimEndMs / 1000f)
        tvTrimInfo.text = "Clip length: %.1fs".format((trimEndMs - trimStartMs) / 1000f)
    }

    private fun onTrimChanged() {
        val p = player ?: return
        p.setMediaItem(buildMediaItem())
        p.prepare()
        p.seekTo(0)
        p.playWhenReady = true
    }

    private fun buildMediaItem(): MediaItem {
        val uri = originalUri!!
        val d = stats?.durationMs ?: 0L
        val trimmed = d > 0 && (trimStartMs > 0 || trimEndMs in 1 until d)
        if (!trimmed) return MediaItem.fromUri(uri)
        return MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
            .build()
    }

    // ------------------------------------------------------------------ pick + analyze

    private fun onVideoPicked(uri: Uri) {
        originalUri = uri
        stats = null
        lastPosition = 0L
        lastVideoSize = null
        previewMode = Mode.CUSTOM
        trimStartMs = 0L
        trimEndMs = 0L
        seekTrimStart.progress = 0
        seekTrimEnd.progress = 1000
        btnPickBig.visibility = View.GONE
        setControlsEnabled(false)
        status("Video analyze korchi...")
        releasePlayer()
        initPlayer()

        val token = ++analysisToken
        Thread {
            val s = EnhanceEngine.analyze(this, uri)
            runOnUiThread {
                if (token != analysisToken) return@runOnUiThread
                stats = s
                trimEndMs = s.durationMs
                updateTrimFromSeekBars()
                setControlsEnabled(true)
                applyPreview()
                status(
                    ("Ready. Dark %.0f%% | Brightness %.0f%% | Bright %.0f%%. " +
                        "Video chepe dhorle ORIGINAL dekhabe.")
                        .format(s.p1 * 100, s.p50 * 100, s.p99 * 100)
                )
            }
        }.start()
    }

    // ------------------------------------------------------------------ live preview

    private fun settings() = Settings(
        preset, presetIntensity, autoOn, strength, cleanupOn, deepDetails, adj.copyOf()
    )

    private fun effectsFor(mode: Mode): List<Effect> {
        val s = stats ?: return emptyList()
        return when (mode) {
            Mode.CUSTOM -> listOf(EnhanceEffect(EnhanceEngine.computeParams(s, settings())))
            Mode.BUILTIN -> EnhanceEngine.builtinEffects(s, settings())
            Mode.NONE -> emptyList()
        }
    }

    private fun applyPreview() {
        val p = player ?: return
        val list = if (showingOriginal || stats == null) emptyList() else effectsFor(previewMode)
        p.setVideoEffects(list)
        tvBadge.text = if (showingOriginal) "ORIGINAL" else "ENHANCED"
        tvBadge.setTextColor(if (showingOriginal) Color.WHITE else accent)
        tvBadge.visibility = if (stats == null) View.INVISIBLE else View.VISIBLE
    }

    private fun setShowingOriginal(value: Boolean) {
        if (showingOriginal == value) return
        showingOriginal = value
        applyPreview()
    }

    private fun initPlayer() {
        if (originalUri == null || player != null) return
        val p = ExoPlayer.Builder(this).build()
        p.setVideoSurfaceView(surfaceView)
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                lastVideoSize = videoSize
                fitSurface()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.text = if (isPlaying) "⏸" else "▶"
            }

            override fun onPlayerError(error: PlaybackException) {
                onPreviewError(error)
            }
        })
        p.setMediaItem(buildMediaItem())
        if (stats != null && !showingOriginal) p.setVideoEffects(effectsFor(previewMode))
        p.prepare()
        if (lastPosition > 0) p.seekTo(lastPosition)
        p.playWhenReady = true
        player = p
        tvBadge.visibility = if (stats == null) View.INVISIBLE else View.VISIBLE
    }

    private fun releasePlayer() {
        player?.let {
            lastPosition = it.currentPosition
            it.release()
        }
        player = null
    }

    private fun onPreviewError(error: PlaybackException) {
        when (previewMode) {
            Mode.CUSTOM -> {
                previewMode = Mode.BUILTIN
                status("Custom preview ei phone e chole na, simple mode e jachchi...")
            }
            Mode.BUILTIN -> {
                previewMode = Mode.NONE
                status("Preview effect off. Tobe Export kore dekhte paren.")
            }
            Mode.NONE -> {
                status("Video play error: ${error.errorCodeName}")
                return
            }
        }
        releasePlayer()
        initPlayer()
    }

    private fun updatePlaybackUi() {
        val p = player ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d <= 0) return
        val pos = p.currentPosition.coerceIn(0, d)
        if (!seekDragging) seekPlay.progress = (pos * 1000 / d).toInt()
        tvTime.text = "${fmt(pos)} / ${fmt(d)}"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Video ke container er bhitore aspect ratio thik rekhe boshano. */
    private fun fitSurface() {
        val vs = lastVideoSize ?: return
        val cw = container.width
        val ch = container.height
        if (cw == 0 || ch == 0 || vs.width == 0 || vs.height == 0) return
        val ratio = vs.width * vs.pixelWidthHeightRatio / vs.height
        var w = cw
        var h = (cw / ratio).toInt()
        if (h > ch) {
            h = ch
            w = (ch * ratio).toInt()
        }
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    // ------------------------------------------------------------------ export

    private fun onExportClicked() {
        if (exporting) return
        if (stats == null) {
            Toast.makeText(this, "Age ekta video import korun", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("720p  (fast)", "1080p  (recommended)", "Original  (4K hole slow)")
        AlertDialog.Builder(this)
            .setTitle("Export quality")
            .setItems(items) { _, which ->
                exportLimit = when (which) { 0 -> 720; 1 -> 1080; else -> 0 }
                startExport(Mode.CUSTOM)
            }
            .show()
    }

    /** Portrait hole lomba side er limit ke 16:9 er por scale kore. */
    private fun limitHeightFor(s: VideoStats): Int {
        if (exportLimit == 0) return 0
        val portrait = s.outHeight > s.outWidth
        return if (portrait) exportLimit * 16 / 9 else exportLimit
    }

    private fun presentationFor(s: VideoStats): Effect? {
        val limit = limitHeightFor(s)
        return if (limit > 0 && s.outHeight > limit) Presentation.createForHeight(limit) else null
    }

    private fun targetBitrate(s: VideoStats): Int {
        val limit = limitHeightFor(s)
        val scale = if (limit > 0 && s.outHeight > limit) limit.toFloat() / s.outHeight else 1f
        val w = s.outWidth * scale
        val h = s.outHeight * scale
        val pixelBased = (w * h * min(s.fps, 60f) * 0.12f).toInt()
        val wanted = if (scale < 1f) pixelBased else max(s.bitrate, pixelBased)
        return wanted.coerceIn(3_000_000, 40_000_000)
    }

    private fun startExport(mode: Mode) {
        val s = stats ?: return
        if (originalUri == null) return
        exporting = true
        setControlsEnabled(false)
        releasePlayer()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvBadge.visibility = View.INVISIBLE

        val effects = ArrayList<Effect>(effectsFor(mode))
        presentationFor(s)?.let { effects.add(it) }

        val edited = EditedMediaItem.Builder(buildMediaItem())
            .setEffects(Effects(emptyList(), effects))
            .build()

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        val outFile = File(dir, "enhanced_${System.currentTimeMillis()}.mp4")

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(targetBitrate(s)).build()
            )
            .setEnableFallback(true)
            .build()

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressPoll)
                    progressBar.progress = 100
                    status("Export done, gallery te save korchi...")
                    saveToGallery(outFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(progressPoll)
                    outFile.delete()
                    if (mode == Mode.CUSTOM) {
                        status("Custom mode fail korlo, simple mode e abar try korchi...")
                        startExport(Mode.BUILTIN)
                    } else {
                        finishExport("Export error: ${exportException.errorCodeName}")
                    }
                }
            })
            .build()

        transformer = t
        t.start(edited, outFile.absolutePath)
        handler.post(progressPoll)
        status("Export cholche... app ta khola rakhun.")
    }

    private fun saveToGallery(file: File) {
        Thread {
            val message = try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoEnhance")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val target = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
                )
                if (target == null) {
                    "Gallery te save kora gelo na. File: ${file.absolutePath}"
                } else {
                    contentResolver.openOutputStream(target)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(target, done, null, null)
                    file.delete()
                    "Saved! Gallery > Movies/AutoEnhance folder e dekhun."
                }
            } catch (e: Exception) {
                "Save error: ${e.message}. File: ${file.absolutePath}"
            }
            runOnUiThread { finishExport(message) }
        }.start()
    }

    private fun finishExport(message: String) {
        exporting = false
        transformer = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.visibility = View.INVISIBLE
        setControlsEnabled(stats != null)
        initPlayer()
        applyPreview()
        status(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ helpers

    private fun setControlsEnabled(enabled: Boolean) {
        val views: List<View> = listOf(
            btnCompare, btnPlay, seekPlay, swAuto, seekStrength, seekDetails, cbCleanup,
            seekAdjust, btnAdjReset, seekIntensity, seekTrimStart, seekTrimEnd, btnExport
        )
        views.forEach { it.isEnabled = enabled }
        btnPickTop.isEnabled = !exporting
        btnExport.alpha = if (enabled) 1f else 0.5f
    }

    private fun simpleSeek(onChange: (Int) -> Unit, onDone: () -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) = onChange(v)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = onDone()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun status(text: String) {
        tvStatus.text = text
    }
}
