package com.visionassistant.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device OCR (ML Kit, Latin script). The model is bundled in the app, so it works offline. */
class TextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(frame: Bitmap, onResult: (String) -> Unit) {
        recognizer.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { onResult(it.text.replace("\n", ". ").trim()) }
            .addOnFailureListener { onResult("") }
    }
}
