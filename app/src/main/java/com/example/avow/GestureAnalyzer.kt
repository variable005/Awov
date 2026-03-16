package com.example.avow

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

enum class Gesture(val sentence: String, val hint: String) {
    NONE("", ""),
    OPEN_PALM("Hello", "All fingers extended, palm facing out"),
    FIST("I need help", "All fingers tucked, thumb over fingers"),
    TWO_FINGERS("Thank you", "Index and middle fingers extended"),
    THUMBS_UP("Yes", "Thumb up, other fingers tucked"),
    THUMBS_DOWN("No", "Thumb down, other fingers tucked"),
    INDEX_FINGER_UP("I have a question.", "Only index finger pointing up"),
    THREE_FINGERS("Wait a moment.", "Thumb, index, and middle extended"),
    PINKY_UP("I need water.", "Only pinky finger pointing up"),
    SHAKA("Awesome!", "Thumb and pinky extended, others tucked"),
    PALM_DOWN("Calm down.", "All fingers extended, palm facing ground"),
    LOVE_YOU("I love you!", "Thumb, index, and pinky extended"),
    ROCK_ON("Rock on!", "Index and pinky extended"),
    PEACE_SIGN("Peace be with you.", "Index and middle spread apart"),
    FOUR_FINGERS("Give me four minutes.", "All fingers except thumb extended"),
    INDEX_PINCH("Got it.", "Thumb and index tips touching"),
    TOUCHING_FINGERS("Everything is connected.", "Index and middle tips touching")
}

