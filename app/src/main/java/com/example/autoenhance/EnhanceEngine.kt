package com.example.autoenhance

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class Preset(val label: String, val c1: String, val c2: String) {
    NATURAL("Natural", "#8BC34A", "#2E7D32"),
    VIVID("Vivid", "#FF5252", "#FFEB3B"),
    CINEMA("Cinema", "#26A69A", "#FF9800"),
    WARM("Warm", "#FF9800", "#FFE082"),
    COOL("Cool", "#42A5F5", "#80DEEA"),
    NOIR("Noir", "#EEEEEE", "#212121"),
    FADE("Fade", "#CE93D8", "#FFCCBC")
}

/** Adjust panel er slider list. bipolar = -100..+100, noile 0..100 */
class AdjItem(val name: String, val bipolar: Boolean)

val ADJ_ITEMS = listOf(
    AdjItem("Brightness", true),   // 0
    AdjItem("Contrast", true),     // 1
    AdjItem("Saturation", true),   // 2
    AdjItem("Warmth", true),       // 3
    AdjItem("Highlights", true),   // 4
    AdjItem("Shadows", true),      // 5
    AdjItem("Sharpen", false),     // 6
    AdjItem("Details", false),     // 7
    AdjItem("Denoise", false),     // 8
    AdjItem("Vignette", false)     // 9
)

/** User er shob setting ekshathe. */
class Settings(
    val preset: Preset,
    val presetIntensity: Float,   // 0..1
    val autoOn: Boolean,
    val autoStrength: Float,      // 0..1
    val cleanup: Boolean,         // denoise + sharpen
    val deepDetails: Float,       // 0..1
    val adj: IntArray             // ADJ_ITEMS er value
)

/** Video analyze kore pawa tothyo. */
class VideoStats(
    val p1: Float,
    val p50: Float,
    val p99: Float,
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    val avgSat: Float,
    val outWidth: Int,
    val outHeight: Int,
    val bitrate: Int,
    val fps: Float,
    val durationMs: Long
)

/** Shader ke deya shob value. */
class GradeParams(
    val gain: FloatArray,
    val blackPoint: Float,
    val invRange: Float,
    val gamma: Float,
    val contrast: Float,
    val vibrance: Float,
    val saturation: Float,
    val shadowTint: FloatArray,
    val highTint: FloatArray,
    val highlights: Float,
    val shadows: Float,
    val clarity: Float,       // deep details (local contrast)
    val vignette: Float,
    val mono: Float,
    val fade: Float,
    val denoise: Float,
    val sharpen: Float
)

private class Look(
    val contrast: Float, val sat: Float,
    val shadow: FloatArray, val high: FloatArray,
    val mono: Float, val fade: Float, val vignette: Float
)

private val Z3 = floatArrayOf(0f, 0f, 0f)

private fun lookOf(p: Preset): Look = when (p) {
    Preset.NATURAL -> Look(0f, 1f, Z3, Z3, 0f, 0f, 0f)
    Preset.VIVID -> Look(0.06f, 1.12f, Z3, Z3, 0f, 0f, 0f)
    Preset.CINEMA -> Look(0.14f, 0.95f,
        floatArrayOf(-0.020f, 0.000f, 0.030f), floatArrayOf(0.030f, 0.010f, -0.025f), 0f, 0.02f, 0.25f)
    Preset.WARM -> Look(0.03f, 1.04f,
        floatArrayOf(0.010f, 0f, -0.005f), floatArrayOf(0.025f, 0.008f, -0.025f), 0f, 0f, 0.10f)
    Preset.COOL -> Look(0.04f, 1.0f,
        floatArrayOf(-0.010f, 0f, 0.025f), floatArrayOf(-0.015f, 0.005f, 0.030f), 0f, 0f, 0.10f)
    Preset.NOIR -> Look(0.20f, 1f, Z3, Z3, 1f, 0f, 0.30f)
    Preset.FADE -> Look(-0.05f, 0.88f,
        floatArrayOf(0.010f, 0.005f, 0.015f), floatArrayOf(0.015f, 0.008f, 0f), 0f, 0.12f, 0.15f)
}

