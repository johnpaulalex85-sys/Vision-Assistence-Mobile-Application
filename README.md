# Vision Assistant - standalone Android app

Everything runs on the phone. No server, no internet needed.

| Feature | How |
|---|---|
| Obstacle detection + spoken/vibration alerts | YOLOv8n (TFLite) + corridor/cooldown logic ported from the backend |
| Wall detection | Smooth-surface detector ported from `wall_service.py` (plain Kotlin) |
| Read text | ML Kit text recognition (bundled, offline) |
| Describe surroundings | Template sentence built from detections + wall |
| Face recognition | ML Kit face detection + MobileFaceNet TFLite embeddings (optional model file) |
| Voice commands | Android SpeechRecognizer (offline preferred). Button or hold **volume down** |
| Accessibility | Large buttons, TalkBack labels, haptic buzz on urgent hazards, screen stays on |

## 1. Model files (put in app/src/main/assets/)
1. **yolov8n.tflite** (required):
       pip install ultralytics
       yolo export model=yolov8n.pt format=tflite imgsz=320
   then copy `yolov8n_saved_model/yolov8n_float32.tflite` and rename it `yolov8n.tflite`.
2. **mobilefacenet.tflite** (optional - only for face recognition): a MobileFaceNet model with
   112x112x3 float input and one embedding vector as output (e.g. 128 or 192 values). The code reads
   the sizes from the model. Without the file everything else still works, and face commands say the model is missing.

## 2. Build and run
Open this folder in Android Studio (Koala or newer), sync Gradle, run on a real phone (USB debugging).
Allow Camera and Microphone when asked. For offline voice commands install the phone's offline speech
language pack (Google app / Settings > Languages > Offline speech recognition).

## Voice commands
describe | read text | who is this | remember face (then say the name after the beep) |
remember this face as <name> | forget <name> | pause alerts | resume alerts | help

## Tuning
- HazardEngine.kt - corridor (0.32 / 0.68), "close" thresholds, cooldowns
- WallDetector.kt - minCoverage, textureThreshold (same meaning as WALL_* in config.py)
- FaceRecognizer.kt - `threshold` (cosine similarity, default 0.60)
- Detector.kt - confThreshold (0.40)
- Slow phone? re-export YOLO with imgsz=256.

## Not ported from the backend
Qwen paragraph descriptions, depth model, patterned (brick/tile) wall detection, SegFormer wall segmentation.
