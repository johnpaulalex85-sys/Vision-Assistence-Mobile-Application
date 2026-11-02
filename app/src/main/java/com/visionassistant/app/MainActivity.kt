package com.visionassistant.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var status: TextView
    private lateinit var btnAlerts: Button

    private var detector: Detector? = null
    private val walls = WallDetector()
    private val engine = HazardEngine()
    private lateinit var speaker: Speaker
    private lateinit var textReader: TextReader
    private lateinit var faces: FaceRecognizer
    private lateinit var voice: VoiceController
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var bgExecutor: ExecutorService

    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var latestDets: List<Detection> = emptyList()
    @Volatile private var latestWall: WallResult? = null
    @Volatile private var alertsOn = true
    @Volatile private var awaitingName = false
    @Volatile private var quietUntil = 0L

    private val faceBusy = AtomicBoolean(false)
    private var lastFaceRun = 0L
    private val faceSpoken = HashMap<String, Long>()
    private var lastFrameMs = 0L
    private var fps = 0f

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (has(Manifest.permission.CAMERA)) startCamera() else status.text = "Camera permission is required."
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        btnAlerts = findViewById(R.id.btnAlerts)

        analysisExecutor = Executors.newSingleThreadExecutor()
        bgExecutor = Executors.newSingleThreadExecutor()
        speaker = Speaker(this)
        textReader = TextReader()
        faces = FaceRecognizer(this, bgExecutor)
        voice = VoiceController(this, onText = { handleCommand(it) }, onError = { say(it) })

        findViewById<Button>(R.id.btnListen).setOnClickListener { listen() }
        findViewById<Button>(R.id.btnRead).setOnClickListener { readText() }
        findViewById<Button>(R.id.btnDescribe).setOnClickListener { describe() }
        findViewById<Button>(R.id.btnWho).setOnClickListener { identifyFaces() }
        findViewById<Button>(R.id.btnRemember).setOnClickListener { startRemember() }
        btnAlerts.setOnClickListener { setAlerts(!alertsOn) }

        detector = try { Detector(this) } catch (e: Exception) { null }
        if (detector == null) {
            status.text = "Model missing: put yolov8n.tflite in app/src/main/assets"
            say("The detection model is missing.")
        } else {
            say("Vision Assistant ready. Say help to hear the commands.")
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { !has(it) }
        if (needed.isEmpty()) startCamera() else permissions.launch(needed.toTypedArray())
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            status.text = "Scanning..."
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val det = detector ?: return
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val frame = if (rot == 0) raw else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }

            val dets = det.detect(frame)
            val wall = walls.detect(frame, dets)
            latestFrame = frame; latestDets = dets; latestWall = wall

            val now = SystemClock.elapsedRealtime()
            if (alertsOn && !voice.listening && !awaitingName) {
                val alert = engine.evaluate(dets, now) ?: wall?.let { engine.evaluateWall(it, now) }
                if (alert != null && (alert.urgent || now >= quietUntil)) {
                    speaker.alert(alert.message, alert.urgent)
                    if (alert.urgent) vibrate()
                }
                maybeIdentifyFaces(frame, dets, now)
            }

            if (lastFrameMs != 0L) {
                val inst = 1000f / (now - lastFrameMs).coerceAtLeast(1)
                fps = if (fps == 0f) inst else fps * 0.9f + inst * 0.1f
            }
            lastFrameMs = now
            val shown = if (wall != null) dets + Detection("wall", 0.9f, wall.x1, wall.y1, wall.x2, wall.y2) else dets
            overlay.update(shown, frame.width, frame.height)
            runOnUiThread { status.text = "${dets.size} objects${if (wall != null) " + wall" else ""}  |  ${"%.1f".format(fps)} FPS" }
        } finally {
            image.close()
        }
    }

    // ---------------------------------------------------------------- features

    private fun say(text: String) {
        quietUntil = SystemClock.elapsedRealtime() + text.length * 65L + 1000
        speaker.speak(text, interrupt = true)
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun readText() {
        val frame = latestFrame ?: return say("The camera is not ready yet.")
        say("Reading.")
        textReader.read(frame) { text ->
            say(if (text.isBlank()) "I could not find any text. Move closer and hold steady." else text)
        }
    }

    private fun describe() {
        if (latestFrame == null) return say("The camera is not ready yet.")
        say(SceneDescriber.describe(latestDets, latestWall))
    }

    private fun faceModelMissing(): Boolean {
        if (faces.available) return false
        say("Face recognition needs the mobilefacenet model file in the app assets.")
        return true
    }

    private fun identifyFaces() {
        if (faceModelMissing()) return
        val frame = latestFrame ?: return say("The camera is not ready yet.")
        faces.identify(frame) { results ->
            if (results.isEmpty()) say("I do not see any face.") else say(
                results.joinToString(", and ") { r ->
                    val who = r.name ?: "an unknown person"
                    if (r.zone == "ahead") "$who ahead" else "$who on your ${r.zone}"
                }
            )
        }
    }

    private fun startRemember() {
        if (faceModelMissing()) return
        awaitingName = true
        speaker.speak("Point the camera at the face. Say the name after the beep.", interrupt = true) { listen() }
    }

    private fun rememberFace(name: String) {
        val frame = latestFrame ?: return say("The camera is not ready yet.")
        faces.register(name, frame) { say(it) }
    }

    private fun forgetFace(name: String) {
        say(if (faces.delete(name)) "Forgot $name" else "I do not know anyone called ${name.trim()}")
    }

    private fun maybeIdentifyFaces(frame: Bitmap, dets: List<Detection>, now: Long) {
        if (!faces.available || now - lastFaceRun < 3000 || faceBusy.get()) return
        if (dets.none { it.label == "person" }) return
        lastFaceRun = now
        faceBusy.set(true)
        faces.identify(frame) { results ->
            faceBusy.set(false)
            val t = SystemClock.elapsedRealtime()
            val fresh = results.filter { it.name != null && t - (faceSpoken[it.name] ?: 0L) > 15000 }
            if (fresh.isNotEmpty()) {
                fresh.forEach { faceSpoken[it.name!!] = t }
                speaker.alert(fresh.joinToString(", ") { r ->
                    if (r.zone == "ahead") "${r.name} ahead" else "${r.name} on your ${r.zone}"
                }, false)
            }
        }
    }

    private fun setAlerts(on: Boolean) {
        alertsOn = on
        btnAlerts.text = if (on) "Alerts: ON (tap to pause)" else "Alerts: PAUSED (tap to resume)"
        say(if (on) "Alerts resumed." else "Alerts paused.")
    }

    private fun listen() {
        speaker.stop()
        status.text = "Listening..."
        voice.listen()
    }

    private fun title(s: String) = s.trim().split(" ").joinToString(" ") { w ->
        w.replaceFirstChar { it.uppercase() }
    }

    private fun handleCommand(raw: String) {
        if (awaitingName) {
            awaitingName = false
            rememberFace(title(raw))
            return
        }
        val t = raw.lowercase(Locale.ROOT).trim()
        val remember = Regex("(?:remember|save|register|add)\\b.*?\\b(?:as|named|name is|is)\\s+(.+)").find(t)
        val forget = Regex("(?:forget|delete|remove)\\s+(.+)").find(t)
        when {
            remember != null -> if (!faceModelMissing()) rememberFace(title(remember.groupValues[1]))
            forget != null -> forgetFace(forget.groupValues[1])
            "remember" in t && "face" in t -> startRemember()
            "read" in t || "text" in t -> readText()
            "describe" in t || "around" in t || "what do you see" in t || "surround" in t -> describe()
            "who" in t || "recogni" in t -> identifyFaces()
            "pause" in t || "mute" in t || "quiet" in t || ("stop" in t && "alert" in t) -> setAlerts(false)
            "resume" in t || "unmute" in t || "start" in t -> setAlerts(true)
            "help" in t -> say(HELP)
            else -> say("Sorry, I did not understand. Say help to hear the commands.")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {   // hands-free trigger for voice commands
            if (event?.repeatCount == 0) listen()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
        if (::bgExecutor.isInitialized) bgExecutor.shutdown()
        detector?.close()
        if (::voice.isInitialized) voice.destroy()
        if (::speaker.isInitialized) speaker.shutdown()
    }

    companion object {
        const val HELP = "Press the big button or hold volume down, then say a command. " +
            "Say describe, read text, who is this, remember face, remember this face as and a name, " +
            "forget and a name, pause alerts, or resume alerts."
    }
}