object EnhanceEngine {

    private val DEFAULT = VideoStats(
        0.03f, 0.42f, 0.92f, 0.4f, 0.4f, 0.4f, 0.3f, 1080, 1920, 0, 30f, 0
    )

    /** Video theke 12ta frame nie histogram, color mean, saturation ber kore. */
    fun analyze(context: Context, uri: Uri): VideoStats {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun meta(k: Int) = r.extractMetadata(k)

            val dur = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val br = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val fps = (meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull() ?: 30f).coerceIn(15f, 60f)
            val swap = rot == 90 || rot == 270
            val outW = if (swap) vh else vw
            val outH = if (swap) vw else vh

            val hist = LongArray(256)
            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var sumSat = 0.0
            var n = 0L

            val samples = 12
            for (i in 0 until samples) {
                val timeUs = dur * 1000L * (2 * i + 1) / (2L * samples)
                val bmp = r.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160
                ) ?: continue
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                for (p in px) {
                    val rr = (p shr 16) and 0xFF
                    val gg = (p shr 8) and 0xFF
                    val bb = p and 0xFF
                    val l = (0.2126 * rr + 0.7152 * gg + 0.0722 * bb).toInt().coerceIn(0, 255)
                    if (l < 8) continue            // pure black / letterbox ignore
                    hist[l]++
                    sumR += rr
                    sumG += gg
                    sumB += bb
                    val mx = max(rr, max(gg, bb))
                    val mn = min(rr, min(gg, bb))
                    sumSat += (mx - mn).toDouble() / mx
                    n++
                }
                bmp.recycle()
            }
            if (n < 200) return DEFAULT.withMeta(outW, outH, br, fps, dur)

