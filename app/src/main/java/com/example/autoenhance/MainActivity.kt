package com.example.autoenhance

import android.content.ContentValues
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var videoView: VideoView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPick: Button
    private lateinit var btnEnhance: Button
    private lateinit var btnToggle: Button

    private var originalUri: Uri? = null
    private var enhancedUri: Uri? = null
    private var showingEnhanced = false

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

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                originalUri = uri
                enhancedUri = null
                showingEnhanced = false
                btnEnhance.isEnabled = true
                btnToggle.isEnabled = false
                status("Video select hoyeche. Ebar 'Auto Enhance' chapun.")
                play(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnPick = findViewById(R.id.btnPick)
        btnEnhance = findViewById(R.id.btnEnhance)
        btnToggle = findViewById(R.id.btnToggle)

        btnPick.setOnClickListener { pickVideo.launch("video/*") }
        btnEnhance.setOnClickListener { startEnhance() }
        btnToggle.setOnClickListener { toggleBeforeAfter() }
    }

    // ---------- Step 1: video analyze kore auto value ber kora ----------

    private fun startEnhance() {
        val uri = originalUri ?: return
        setBusy(true)
        status("Video analyze korchi...")

        Thread {
            val stats = try {
                analyze(uri)
            } catch (e: Exception) {
                null
            }
            val (mean, std) = stats ?: Pair(0.45, 0.20)

            // Dark hole brightness barabe, beshi alo hole komabe
            val brightness = ((0.45 - mean) * 0.6).coerceIn(-0.20, 0.25).toFloat()
            // Contrast kom hole barabe
            val contrast = ((0.22 - std) * 2.0).coerceIn(0.0, 0.30).toFloat()
            // Halka saturation boost
            val saturation = 15f

            runOnUiThread {
                status(
                    "Brightness %+.2f, Contrast %+.2f, Saturation +%d apply korchi..."
                        .format(brightness, contrast, saturation.toInt())
                )
                export(uri, brightness, contrast, saturation)
            }
        }.start()
    }

    /** Kichu frame sample kore gorosori brightness (mean) ar contrast (std) ber kore. */
    private fun analyze(uri: Uri): Pair<Double, Double> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val samples = 6
            var sumMean = 0.0
            var sumStd = 0.0
            var count = 0

            for (i in 0 until samples) {
                val timeUs = (durationMs * 1000.0 * (i + 0.5) / samples).toLong()
                val bmp = retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96
                ) ?: continue

                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)

                var s = 0.0
                var s2 = 0.0
                for (p in px) {
                    val l = (0.299 * Color.red(p) + 0.587 * Color.green(p) +
                        0.114 * Color.blue(p)) / 255.0
                    s += l
                    s2 += l * l
                }
                val mean = s / px.size
                val variance = (s2 / px.size - mean * mean).coerceAtLeast(0.0)
                sumMean += mean
                sumStd += sqrt(variance)
                count++
            }

            return if (count == 0) Pair(0.45, 0.20) else Pair(sumMean / count, sumStd / count)
        } finally {
            retriever.release()
        }
    }

    // ---------- Step 2: Media3 Transformer diye effect apply + export ----------

    private fun export(uri: Uri, brightness: Float, contrast: Float, saturation: Float) {
        val videoEffects = listOf<Effect>(
            Brightness(brightness),
            Contrast(contrast),
            HslAdjustment.Builder().adjustSaturation(saturation).build()
        )

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir
        val outFile = File(dir, "enhanced_${System.currentTimeMillis()}.mp4")

        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressPoll)
                    progressBar.progress = 100
                    enhancedUri = Uri.fromFile(outFile)
                    showingEnhanced = true
                    btnToggle.isEnabled = true
                    btnToggle.text = "Ekhon: AFTER (chaple BEFORE dekhabe)"
                    play(enhancedUri!!)
                    setBusy(false)
                    status("Export done! Gallery te save hochche (Movies/AutoEnhance)...")
                    saveToGallery(outFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(progressPoll)
                    setBusy(false)
                    status("Error: ${exportException.message}")
                }
            })
            .build()

        transformer = t
        progressBar.progress = 0
        t.start(edited, outFile.absolutePath)
        handler.post(progressPoll)
        status("Export cholche...")
    }

    // ---------- Gallery te save ----------

    private fun saveToGallery(file: File) {
        Thread {
            val message = try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoEnhance")
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
                    "Done! Gallery te save hoyeche: Movies/AutoEnhance"
                }
            } catch (e: Exception) {
                "Save error: ${e.message}. File: ${file.absolutePath}"
            }
            runOnUiThread { status(message) }
        }.start()
    }

    // ---------- Preview / Before-After ----------

    private fun toggleBeforeAfter() {
        val orig = originalUri ?: return
        val enh = enhancedUri ?: return
        showingEnhanced = !showingEnhanced
        if (showingEnhanced) {
            play(enh)
            btnToggle.text = "Ekhon: AFTER (chaple BEFORE dekhabe)"
        } else {
            play(orig)
            btnToggle.text = "Ekhon: BEFORE (chaple AFTER dekhabe)"
        }
    }

    private fun play(uri: Uri) {
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { it.isLooping = true }
        videoView.start()
    }

    private fun setBusy(busy: Boolean) {
        btnPick.isEnabled = !busy
        btnEnhance.isEnabled = !busy && originalUri != null
        progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun status(text: String) {
        tvStatus.text = text
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        super.onDestroy()
    }
}
