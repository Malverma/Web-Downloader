package com.webdownloader.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.webdownloader.data.db.Quality
import com.webdownloader.data.prefs.AppSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

data class QualitySpec(val original: Boolean, val maxWidth: Int, val quality: Int, val jpeg: Boolean)

fun Quality.spec(settings: AppSettings): QualitySpec = when (this) {
    Quality.ORIGINAL -> QualitySpec(original = true, maxWidth = 0, quality = 100, jpeg = false)
    Quality.CUSTOM -> QualitySpec(false, settings.customMaxWidth, settings.customQuality, settings.customJpeg)
    else -> QualitySpec(false, maxWidth, webpQuality, jpeg = false)
}

object ImageProcessor {
    class Processed(val bytes: ByteArray, val ext: String)

    private const val WEBP_MAX_DIMENSION = 16383
    private const val MAX_DECODE_BYTES = 150_000_000L

    fun sniffExt(b: ByteArray): String = when {
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
        b.size > 12 && String(b, 4, 8, Charsets.ISO_8859_1).startsWith("ftypavi") -> "avif"
        else -> "jpg"
    }

    /** Validates the download and resizes/re-encodes it according to [spec]. */
    fun process(bytes: ByteArray, spec: QualitySpec): Processed {
        val ext = sniffExt(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            // AVIF may not be decodable on older Android; keep it untouched.
            if (ext == "avif") return Processed(bytes, ext)
            throw IOException("Downloaded file is not an image")
        }
        if (spec.original || ext == "gif") return Processed(bytes, ext)

        val targetW = if (spec.maxWidth > 0) min(spec.maxWidth, w) else w
        var sample = 1
        while (w / (sample * 2) >= targetW) sample *= 2
        while ((w.toLong() / sample) * (h.toLong() / sample) * 4 > MAX_DECODE_BYTES) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Could not decode image")
        val outW = min(targetW, decoded.width)
        val outH = max(1, (decoded.height.toLong() * outW / decoded.width).toInt())
        val scaled = if (outW != decoded.width) Bitmap.createScaledBitmap(decoded, outW, outH, true) else decoded

        val useJpeg = spec.jpeg || outH > WEBP_MAX_DIMENSION || outW > WEBP_MAX_DIMENSION
        val format = when {
            useJpeg -> Bitmap.CompressFormat.JPEG
            Build.VERSION.SDK_INT >= 30 -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(format, spec.quality.coerceIn(1, 100), out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!ok) throw IOException("Could not encode image")

        val encoded = out.toByteArray()
        // Not resized and re-encoding didn't help: keep the original bytes.
        if (outW >= w && encoded.size >= bytes.size) return Processed(bytes, ext)
        return Processed(encoded, if (useJpeg) "jpg" else "webp")
    }
}