            fun pct(q: Double): Float {
                val target = (n * q).toLong()
                var cum = 0L
                for (b in 8..255) {
                    cum += hist[b]
                    if (cum >= target) return b / 255f
                }
                return 1f
            }
            return VideoStats(
                p1 = pct(0.01), p50 = pct(0.50), p99 = pct(0.99),
                meanR = (sumR / n / 255).toFloat(),
                meanG = (sumG / n / 255).toFloat(),
                meanB = (sumB / n / 255).toFloat(),
                avgSat = (sumSat / n).toFloat(),
                outWidth = outW, outHeight = outH, bitrate = br, fps = fps, durationMs = dur
            )
        } catch (e: Exception) {
            return DEFAULT
        } finally {
            r.release()
        }
    }

    private fun VideoStats.withMeta(w: Int, h: Int, br: Int, fps: Float, dur: Long) =
        VideoStats(p1, p50, p99, meanR, meanG, meanB, avgSat,
            if (w > 0) w else outWidth, if (h > 0) h else outHeight, br, fps, dur)

    /** Stats + user setting theke shader parameter. */
    fun computeParams(s: VideoStats, st: Settings): GradeParams {
        val k = if (st.autoOn) st.autoStrength.coerceIn(0f, 1f) else 0f
        val pi = st.presetIntensity.coerceIn(0f, 1f)
        val look = lookOf(st.preset)

        // ---------- AUTO base ----------
        val avg = (s.meanR + s.meanG + s.meanB) / 3f
        fun wb(m: Float) = (1f + (avg / max(m, 0.001f) - 1f) * 0.5f).coerceIn(0.94f, 1.06f)
        val gainAuto = floatArrayOf(wb(s.meanR), wb(s.meanG), wb(s.meanB))

        val bp = min(s.p1, 0.08f) * 0.8f
        val wp = (s.p99 * 1.03f).coerceIn(0.88f, 1.0f)
        val m = ((s.p50 - bp) / (wp - bp)).coerceIn(0.05f, 0.95f)
        val target = when (st.preset) {
            Preset.CINEMA -> 0.40f
            Preset.NOIR -> 0.42f
            else -> 0.47f
        }
        val gammaAuto = (ln(target) / ln(m)).coerceIn(0.65f, 1.2f)
        val range = s.p99 - s.p1
        val contrastAuto = (0.35f - range * 0.3f).coerceIn(0.08f, 0.30f)
        val vibAuto = (0.55f - s.avgSat).coerceIn(0.15f, 0.5f)
        val noise = ((0.45f - s.p50) * 2.2f).coerceIn(0f, 1f)

        // dark hole shadow lift, khub bright hole highlight komano
        val shadowsAuto = (0.30f * (0.40f - s.p50) / 0.40f).coerceIn(0f, 0.30f) +
            (if (s.p1 < 0.06f) 0.05f else 0f)
        val highlightsAuto = if (s.p99 > 0.97f) -0.20f else if (s.p99 > 0.92f) -0.08f else 0f

        // Deep details: dull/flat video te beshi, noisy dark video te kom
        val clarityAuto = st.deepDetails * (1f - 0.5f * noise) * (0.9f + 0.5f * (1f - range))

        // ---------- strength diye neutral ar auto er majhe blend ----------
        val gamma0 = 1f + (gammaAuto - 1f) * k
        val bpE = bp * k
        val wpE = 1f - (1f - wp) * k
        val gain = FloatArray(3) { 1f + (gainAuto[it] - 1f) * k }

        // ---------- manual adjust ----------
        val a = st.adj
        fun v(i: Int) = a[i] / 100f

        val gamma = gamma0 * 0.6f.pow(v(0))
        val contrast = (contrastAuto * k + look.contrast * pi + v(1) * 0.4f).coerceIn(-0.5f, 0.6f)
        val saturation = (1f + v(2) * 0.6f) * (1f + (look.sat - 1f) * pi)
        val w = v(3)
        gain[0] *= 1f + 0.08f * w
        gain[2] *= 1f - 0.08f * w

        val shadows = (shadowsAuto * k + v(5)).coerceIn(-1f, 1f)
        val highlights = (highlightsAuto * k + v(4)).coerceIn(-1f, 1f)

        val sharpenBase = if (st.cleanup) 0.7f - 0.4f * noise else 0f
        val denoiseBase = if (st.cleanup) 0.25f + 0.65f * noise else 0f
        val sharpen = (sharpenBase + v(6) * 1.2f).coerceIn(0f, 1.5f)
        val clarity = (clarityAuto + v(7)).coerceIn(0f, 2f)
        val denoise = (denoiseBase + v(8) * 0.8f).coerceIn(0f, 1f)
        val vignette = (look.vignette * pi + v(9) * 0.7f).coerceIn(0f, 0.9f)

        return GradeParams(
            gain = gain,
            blackPoint = bpE,
            invRange = 1f / (wpE - bpE),
            gamma = gamma,
            contrast = contrast,
            vibrance = (vibAuto * k + (if (st.preset == Preset.VIVID) 0.2f * pi else 0f)).coerceAtMost(0.7f),
            saturation = saturation,
            shadowTint = FloatArray(3) { look.shadow[it] * pi },
            highTint = FloatArray(3) { look.high[it] * pi },
            highlights = highlights,
            shadows = shadows,
            clarity = clarity,
            vignette = vignette,
            mono = look.mono * pi,
            fade = look.fade * pi,
            denoise = denoise,
            sharpen = sharpen
        )
    }

    /** Fallback: custom shader kaj na korle Media3 er built-in effect. */
    @OptIn(UnstableApi::class)
    fun builtinEffects(s: VideoStats, st: Settings): List<Effect> {
        val k = if (st.autoOn) st.autoStrength.coerceIn(0f, 1f) else 0f
        val brightness = (((0.45f - s.p50) * 0.5f).coerceIn(-0.15f, 0.2f) * k +
            st.adj[0] / 100f * 0.25f).coerceIn(-0.5f, 0.5f)
        val contrast = (((0.8f - (s.p99 - s.p1)) * 0.5f).coerceIn(0f, 0.3f) * k +
            st.adj[1] / 100f * 0.3f).coerceIn(-0.6f, 0.6f)
        val saturation = (18f * k + st.adj[2] / 100f * 40f).coerceIn(-100f, 100f)
        return listOf(
            Brightness(brightness),
            Contrast(contrast),
            HslAdjustment.Builder().adjustSaturation(saturation).build()
        )
    }
}
