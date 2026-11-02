package com.visionassistant.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FaceResult(val name: String?, val zone: String, val score: Float)

/**
 * ML Kit finds faces; an optional MobileFaceNet-style TFLite model (assets/mobilefacenet.tflite,
 * 112x112x3 float input, one embedding vector out) recognises them. Embeddings are stored on the phone.
 * Without the model file, `available` is false and recognition is disabled.
 */
class FaceRecognizer(context: Context, private val executor: Executor) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
    )
    private var interpreter: Interpreter? = null
    private var inSize = 112
    private var outDim = 192
    private val store = LinkedHashMap<String, MutableList<FloatArray>>()
    private val file = File(context.filesDir, "faces.json")

    val available: Boolean get() = interpreter != null
    var threshold = 0.60f          // cosine similarity; raise if strangers are matched, lower if friends are missed

    init {
        try {
            val fd = context.assets.openFd("mobilefacenet.tflite")
            val mapped = fd.createInputStream().channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val it = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) })
            inSize = it.getInputTensor(0).shape()[1]
            outDim = it.getOutputTensor(0).shape()[1]
            interpreter = it
        } catch (e: Exception) { interpreter = null }
        load()
    }

    fun names(): List<String> = synchronized(store) { store.keys.toList() }

    fun delete(name: String): Boolean = synchronized(store) {
        val key = store.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: return false
        store.remove(key); save(); true
    }

    fun identify(frame: Bitmap, cb: (List<FaceResult>) -> Unit) {
        detectFaces(frame) { rects ->
            val results = rects.map { r ->
                val cx = (r.centerX().toFloat() / frame.width)
                val zone = if (cx < 0.32f) "left" else if (cx > 0.68f) "right" else "ahead"
                val (name, score) = match(embed(crop(frame, r)))
                FaceResult(name, zone, score)
            }
            cb(results)
        }
    }

    fun register(name: String, frame: Bitmap, cb: (String) -> Unit) {
        detectFaces(frame) { rects ->
            if (rects.isEmpty()) { cb("I could not see a face. Point the camera at the person and try again."); return@detectFaces }
            val r = rects.maxByOrNull { it.width() * it.height() }!!
            val emb = embed(crop(frame, r))
            synchronized(store) {
                val key = store.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
                val list = store.getOrPut(key) { mutableListOf() }
                list.add(emb)
                if (list.size > 5) list.removeAt(0)
                save()
            }
            cb("Remembered $name")
        }
    }

    private fun detectFaces(frame: Bitmap, cb: (List<Rect>) -> Unit) {
        detector.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener(executor) { faces -> cb(faces.map { it.boundingBox }) }
            .addOnFailureListener(executor) { cb(emptyList()) }
    }

    private fun crop(src: Bitmap, r: Rect): Bitmap {
        val padX = (r.width() * 0.1f).toInt(); val padY = (r.height() * 0.1f).toInt()
        val l = max(0, r.left - padX); val t = max(0, r.top - padY)
        val rr = min(src.width, r.right + padX); val b = min(src.height, r.bottom + padY)
        return Bitmap.createBitmap(src, l, t, max(1, rr - l), max(1, b - t))
    }

    private fun embed(face: Bitmap): FloatArray {
        val it = interpreter ?: return FloatArray(0)
        val scaled = Bitmap.createScaledBitmap(face, inSize, inSize, true)
        val px = IntArray(inSize * inSize)
        scaled.getPixels(px, 0, inSize, 0, 0, inSize, inSize)
        val buf = ByteBuffer.allocateDirect(4 * inSize * inSize * 3).order(ByteOrder.nativeOrder())
        for (p in px) {
            buf.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128f)
            buf.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128f)
            buf.putFloat(((p and 0xFF) - 127.5f) / 128f)
        }
        buf.rewind()
        val out = Array(1) { FloatArray(outDim) }
        it.run(buf, out)
        var norm = 0f
        for (v in out[0]) norm += v * v
        norm = sqrt(norm).coerceAtLeast(1e-6f)
        return FloatArray(outDim) { out[0][it] / norm }
    }

    private fun match(emb: FloatArray): Pair<String?, Float> = synchronized(store) {
        if (emb.isEmpty()) return null to 0f
        var best: String? = null; var bestScore = -1f
        for ((n, list) in store) for (e in list) {
            if (e.size != emb.size) continue
            var dot = 0f
            for (i in e.indices) dot += e[i] * emb[i]
            if (dot > bestScore) { bestScore = dot; best = n }
        }
        if (bestScore >= threshold) best to bestScore else null to bestScore
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in store) {
            val arr = JSONArray()
            v.forEach { e -> arr.put(JSONArray().also { a -> e.forEach { x -> a.put(x.toDouble()) } }) }
            o.put(k, arr)
        }
        file.writeText(o.toString())
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                store[k] = MutableList(arr.length()) { i ->
                    val a = arr.getJSONArray(i)
                    FloatArray(a.length()) { j -> a.getDouble(j).toFloat() }
                }
            }
        } catch (e: Exception) { /* corrupt file: start empty */ }
    }
}
