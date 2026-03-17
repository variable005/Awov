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
    TOUCHING_FINGERS("Everything is connected.", "Index and middle tips touching"),
    WAVE("Hi!", "Moving hand side to side"),
    CIRCLE("Okay.", "Moving hand in a circle"),
    CLAP("Attention!", "Two hands moving together"),
    X_SIGN("Stop.", "Index fingers crossing")
}

class GestureAnalyzer(
    private val context: Context,
    private val listener: GestureListener
) {
    var onlyCustomGestures: Boolean = false
    interface GestureListener {
        fun onTrackingUpdate(gesture: Gesture, landmarks: List<List<NormalizedLandmark>>, customName: String? = null)
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
    private val customNameBuffer = mutableListOf<String?>()
    private var handLossCount = 0
    private val HAND_LOSS_GRACE_PERIOD = 10 // frames
    
    // Motion Tracking
    private val motionBuffer = mutableListOf<List<android.graphics.PointF>>() // List of indices 0-1 centers
    private val MOTION_BUFFER_SIZE = 15

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
                .setNumHands(2)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    if (result.landmarks().isNotEmpty()) {
                        handLossCount = 0
                        processMultiHandLandmarks(result)
                    } else {
                        handLossCount++
                        listener.onHandLost()
                        if (handLossCount > HAND_LOSS_GRACE_PERIOD) {
                            resetDetection()
                        }
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

    private fun processMultiHandLandmarks(result: HandLandmarkerResult) {
        val allLandmarks = result.landmarks()
        
        // Update motion buffer with weighted center of all detected hands for stability
        if (allLandmarks.isNotEmpty()) {
            var sumX = 0f
            var sumY = 0f
            var count = 0
            allLandmarks.forEach { hand ->
                sumX += hand[0].x() + hand[9].x()
                sumY += hand[0].y() + hand[9].y()
                count += 2
            }
            motionBuffer.add(listOf(android.graphics.PointF(sumX / count, sumY / count)))
        } else {
            motionBuffer.add(emptyList())
        }
        if (motionBuffer.size > MOTION_BUFFER_SIZE) motionBuffer.removeAt(0)

        // Single hand heuristics (run for the first hand for now, or combine)
        val primaryLandmarks = allLandmarks[0]
        val detected = analyzeSingleHand(primaryLandmarks)
        var finalDetected = detected
        
        // --- DYNAMIC GESTURES ---
        // Priority: Two-hand gestures > Dynamic gestures > Single-hand static gestures
        if (allLandmarks.size == 2) {
            val h1 = allLandmarks[0]
            val h2 = allLandmarks[1]
            
            // X-Sign (Stop)
            val distIndexTips = Math.hypot((h1[8].x() - h2[8].x()).toDouble(), (h1[8].y() - h2[8].y()).toDouble()).toFloat()
            if (distIndexTips < 0.06f) finalDetected = Gesture.X_SIGN
            
            // Clap (Attention)
            if (finalDetected == Gesture.NONE && detectClap()) finalDetected = Gesture.CLAP
        }

        if (finalDetected == Gesture.NONE && motionBuffer.size >= 8) {
            if (detectWave()) finalDetected = Gesture.WAVE
            else if (detectCircle()) finalDetected = Gesture.CIRCLE
        }

        // --- Temporal Smoothing ---
        gestureBuffer.add(finalDetected)
        if (gestureBuffer.size > 2) gestureBuffer.removeAt(0)
        val currentGesture = if (gestureBuffer.distinct().size == 1) finalDetected else Gesture.NONE

        // --- Custom Gesture Check ---
        var finalGestureName = currentGesture.name
        var customMatch: String? = null
        var isCustom = false
        
        if (currentGesture == Gesture.NONE) {
            customMatch = findMatchingCustomGesture(primaryLandmarks, customTemplates, calculatePalmSize(primaryLandmarks))
        }

        // Custom Smoothing
        customNameBuffer.add(customMatch)
        if (customNameBuffer.size > 3) customNameBuffer.removeAt(0)
        val smoothedCustomName = if (customNameBuffer.distinct().size == 1) customMatch else null

        if (smoothedCustomName != null) {
            finalGestureName = smoothedCustomName
            isCustom = true
        }

        handleGestureState(currentGesture, finalGestureName, isCustom, allLandmarks, smoothedCustomName)
    }

    private fun detectWave(): Boolean {
        val points = motionBuffer.filter { it.isNotEmpty() }.map { it[0] }
        if (points.size < 8) return false
        
        var directionalChanges = 0
        var lastDeltaX = 0f
        var totalHorizontalTraveled = 0f
        
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i-1].x
            totalHorizontalTraveled += Math.abs(dx)
            
            if (Math.abs(dx) > 0.01f) {
                if (lastDeltaX != 0f && (dx > 0) != (lastDeltaX > 0)) {
                    directionalChanges++
                }
                lastDeltaX = dx
            }
        }
        
        // A wave needs at least 2 turns and a minimum amount of horizontal movement
        return directionalChanges >= 2 && totalHorizontalTraveled > 0.15f
    }

    private fun detectClap(): Boolean {
        // Look at the last 5 frames of motion
        if (motionBuffer.size < 5) return false
        val points = motionBuffer.takeLast(5).filter { it.isNotEmpty() }
        if (points.size < 3) return false
        
        // For clap, we usually detect the "impact" or rapid convergence
        // But since motionBuffer stores the center of ALL hands, 
        // a better way for dynamic clap is checking if two hands suddenly become one center 
        // or the distance between them is rapidly decreasing.
        // For now, let's keep it simple: if two-hand centers were far and now they are one.
        // Actually, let's use the X_SIGN as the primary 2-hand trigger for now as it's more stable.
        return false 
    }

    private fun detectCircle(): Boolean {
        val points = motionBuffer.filter { it.isNotEmpty() }.map { it[0] }
        if (points.size < 12) return false
        
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        
        val width = maxX - minX
        val height = maxY - minY
        
        if (width < 0.15f || height < 0.15f) return false
        val aspectRatio = width / height
        if (aspectRatio !in 0.7f..1.4f) return false
        
        // Rough distance sum check to see if it's a path, not just a jump
        var pathLength = 0f
        for (i in 1 until points.size) {
            pathLength += Math.hypot((points[i].x - points[i-1].x).toDouble(), (points[i].y - points[i-1].y).toDouble()).toFloat()
        }
        
        return pathLength > 0.4f
    }

    private fun calculatePalmSize(landmarks: List<NormalizedLandmark>): Float {
        val wrist = landmarks[0]
        val middleMCP = landmarks[9]
        return Math.hypot((middleMCP.x() - wrist.x()).toDouble(), (middleMCP.y() - wrist.y()).toDouble()).toFloat()
    }

    private fun analyzeSingleHand(landmarks: List<NormalizedLandmark>): Gesture {
        val wrist = landmarks[0]
        val palmSize = calculatePalmSize(landmarks)
        if (palmSize < 0.01f) return Gesture.NONE

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
        val isIndexExtended = getAngle(landmarks[5], landmarks[6], landmarks[8]) > 145.0
        val isMiddleExtended = getAngle(landmarks[9], landmarks[10], landmarks[12]) > 145.0
        val isRingExtended = getAngle(landmarks[13], landmarks[14], landmarks[16]) > 145.0
        val isPinkyExtended = getAngle(landmarks[17], landmarks[18], landmarks[20]) > 145.0
        
        // Thumb logic: Combination of distance and angle from index
        val thumbTip = landmarks[4]
        val isThumbExtended = getAngle(landmarks[2], landmarks[3], landmarks[4]) > 140.0 || 
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
        val detected = if (onlyCustomGestures) {
            Gesture.NONE
        } else {
            when {
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
        }

        // --- Temporal Smoothing (2-frame window for ultra-fast response) ---
        gestureBuffer.add(detected)
        if (gestureBuffer.size > 2) gestureBuffer.removeAt(0)
        
        val currentGesture = if (gestureBuffer.distinct().size == 1) detected else Gesture.NONE

        // --- Custom Gesture Check ---
        var finalGestureName = currentGesture.name
        var customMatch: String? = null
        var isCustom = false
        
        if (currentGesture == Gesture.NONE) {
            customMatch = findMatchingCustomGesture(landmarks, customTemplates, palmSize)
        }

        // Custom Smoothing
        customNameBuffer.add(customMatch)
        if (customNameBuffer.size > 3) customNameBuffer.removeAt(0)
        val smoothedCustomName = if (customNameBuffer.distinct().size == 1) customMatch else null

        if (smoothedCustomName != null) {
            finalGestureName = smoothedCustomName
            isCustom = true
        }

        handleGestureState(currentGesture, finalGestureName, isCustom, listOf(landmarks), smoothedCustomName)
        
        return currentGesture
    }

    private var customTemplates: Map<String, List<NormalizedLandmark>> = emptyMap()
    fun updateCustomTemplates(templates: Map<String, List<NormalizedLandmark>>) {
        customTemplates = templates
    }

    private fun handleGestureState(
        gesture: Gesture, 
        name: String, 
        isCustom: Boolean, 
        landmarks: List<List<NormalizedLandmark>>,
        customName: String?
    ) {
        val currentTime = SystemClock.uptimeMillis()
        
        // Respect suppression flag
        val isSelectionGesture = gesture == Gesture.INDEX_FINGER_UP || 
                                gesture == Gesture.PEACE_SIGN || 
                                gesture == Gesture.THREE_FINGERS
        
        val effectiveGesture = if (onlyCustomGestures && !isCustom && !isSelectionGesture) Gesture.NONE else gesture
        val effectiveName = if (onlyCustomGestures && !isCustom && !isSelectionGesture) "NONE" else name
        val effectiveCustomName = if (onlyCustomGestures && !isCustom && !isSelectionGesture) null else customName

        // Always update tracking
        listener.onTrackingUpdate(effectiveGesture, landmarks, effectiveCustomName)

        if (effectiveName == "NONE" || effectiveName == "") {
            resetDetection()
            return
        }

        if (effectiveName != lastGestureName) {
            lastGestureName = effectiveName
            gestureStartTime = currentTime
            
            // IMMEDIATE TRIGGER for dynamic gestures (WAVE, CIRCLE, CLAP)
            if (effectiveGesture == Gesture.WAVE || effectiveGesture == Gesture.CIRCLE || effectiveGesture == Gesture.CLAP) {
                if (currentTime - lastSpokenTime >= COOLDOWN_PERIOD) {
                    lastSpokenTime = currentTime
                    listener.onGestureFinalized(effectiveGesture)
                    // Reset to avoid double triggers from the same motion window
                    motionBuffer.clear() 
                    gestureBuffer.clear()
                }
            }
        } else {
            val duration = currentTime - gestureStartTime
            val progress = (duration.toFloat() / DEBOUNCE_THRESHOLD).coerceIn(0f, 1f)
            listener.onGestureProgress(progress)

            if (duration >= DEBOUNCE_THRESHOLD) {
                if (currentTime - lastSpokenTime >= COOLDOWN_PERIOD) {
                    lastSpokenTime = currentTime
                    if (isCustom) {
                        listener.onCustomGestureFinalized(effectiveName)
                    } else {
                        listener.onGestureFinalized(effectiveGesture)
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
        var minDistance = 0.20f // Increased threshold from 0.15f for better tolerance

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
        gestureBuffer.clear()
        customNameBuffer.clear()
        listener.onGestureProgress(0f)
    }

    fun close() {
        handLandmarker?.close()
        backgroundExecutor.shutdown()
    }

    companion object {
        val HAND_CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, // Thumb
            0 to 5, 5 to 6, 6 to 7, 7 to 8, // Index
            5 to 9, 9 to 10, 10 to 11, 11 to 12, // Middle
            9 to 13, 13 to 14, 14 to 15, 15 to 16, // Ring
            13 to 17, 17 to 18, 18 to 19, 19 to 20, // Pinky
            0 to 17 // Palm base
        )
    }
}