class GestureAnalyzer(
    private val context: Context,
    private val listener: GestureListener
) {
    interface GestureListener {
        fun onTrackingUpdate(gesture: Gesture, landmarks: List<NormalizedLandmark>)
        fun onGestureFinalized(gesture: Gesture)
        fun onGestureProgress(progress: Float) // New: 0.0 to 1.0
        fun onCustomGestureFinalized(name: String)
        fun onHandLost()
        fun onError(error: String)
    }

    private var handLandmarker: HandLandmarker? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // State management for Debouncing and Cooldown
    private var lastGesture = Gesture.NONE
    private var gestureStartTime = 0L
    private var lastSpokenTime = 0L
    private val DEBOUNCE_THRESHOLD = 300L // Reduced from 500ms for faster response
    private val COOLDOWN_PERIOD = 1200L // Reduced from 2000ms for faster sentence building
    private val gestureBuffer = mutableListOf<Gesture>()

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
        
        try {
            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinHandDetectionConfidence(0.8f)
                .setMinTrackingConfidence(0.8f)
                .setMinHandPresenceConfidence(0.8f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    if (result.landmarks().isNotEmpty()) {
                        processLandmarks(result)
                    } else {
                        listener.onHandLost()
                        resetDetection()
                    }
                }
                .setErrorListener { error ->
                    listener.onError("MediaPipe Error: ${error.message}")
                }

            handLandmarker = HandLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            listener.onError("Hand Landmarker failed to initialize: ${e.message}")
        }
    }

    fun analyze(bitmap: Bitmap, timestampMS: Long) {
        backgroundExecutor.execute {
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, timestampMS)
        }
    }

    private fun processLandmarks(result: HandLandmarkerResult) {
        val landmarks = result.landmarks()[0]
        val wrist = landmarks[0]
        
        // --- Normalized Heuristics ---
        // Calculate palm size as a reference unit (Distance: Wrist[0] to Middle MCP[9])
        val middleMCP = landmarks[9]
        val palmSize = Math.hypot(
            (middleMCP.x() - wrist.x()).toDouble(),
            (middleMCP.y() - wrist.y()).toDouble()
        ).toFloat()

        if (palmSize < 0.01f) return

        // --- Helper: Angle-based Heuristics ---
        fun getAngle(p1: NormalizedLandmark, p2: NormalizedLandmark, p3: NormalizedLandmark): Double {
            val v1x = p1.x() - p2.x()
            val v1y = p1.y() - p2.y()
            val v2x = p3.x() - p2.x()
            val v2y = p3.y() - p2.y()
            val dot = v1x * v2x + v1y * v2y
            val mag1 = Math.sqrt((v1x * v1x + v1y * v1y).toDouble())
            val mag2 = Math.sqrt((v2x * v2x + v2y * v2y).toDouble())
            return Math.acos(dot / (mag1 * mag2)) * (180.0 / Math.PI)
        }
        
        // --- Helper: Cross Product for Palm Orientation ---
        fun isPalmFacingCamera(): Boolean {
            val v1x = landmarks[5].x() - landmarks[0].x()
            val v1y = landmarks[5].y() - landmarks[0].y()
            val v2x = landmarks[17].x() - landmarks[0].x()
            val v2y = landmarks[17].y() - landmarks[0].y()
            val cp = v1x * v2y - v1y * v2x
            return cp > 0 // Handedness dependent, but positive CP often means palm out
        }


        // Fingers are extended if their ratio relative to palm is high
        // Angle-based extension checks are more robust than simple ratios
        val isIndexExtended = getAngle(landmarks[5], landmarks[6], landmarks[8]) > 160.0
        val isMiddleExtended = getAngle(landmarks[9], landmarks[10], landmarks[12]) > 160.0
        val isRingExtended = getAngle(landmarks[13], landmarks[14], landmarks[16]) > 160.0
        val isPinkyExtended = getAngle(landmarks[17], landmarks[18], landmarks[20]) > 160.0
        
        // Thumb logic: Combination of distance and angle from index
        val thumbTip = landmarks[4]
        val isThumbExtended = getAngle(landmarks[2], landmarks[3], landmarks[4]) > 150.0 || 
                             (Math.hypot((thumbTip.x() - landmarks[5].x()).toDouble(), (thumbTip.y() - landmarks[5].y()).toDouble()) > palmSize * 0.7f)

        // Orientation and spread heuristics
        val isThumbUp = landmarks[4].y() < landmarks[2].y() && landmarks[4].y() < landmarks[8].y()
        val isThumbDown = landmarks[4].y() > landmarks[2].y() && landmarks[4].y() > landmarks[20].y()
        
        val avgFingerTipY = (landmarks[8].y() + landmarks[12].y() + landmarks[16].y() + landmarks[20].y()) / 4f
        val isHandHorizontal = Math.abs(wrist.y() - avgFingerTipY) < (palmSize * 0.5f)

        val indexMiddleSpread = Math.hypot(
            (landmarks[8].x() - landmarks[12].x()).toDouble(),
            (landmarks[8].y() - landmarks[12].y()).toDouble()
        ).toFloat()
        val isPeaceSpread = indexMiddleSpread > palmSize * 0.6f
        val isIndexMiddleTouching = indexMiddleSpread < palmSize * 0.15f

        // Pinch logic: Index tip (8) and Thumb tip (4)
        val indexThumbDist = Math.hypot(
            (landmarks[8].x() - landmarks[4].x()).toDouble(),
            (landmarks[8].y() - landmarks[4].y()).toDouble()
        ).toFloat()
        val isIndexPinched = indexThumbDist < palmSize * 0.15f
        val detected = when {
            isPalmFacingCamera() && isIndexPinched && !isMiddleExtended && !isRingExtended && !isPinkyExtended -> Gesture.INDEX_PINCH
            isPalmFacingCamera() && isIndexMiddleTouching && !isRingExtended && !isPinkyExtended && !isThumbExtended -> Gesture.TOUCHING_FINGERS
            isPalmFacingCamera() && isIndexExtended && isMiddleExtended && isPinkyExtended && isThumbExtended && !isRingExtended -> Gesture.LOVE_YOU
            isIndexExtended && isPinkyExtended && !isMiddleExtended && !isRingExtended && !isThumbExtended -> Gesture.ROCK_ON
            isIndexExtended && isMiddleExtended && !isRingExtended && !isPinkyExtended && !isThumbExtended && isPeaceSpread -> Gesture.PEACE_SIGN
            isIndexExtended && isMiddleExtended && isRingExtended && isPinkyExtended && !isThumbExtended -> Gesture.FOUR_FINGERS
            
            // Existing gestures
            isHandHorizontal && isIndexExtended && isMiddleExtended && isRingExtended && isPinkyExtended -> Gesture.PALM_DOWN
            !isHandHorizontal && isIndexExtended && isMiddleExtended && isRingExtended && isPinkyExtended -> Gesture.OPEN_PALM
            isThumbExtended && isPinkyExtended && !isIndexExtended && !isMiddleExtended && !isRingExtended -> Gesture.SHAKA
            isIndexExtended && isMiddleExtended && isRingExtended && !isPinkyExtended -> Gesture.THREE_FINGERS
            isIndexExtended && isMiddleExtended && !isRingExtended && !isPinkyExtended -> Gesture.TWO_FINGERS
            isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended -> Gesture.INDEX_FINGER_UP
            isPinkyExtended && !isIndexExtended && !isMiddleExtended && !isRingExtended -> Gesture.PINKY_UP
            isThumbUp && !isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended -> Gesture.THUMBS_UP
            isThumbDown && !isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended -> Gesture.THUMBS_DOWN
            !isIndexExtended && !isMiddleExtended && !isRingExtended && !isPinkyExtended && !isThumbExtended -> Gesture.FIST
            else -> Gesture.NONE
        }

        // --- Temporal Smoothing (2-frame window for ultra-fast response) ---
        gestureBuffer.add(detected)
        if (gestureBuffer.size > 2) gestureBuffer.removeAt(0)
        
        val currentGesture = if (gestureBuffer.distinct().size == 1) detected else Gesture.NONE

        // --- Custom Gesture Check ---
        var finalGestureName = currentGesture.name
        var isCustom = false
        
        if (currentGesture == Gesture.NONE) {
            val customMatch = findMatchingCustomGesture(landmarks, customTemplates, palmSize)
            if (customMatch != null) {
                finalGestureName = customMatch
                isCustom = true
            }
        }

        handleGestureState(currentGesture, finalGestureName, isCustom, landmarks)
    }

    private var customTemplates: Map<String, List<NormalizedLandmark>> = emptyMap()
    fun updateCustomTemplates(templates: Map<String, List<NormalizedLandmark>>) {
        customTemplates = templates
    }

    private fun handleGestureState(
        gesture: Gesture, 
        name: String, 
        isCustom: Boolean, 
        landmarks: List<NormalizedLandmark>
    ) {
        val currentTime = SystemClock.uptimeMillis()
        
        // Always update tracking
        listener.onTrackingUpdate(gesture, landmarks)
        if (isCustom) {
             // Optional: update tracking label with custom name
        }

        if (name == "NONE" || name == "") {
            resetDetection()
            return
        }

        if (name != lastGestureName) {
            lastGestureName = name
            gestureStartTime = currentTime
        } else {
            val duration = currentTime - gestureStartTime
            val progress = (duration.toFloat() / DEBOUNCE_THRESHOLD).coerceIn(0f, 1f)
            listener.onGestureProgress(progress)

            if (duration >= DEBOUNCE_THRESHOLD) {
                if (currentTime - lastSpokenTime >= COOLDOWN_PERIOD) {
                    lastSpokenTime = currentTime
                    if (isCustom) {
                        listener.onCustomGestureFinalized(name)
                    } else {
                        listener.onGestureFinalized(gesture)
                    }
                }
            }
        }
    }

    private var lastGestureName = ""

    fun findMatchingCustomGesture(
        current: List<NormalizedLandmark>,
        templates: Map<String, List<NormalizedLandmark>>,
        palmSize: Float
    ): String? {
        if (templates.isEmpty() || current.isEmpty()) return null
        
        // Normalize current landmarks relative to wrist
        val wrist = current[0]
        val normalizedCurrent = current.map { 
            floatArrayOf(it.x() - wrist.x(), it.y() - wrist.y(), it.z() - wrist.z())
        }

        var bestMatch: String? = null
        var minDistance = 0.15f // Distance threshold for "match"

        templates.forEach { (name, template) ->
            val templateWrist = template[0]
            val normalizedTemplate = template.map {
                floatArrayOf(it.x() - templateWrist.x(), it.y() - templateWrist.y(), it.z() - templateWrist.z())
            }

            var totalDist = 0f
            for (i in normalizedCurrent.indices) {
                // Normalize by palm size to handle varying distances
                val dx = (normalizedCurrent[i][0] - normalizedTemplate[i][0]) / palmSize
                val dy = (normalizedCurrent[i][1] - normalizedTemplate[i][1]) / palmSize
                val dz = (normalizedCurrent[i][2] - normalizedTemplate[i][2]) / palmSize
                totalDist += Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            }
            
            val avgDist = totalDist / normalizedCurrent.size
            if (avgDist < minDistance) {
                minDistance = avgDist
                bestMatch = name
            }
        }
        
        return bestMatch
    }

    private fun resetDetection() {
        lastGesture = Gesture.NONE
        lastGestureName = ""
        gestureStartTime = 0L
        listener.onGestureProgress(0f)
    }

    fun close() {
        handLandmarker?.close()
        backgroundExecutor.shutdown()
    }
}
