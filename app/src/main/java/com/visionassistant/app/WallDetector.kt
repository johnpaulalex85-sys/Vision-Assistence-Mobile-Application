package com.visionassistant.app

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Box is normalized 0..1. coverage = fraction of frame; corridor = fraction of the walking corridor filled. */
data class WallResult(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val coverage: Float, val corridor: Float
)

/**
 * Port of the backend's smooth-surface wall detector (no OpenCV needed).
 * A wall = the largest low-texture region that fills the corridor ahead.
 * Textured walls (brick, tile, wallpaper) are not handled in this version.
 */
class WallDetector(
    private val minCoverage: Float = 0.30f,
    private val textureThreshold: Float = 3.0f,   // raise if real walls are missed, lower if clutter is mistaken for walls
    private val corrL: Float = 0.32f,
    private val corrR: Float = 0.68f,
    private val confirmFrames: Int = 2,
    private val holdFrames: Int = 3
) {
    private val aw = 160
    private var streak = 0
    private var miss = 0
    private var confirmed = false
    private var last: WallResult? = null

    fun detect(frame: Bitmap, others: List<Detection>): WallResult? {
        val raw = analyse(frame, others)
        if (raw != null) {
            streak++; miss = 0; last = raw
            if (streak >= confirmFrames) confirmed = true
            return if (confirmed) raw else null
        }
        streak = 0
        if (confirmed && miss < holdFrames) { miss++; return last }   // brief dropout: keep reporting
        confirmed = false; last = null
        return null
    }

    private fun analyse(frame: Bitmap, others: List<Detection>): WallResult? {
        val w = aw
        val h = max(8, (frame.height * aw / frame.width.toFloat()).toInt())
        val small = Bitmap.createScaledBitmap(frame, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== frame) small.recycle()

        val gray = FloatArray(w * h)
        var sum = 0.0
        for (i in px.indices) {
            val p = px[i]
            val g = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) / 256f
            gray[i] = g; sum += g
        }
        if (sum / (w * h) < 20) return null          // lens covered / pitch dark: not a wall

        val g = blur(blur(gray, w, h), w, h)

        // Texture 1: gradient magnitude (Sobel)
        val grad = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -g[i - w - 1] + g[i - w + 1] - 2 * g[i - 1] + 2 * g[i + 1] - g[i + w - 1] + g[i + w + 1]
            val gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]
            grad[i] = sqrt(gx * gx + gy * gy)
        }

        // Texture 2: local standard deviation over 7x7 via integral images
        val iw = w + 1
        val s1 = DoubleArray(iw * (h + 1))
        val s2 = DoubleArray(iw * (h + 1))
        for (y in 0 until h) {
            var r1 = 0.0; var r2 = 0.0
            for (x in 0 until w) {
                val v = g[y * w + x].toDouble()
                r1 += v; r2 += v * v
                s1[(y + 1) * iw + x + 1] = s1[y * iw + x + 1] + r1
                s2[(y + 1) * iw + x + 1] = s2[y * iw + x + 1] + r2
            }
        }
        val t = textureThreshold
        var smooth = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - 3); val x1 = min(w, x + 4)
            val y0 = max(0, y - 3); val y1 = min(h, y + 4)
            val n = ((x1 - x0) * (y1 - y0)).toDouble()
            val a = s1[y1 * iw + x1] - s1[y0 * iw + x1] - s1[y1 * iw + x0] + s1[y0 * iw + x0]
            val b = s2[y1 * iw + x1] - s2[y0 * iw + x1] - s2[y1 * iw + x0] + s2[y0 * iw + x0]
            val mu = a / n
            val std = sqrt(max(b / n - mu * mu, 0.0)).toFloat()
            if (grad[y * w + x] < 4f * t && std < 1.6f * t) smooth[y * w + x] = 1
        }

        // Pixels that belong to another detected object are not wall
        for (d in others) {
            if (d.label == "wall") continue
            val xa = max(0, (d.x1 * w).toInt()); val xb = min(w, ceil(d.x2 * w).toInt())
            val ya = max(0, (d.y1 * h).toInt()); val yb = min(h, ceil(d.y2 * h).toInt())
            for (y in ya until yb) for (x in xa until xb) smooth[y * w + x] = 0
        }

        // Remove speckle, then close small gaps (stains, switches)
        smooth = morph(morph(smooth, w, h, 1, false), w, h, 1, true)
        smooth = morph(morph(smooth, w, h, 4, true), w, h, 4, false)

        // Largest 4-connected smooth region
        val labels = IntArray(w * h)
        val stack = IntArray(w * h)
        var bestLabel = 0; var bestSize = 0; var next = 0
        for (start in 0 until w * h) {
            if (smooth[start].toInt() == 0 || labels[start] != 0) continue
            next++
            var sp = 0; var size = 0
            stack[sp++] = start; labels[start] = next
            while (sp > 0) {
                val c = stack[--sp]; size++
                val cx = c % w
                if (cx > 0 && smooth[c - 1].toInt() == 1 && labels[c - 1] == 0) { labels[c - 1] = next; stack[sp++] = c - 1 }
                if (cx < w - 1 && smooth[c + 1].toInt() == 1 && labels[c + 1] == 0) { labels[c + 1] = next; stack[sp++] = c + 1 }
                if (c >= w && smooth[c - w].toInt() == 1 && labels[c - w] == 0) { labels[c - w] = next; stack[sp++] = c - w }
                if (c < w * h - w && smooth[c + w].toInt() == 1 && labels[c + w] == 0) { labels[c + w] = next; stack[sp++] = c + w }
            }
            if (size > bestSize) { bestSize = size; bestLabel = next }
        }
        if (bestLabel == 0) return null
        val coverage = bestSize / (w * h).toFloat()
        if (coverage < minCoverage) return null

        // How much of the walking corridor is that region?
        val cx1 = (w * corrL).toInt(); val cx2 = (w * corrR).toInt(); val cy1 = (h * 0.10f).toInt()
        var inCorr = 0
        for (y in cy1 until h) for (x in cx1 until cx2) if (labels[y * w + x] == bestLabel) inCorr++
        val corrArea = (cx2 - cx1) * (h - cy1)
        val corridor = if (corrArea > 0) inCorr / corrArea.toFloat() else 0f
        if (corridor < 0.55f) return null

        // Box from 1st..99th percentile extents so a thin leak does not stretch it
        val cols = IntArray(w); val rows = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) if (labels[y * w + x] == bestLabel) { cols[x]++; rows[y]++ }
        val bx1 = percentile(cols, 0.01f); val bx2 = percentile(cols, 0.99f)
        val by1 = percentile(rows, 0.01f); val by2 = percentile(rows, 0.99f)
        return WallResult(bx1 / w.toFloat(), by1 / h.toFloat(), (bx2 + 1f) / w, (by2 + 1f) / h, coverage, corridor)
    }

    private fun percentile(counts: IntArray, q: Float): Int {
        val total = counts.sum(); var acc = 0
        val target = total * q
        for (i in counts.indices) { acc += counts[i]; if (acc >= target) return i }
        return counts.size - 1
    }

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            tmp[y * w + x] = (src[y * w + max(x - 1, 0)] + 2 * src[y * w + x] + src[y * w + min(x + 1, w - 1)]) / 4f
        }
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = (tmp[max(y - 1, 0) * w + x] + 2 * tmp[y * w + x] + tmp[min(y + 1, h - 1) * w + x]) / 4f
        }
        return out
    }

    /** Separable square dilate/erode of a 0/1 mask; out-of-bounds pixels are ignored (like OpenCV). */
    private fun morph(m: ByteArray, w: Int, h: Int, r: Int, dilate: Boolean): ByteArray {
        val tmp = ByteArray(w * h); val out = ByteArray(w * h)
        val stop = if (dilate) 1 else 0
        for (y in 0 until h) for (x in 0 until w) {
            var v = 1 - stop
            for (k in -r..r) {
                val xx = x + k
                if (xx < 0 || xx >= w) continue
                if (m[y * w + xx].toInt() == stop) { v = stop; break }
            }
            tmp[y * w + x] = v.toByte()
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 1 - stop
            for (k in -r..r) {
                val yy = y + k
                if (yy < 0 || yy >= h) continue
                if (tmp[yy * w + x].toInt() == stop) { v = stop; break }
            }
            out[y * w + x] = v.toByte()
        }
        return out
    }
}
