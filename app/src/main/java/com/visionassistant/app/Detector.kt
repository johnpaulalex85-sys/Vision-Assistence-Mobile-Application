package com.visionassistant.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Box coordinates are normalized 0..1 relative to the (upright) camera frame. */
data class Detection(
    val label: String, val score: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float
)

class Detector(context: Context, modelFile: String = "yolov8n.tflite") {
    private val interpreter: Interpreter
    private val labels: List<String> =
        context.assets.open("labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
    private val size: Int
    private val input: ByteBuffer
    private val pixels: IntArray
    private val output: Array<Array<FloatArray>>
    private val channelsFirst: Boolean
    private val numBoxes: Int
    private val numChannels: Int

    var confThreshold = 0.40f
    var iouThreshold = 0.45f

    init {
        val fd = context.assets.openFd(modelFile)
        val mapped = fd.createInputStream().channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })

        size = interpreter.getInputTensor(0).shape()[1]          // [1, H, W, 3]
        input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)

        val s = interpreter.getOutputTensor(0).shape()           // [1, 84, N] or [1, N, 84]
        channelsFirst = s[1] < s[2]
        numChannels = if (channelsFirst) s[1] else s[2]
        numBoxes = if (channelsFirst) s[2] else s[1]
        output = Array(1) { Array(s[1]) { FloatArray(s[2]) } }
    }

    private fun v(c: Int, i: Int) = if (channelsFirst) output[0][c][i] else output[0][i][c]

    fun detect(frame: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(frame, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== frame) scaled.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)

        val numClasses = numChannels - 4
        data class Raw(val cls: Int, val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)
        val raws = ArrayList<Raw>()
        var maxCoord = 0f
        for (i in 0 until numBoxes) {
            var best = 0; var bestScore = 0f
            for (c in 0 until numClasses) {
                val sc = v(4 + c, i)
                if (sc > bestScore) { bestScore = sc; best = c }
            }
            if (bestScore < confThreshold) continue
            val r = Raw(best, bestScore, v(0, i), v(1, i), v(2, i), v(3, i))
            maxCoord = maxOf(maxCoord, r.cx, r.cy, r.w, r.h)
            raws.add(r)
        }
        // Some exports give pixel coordinates, newer ones give 0..1.
        val k = if (maxCoord <= 1.5f) 1f else 1f / size

        val dets = raws.map {
            Detection(
                labels.getOrElse(it.cls) { "object" }, it.score,
                ((it.cx - it.w / 2) * k).coerceIn(0f, 1f), ((it.cy - it.h / 2) * k).coerceIn(0f, 1f),
                ((it.cx + it.w / 2) * k).coerceIn(0f, 1f), ((it.cy + it.h / 2) * k).coerceIn(0f, 1f)
            )
        }
        return nms(dets)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in dets.sortedByDescending { it.score }) {
            if (kept.none { it.label == d.label && iou(it, d) > iouThreshold }) kept.add(d)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val iw = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)
        val ih = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        val ua = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (ua <= 0) 0f else inter / ua
    }

    fun close() = interpreter.close()
}
