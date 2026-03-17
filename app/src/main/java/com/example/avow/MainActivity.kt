package com.example.avow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.avow.ui.theme.AvowTheme
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.common.InputImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.avow.ui.theme.*
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.clickable

class MainActivity : ComponentActivity(), GestureAnalyzer.GestureListener {

    private lateinit var cameraExecutor: ExecutorService
    private var gestureAnalyzer: GestureAnalyzer? = null
    private var tts: TextToSpeech? = null

    enum class AppMode { TRANSLATE, LEARN, TEACH }

    // UI State
    private var detectedGestureState = mutableStateOf("No Hand")
    private var spokenSentenceState = mutableStateOf("")
    private var sentenceBufferState = mutableStateListOf<String>() // For UI Display
    private var englishSentenceBufferState = mutableStateListOf<String>() // For AI Engine
    private var isHandInFrameState = mutableStateOf(false)
    private var isSpeakingState = mutableStateOf(false)
    private var landmarksState = mutableStateOf<List<List<NormalizedLandmark>>>(emptyList())
    
    // Learn Mode State
    // Learn/Teach Mode State
    private var appModeState = mutableStateOf(AppMode.TRANSLATE)
    private var targetGestureState = mutableStateOf(Gesture.OPEN_PALM)
    private var learnScoreState = mutableIntStateOf(0)
    private var isMatchState = mutableStateOf(false)
    
    // Custom Gesture State
    private var customGesturesState = mutableStateMapOf<String, List<NormalizedLandmark>>()
    private var isRecordingState = mutableStateOf(false)
    private var recordingProgressState = mutableFloatStateOf(0f)
    private var gestureHoldProgressState = mutableFloatStateOf(0f) // New tracking
    private var learnStreakState = mutableIntStateOf(0) // New streak tracker
    private var onlyCustomGesturesState = mutableStateOf(false)
    private val suggestionsState = mutableStateListOf<String>()
    private var cameraLensState = mutableStateOf(CameraSelector.LENS_FACING_FRONT)
    private var isSmilingState = mutableFloatStateOf(0f)
    private var isSurprisedState = mutableStateOf(false)
    private var faceDetectionFrameCounter = 0
    private var smilingBuffer = mutableListOf<Float>()
    private lateinit var smartReplyManager: SmartReplyManager
    private var faceDetector: FaceDetector? = null
    private val sentenceSuggester = SentenceSuggester()
    private var speechManager: SpeechManager? = null
    private var isContextModeState = mutableStateOf(false)
    private var selectedSuggestionIndexState = mutableIntStateOf(-1) 
    private var isHindiModeState = mutableStateOf(false)
    private val translationManager = TranslationManager()
    private var isTranslationLoadingState = mutableStateOf(false)
    private var liveTranscriptionState = mutableStateOf("")
    private var micVolumeState = mutableFloatStateOf(0f)

    private val PREFS_NAME = "avow_gestures"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCustomGestures() // NEW: Load on startup

        cameraExecutor = Executors.newSingleThreadExecutor()
        gestureAnalyzer = GestureAnalyzer(this, this).apply {
            onlyCustomGestures = onlyCustomGesturesState.value
        }
        
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // Added for eyebrows
            .build()
        faceDetector = FaceDetection.getClient(faceOptions)
        
        smartReplyManager = SmartReplyManager { smartSuggestions ->
            updateMergedSuggestions(smartSuggestions)
        }

        speechManager = SpeechManager(
            this,
            onResult = { spokenText ->
                if (isContextModeState.value) {
                    // Persist final result for visibility
                    liveTranscriptionState.value = spokenText
                    // Clear after 4 seconds
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(4000)
                        if (liveTranscriptionState.value == spokenText) {
                            liveTranscriptionState.value = ""
                        }
                    }

                    if (isHindiModeState.value) {
                        lifecycleScope.launch {
                            val englishText = translationManager.translateHiToEn(spokenText)
                            smartReplyManager.addMessage(englishText, false)
                        }
                    } else {
                        smartReplyManager.addMessage(spokenText, false)
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isContextModeState.value) {
                    liveTranscriptionState.value = partialText
                }
            },
            onRmsChanged = { volume ->
                if (isContextModeState.value) {
                    micVolumeState.floatValue = volume
                }
            }
        )

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { isSpeakingState.value = true }
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { isSpeakingState.value = false }
                    }
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { isSpeakingState.value = false }
                    }
                })
            }
        }

        setContent {
            AvowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GestureTranslatorApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureTranslatorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "AVOW",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 4.sp
                            ),
                            color = CyanPrimary
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            if (!hasAudioPermission) {
                                audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                isContextModeState.value = !isContextModeState.value
                                if (isContextModeState.value) {
                                    speechManager?.startListening()
                                } else {
                                    speechManager?.stopListening()
                                    liveTranscriptionState.value = ""
                                }
                            }
                        }) {
                            Icon(
                                imageVector = if (isContextModeState.value) Icons.Default.Mic else Icons.Default.MicNone,
                                contentDescription = "Listen Mode",
                                tint = if (isContextModeState.value) NeonRed else CyanPrimary
                            )
                        }
                        // Language Toggle
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isTranslationLoadingState.value = true
                                    if (translationManager.downloadModelsIfNeeded()) {
                                        isHindiModeState.value = !isHindiModeState.value
                                        speechManager?.setLanguage(isHindiModeState.value)
                                        checkTtsLanguage()
                                    }
                                    isTranslationLoadingState.value = false
                                }
                            }
                        ) {
                            Text(
                                if (isHindiModeState.value) "हिं" else "EN",
                                color = CyanPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { showSheet = true }) {
                            Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
                
                // Mode Tabs (Restored)
                TabRow(
                    selectedTabIndex = when(appModeState.value) {
                        AppMode.TRANSLATE -> 0
                        AppMode.LEARN -> 1
                        AppMode.TEACH -> 2
                    },
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[when(appModeState.value) {
                                AppMode.TRANSLATE -> 0
                                AppMode.LEARN -> 1
                                AppMode.TEACH -> 2
                            }]),
                            color = CyanPrimary
                        )
                    },
                    divider = {}
                ) {
                    Tab(
                        selected = appModeState.value == AppMode.TRANSLATE,
                        onClick = { appModeState.value = AppMode.TRANSLATE },
                        text = { Text("LIVE", style = MaterialTheme.typography.labelSmall) }
                    )
                    Tab(
                        selected = appModeState.value == AppMode.LEARN,
                        onClick = { appModeState.value = AppMode.LEARN },
                        text = { Text("LEARN", style = MaterialTheme.typography.labelSmall) }
                    )
                    Tab(
                        selected = appModeState.value == AppMode.TEACH,
                        onClick = { appModeState.value = AppMode.TEACH },
                        text = { Text("TEACH", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding()) // Only bottom padding if needed
        ) {
            // 1. Full-Screen Camera Background Layer
            if (hasCameraPermission) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview()
                    LandmarkOverlay(landmarksState.value)
                    ViewfinderOverlay()
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Camera Access Required", color = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                // Top Spacer for Status Indicator and Tabs
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status Indicator (Floating over camera)
                Row(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .border(1.dp, CyanPrimary.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusIndicator(isDetected = isHandInFrameState.value)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Removed the fixed-height Surface and its contents
                // The camera components are now in the background layer.
                // The status indicator that was inside the Surface is also removed as it's redundant.

                // 3. Mode Specific Content (Suggestions / Cards)
                Box(modifier = Modifier.weight(1f).padding(top = 16.dp)) { // Added top padding
                    when (appModeState.value) {
                        AppMode.TRANSLATE -> HUDOverlay(
                            gesture = detectedGestureState.value,
                            sentence = sentenceBufferState.joinToString(" "),
                            isHandInFrame = isHandInFrameState.value,
                            holdProgress = gestureHoldProgressState.floatValue,
                            suggestions = suggestionsState.toList(),
                            onSuggestionClick = { suggestion ->
                                if (isHindiModeState.value) {
                                    sentenceBufferState.add(suggestion)
                                    scope.launch {
                                        val eng = translationManager.translateHiToEn(suggestion)
                                        englishSentenceBufferState.add(eng)
                                        smartReplyManager.addMessage(eng, true)
                                        updateMergedSuggestions()
                                    }
                                } else {
                                    sentenceBufferState.add(suggestion)
                                    englishSentenceBufferState.add(suggestion)
                                    smartReplyManager.addMessage(suggestion, true)
                                    updateMergedSuggestions()
                                }
                            }
                        )
                        AppMode.LEARN -> LearnModeUI()
                        AppMode.TEACH -> TeachModeUI()
                    }
                }
            }
            
        }
    }

    if (showSheet) {
        GestureDictionarySheet(
            sheetState = sheetState,
            onDismiss = { showSheet = false }
        )
    }
}



    @Composable
    fun LandmarkOverlay(allHandLandmarks: List<List<NormalizedLandmark>>) {
        if (allHandLandmarks.isEmpty()) return

        val coreColor = CyanPrimary
        val glowColor = CyanPrimary.copy(alpha = 0.3f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            allHandLandmarks.forEach { landmarks ->
                // Draw Connections with Glow
                GestureAnalyzer.HAND_CONNECTIONS.forEach { (start, end) ->
                    val p1 = landmarks[start]
                    val p2 = landmarks[end]
                    
                    drawLine(
                        color = glowColor,
                        start = androidx.compose.ui.geometry.Offset(p1.x() * size.width, p1.y() * size.height),
                        end = androidx.compose.ui.geometry.Offset(p2.x() * size.width, p2.y() * size.height),
                        strokeWidth = 12f,
                        cap = StrokeCap.Round
                    )
                    
                    drawLine(
                        color = coreColor,
                        start = androidx.compose.ui.geometry.Offset(p1.x() * size.width, p1.y() * size.height),
                        end = androidx.compose.ui.geometry.Offset(p2.x() * size.width, p2.y() * size.height),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }

                // Draw Landmark Points
                landmarks.forEach { landmark ->
                    drawCircle(
                        color = coreColor,
                        radius = 6f,
                        center = androidx.compose.ui.geometry.Offset(
                            landmark.x() * size.width,
                            landmark.y() * size.height
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun ViewfinderOverlay() {
        val infiniteTransition = rememberInfiniteTransition(label = "scanner")
        val scanY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scanLine"
        )

        val strokeColor = CyanPrimary.copy(alpha = 0.6f)
        
        Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
            val w = size.width
            val h = size.height
            val len = 60f
            val stroke = 4f

            // Scanning line
            drawLine(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.5f to CyanPrimary,
                    1f to Color.Transparent
                ),
                start = androidx.compose.ui.geometry.Offset(0f, scanY * h),
                end = androidx.compose.ui.geometry.Offset(w, scanY * h),
                strokeWidth = 4f
            )

            // Corners
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(len, 0f), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, len), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - len, 0f), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, len), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(len, h), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - len), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - len, h), stroke)
            drawLine(strokeColor, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - len), stroke)
            
            // Central tech marks
            drawRect(
                color = CyanPrimary.copy(alpha = 0.4f),
                topLeft = androidx.compose.ui.geometry.Offset(w/2 - 30f, h/2 - 1f),
                size = androidx.compose.ui.geometry.Size(60f, 2f)
            )
            drawRect(
                color = CyanPrimary.copy(alpha = 0.4f),
                topLeft = androidx.compose.ui.geometry.Offset(w/2 - 1f, h/2 - 30f),
                size = androidx.compose.ui.geometry.Size(2f, 60f)
            )
        }
    }

    @Composable
    fun CameraPreview() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }
        val lensFacing by cameraLensState

        LaunchedEffect(lensFacing) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val timestamp = SystemClock.uptimeMillis()
                    val bitmap = imageProxy.toBitmap()
                    
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val processedBitmap = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else bitmap

                    gestureAnalyzer?.analyze(processedBitmap, timestamp)

                    // Face Detection for Emotions (Throttled to 1/3 frames)
                    faceDetectionFrameCounter++
                    if (faceDetectionFrameCounter % 3 == 0) {
                        val inputImage = InputImage.fromBitmap(processedBitmap, rotation)
                        faceDetector?.process(inputImage)
                            ?.addOnSuccessListener { faces ->
                                if (faces.isNotEmpty()) {
                                    val face = faces[0]
                                    
                                    // Smoothing Smiling Probability
                                    val currentSmiling = face.smilingProbability ?: 0f
                                    smilingBuffer.add(currentSmiling)
                                    if (smilingBuffer.size > 5) smilingBuffer.removeAt(0)
                                    isSmilingState.floatValue = smilingBuffer.average().toFloat()
                                    
                                    // Improved Surprise Heuristic (Eyes + Eyebrows via Contours)
                                    val eyeOpenProb = ((face.leftEyeOpenProbability ?: 0f) + (face.rightEyeOpenProbability ?: 0f)) / 2f
                                    val isSmiling = isSmilingState.floatValue > 0.4f
                                    
                                    val leftEyebrow = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_TOP)
                                    val leftEye = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYE)
                                    
                                    var surpriseByEyebrows = false
                                    if (leftEyebrow != null && leftEye != null) {
                                        val eyebrowY = leftEyebrow.points.map { it.y }.average()
                                        val eyeY = leftEye.points.map { it.y }.average()
                                        val dist = Math.abs(eyeY - eyebrowY)
                                        // If distance is large, eyebrows are likely raised
                                        if (dist > face.boundingBox.height() * 0.15f) {
                                            surpriseByEyebrows = true
                                        }
                                    }

                                    isSurprisedState.value = (eyeOpenProb > 0.9f || surpriseByEyebrows) && !isSmiling
                                }
                            }
                    }

                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("Avow", "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GestureDictionarySheet(sheetState: SheetState, onDismiss: () -> Unit) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Headers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isHindiModeState.value) "संकेत पुस्तकालय" else "Sign Library",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    if (customGesturesState.isNotEmpty()) {
                        TextButton(
                            onClick = { 
                                customGesturesState.clear()
                                persistCustomGestures() 
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isHindiModeState.value) "सब साफ करें" else "Clear All")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Toggle for Predefined Gestures
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isHindiModeState.value) "सख्त मोड (केवल कस्टम)" else "Strict Mode (Custom Only)",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                if (isHindiModeState.value) "केवल आपके द्वारा प्रशिक्षित संकेतों को पहचानें" else "Only recognize signs you trained",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = onlyCustomGesturesState.value,
                            onCheckedChange = { 
                                onlyCustomGesturesState.value = it
                                gestureAnalyzer?.onlyCustomGestures = it
                                persistCustomGestures()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Content Tabs
                var selectedTabIndex by remember { mutableStateOf(0) }
                val tabs = if (isHindiModeState.value) 
                    listOf("बिल्ट-इन", "कस्टम (${customGesturesState.size})")
                    else listOf("Built-in", "Custom (${customGesturesState.size})")
                
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent,
                    divider = {},
                    indicator = { tabPositions ->
                        if (selectedTabIndex < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                color = Color.Black
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { 
                                Text(
                                    title, 
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                ) 
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.verticalScroll(scrollState)) {
                        if (selectedTabIndex == 0) {
                            // Built-in signs
                            if (onlyCustomGesturesState.value) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (isHindiModeState.value) "बिल्ट-इन संकेत वर्तमान में अक्षम हैं।" else "Built-in signs are currently disabled.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Gesture.values().filter { it != Gesture.NONE }.forEach { gesture ->
                                    val isActive = detectedGestureState.value == gesture.name
                                    SignRow(
                                        name = GestureLocalizer.getSentence(gesture, isHindiModeState.value),
                                        desc = GestureLocalizer.getHint(gesture, isHindiModeState.value),
                                        isActive = isActive
                                    )
                                }
                            }
                        } else {
                            // Custom signs
                            if (customGesturesState.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (isHindiModeState.value) "अभी तक कोई कस्टम संकेत नहीं मिला।" else "No custom signs found yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            } else {
                                customGesturesState.forEach { (name, _) ->
                                    val isActive = detectedGestureState.value == "CUSTOM: $name"
                                    SignRow(
                                        name = name,
                                        desc = "User Defined",
                                        isActive = isActive,
                                        onDelete = {
                                            customGesturesState.remove(name)
                                            persistCustomGestures()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SignRow(name: String, desc: String, isActive: Boolean, onDelete: (() -> Unit)? = null) {
        val backgroundColor = if (isActive) Color.Black else Color.Transparent
        val textColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
        val subTextColor = if (isActive) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(backgroundColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(6.dp),
                shape = CircleShape,
                color = if (isActive) Color.White else Color.LightGray
            ) {}
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name.uppercase(),
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = subTextColor
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = textColor)
                }
            }
        }
    }

    @Composable
    fun TeachModeUI() {
        var gestureName by remember { mutableStateOf("") }
        val isRecording by isRecordingState
        val progress by recordingProgressState

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .padding(top = 110.dp), // Increased for luxury clearance
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    border = BorderStroke(2.dp, CyanPrimary.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "TEACH NEW SIGN",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 2.sp),
                            color = CyanPrimary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        TextField(
                            value = gestureName,
                            onValueChange = { gestureName = it },
                            placeholder = { Text("Sign Name (e.g. 'Coffee')", color = Color.White.copy(alpha = 0.5f)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isRecording,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = CyanPrimary,
                                unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        if (isRecording) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(12.dp),
                                color = NeonGreen,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("HOLD STEADY...", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = NeonGreen))
                        } else {
                            Button(
                                onClick = { startRecording(gestureName) },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                enabled = gestureName.isNotBlank() && isHandInFrameState.value,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
                            ) {
                                Text("RECORD 2S SNAPSHOT", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold))
                            }
                        }
                    }
                }
                
                if (!isHandInFrameState.value) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "PLACE HAND IN FRAME TO BEGIN",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    private fun startRecording(name: String) {
        if (isRecordingState.value) return
        isRecordingState.value = true
        recordingProgressState.floatValue = 0f
        
        // Simple 2-second simulation/recording
        val duration = 2000L
        val steps = 20
        val stepTime = duration / steps
        
        android.os.Handler(android.os.Looper.getMainLooper()).post(object : Runnable {
            var currentStep = 0
            override fun run() {
                if (currentStep <= steps) {
                    recordingProgressState.floatValue = currentStep.toFloat() / steps
                    currentStep++
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, stepTime)
                } else {
                    saveCustomGesture(name)
                    isRecordingState.value = false
                }
            }
        })
    }

    private fun saveCustomGesture(name: String) {
        val currentLandmarks = landmarksState.value
        if (currentLandmarks.isNotEmpty()) {
            val snapshot = currentLandmarks[0] // Save the first hand detected as the template
            customGesturesState[name] = snapshot
            gestureAnalyzer?.updateCustomTemplates(customGesturesState.toMap())
            persistCustomGestures() // Save to disk
            speak("Learned new sign: $name")
            runOnUiThread {
                Toast.makeText(this, "Success: Learned '$name'", Toast.LENGTH_SHORT).show()
            }
        } else {
            runOnUiThread {
                Toast.makeText(this, "Error: No hand detected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistCustomGestures() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(customGesturesState.toMap())
        prefs.edit()
            .putString("templates", json)
            .putBoolean("onlyCustomGestures", onlyCustomGesturesState.value)
            .apply()
    }

    private fun loadCustomGestures() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load toggle setting
        val onlyCustom = prefs.getBoolean("onlyCustomGestures", false)
        onlyCustomGesturesState.value = onlyCustom
        gestureAnalyzer?.onlyCustomGestures = onlyCustom

        val json = prefs.getString("templates", null)
        if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, List<NormalizedLandmark>>>() {}.type
            try {
                val map: Map<String, List<NormalizedLandmark>> = com.google.gson.Gson().fromJson(json, type)
                customGesturesState.putAll(map)
                gestureAnalyzer?.updateCustomTemplates(map)
            } catch (e: Exception) {
                Log.e("Avow", "Failed to load custom gestures", e)
            }
        }
    }

    @Composable
    fun SuccessSplash() {
        val infiniteTransition = rememberInfiniteTransition(label = "success")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepSlate.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    modifier = Modifier.size(120.dp).scale(scale),
                    tint = NeonGreen
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "STREAK: ${learnStreakState.intValue}",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Color.White
                )
                Text(
                    "+10 POINTS",
                    style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp),
                    color = CyanPrimary.copy(alpha = 0.6f)
                )
            }
        }
        
        // Auto-dismiss and move to next gesture
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1200)
            isMatchState.value = false
            nextLearnGesture() // Changed from resetLearnSession() to nextLearnGesture()
        }
    }

    @Composable
    fun LearnModeUI() {
        val targetGesture by targetGestureState
        val score by learnScoreState
        val streak by learnStreakState
        val holdProgress by gestureHoldProgressState
        val isMatch by isMatchState
        val detectedGesture by detectedGestureState
        val isHandDetected by isHandInFrameState

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .padding(top = 120.dp), // Luxury clearance
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress Header
                ElevatedCard(
                    modifier = Modifier.padding(top = 16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                ) {
                    val progressText = if (isHindiModeState.value) 
                        "प्रगति: $score संकेत सीखे गए" else "PROGRESS: $score GESTURES MASTERED"
                    Text(
                        progressText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Target Gesture Card
                androidx.compose.animation.AnimatedContent(
                    targetState = targetGesture,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically()).togetherWith(
                            androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically()
                        )
                    },
                    label = "TargetChange"
                ) { target ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = BorderStroke(2.dp, CyanPrimary.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (isHindiModeState.value) "इस संकेत को दोहराएं" else "REPLICATE THIS SIGN",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Black),
                                color = CyanPrimary.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = holdProgress,
                                    modifier = Modifier.size(120.dp),
                                    color = NeonGreen,
                                    strokeWidth = 6.dp,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                    strokeCap = StrokeCap.Round
                                )
                                Text(
                                    GestureLocalizer.getSentence(target, isHindiModeState.value).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                GestureLocalizer.getHint(target, isHindiModeState.value),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
                
                // Streak Badge
                if (streak > 0) {
                    ElevatedCard(
                        modifier = Modifier.padding(top = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.Yellow.copy(alpha = 0.9f))
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, "Streak", tint = Color.Black, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            val streakText = if (isHindiModeState.value) "सिलसिला: $streak" else "STREAK: $streak"
                            Text(streakText, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = Color.Black)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Detection status
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = if (isHandDetected) "CURRENTLY MAKING: $detectedGesture" else "SHOW YOUR HAND...",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (isMatch) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    private fun resetLearnSession() {
        learnScoreState.intValue = 0
        learnStreakState.intValue = 0
        isMatchState.value = false
        targetGestureState.value = Gesture.values().filter { it != Gesture.NONE }.random()
    }

    private fun nextLearnGesture() {
        val next = Gesture.values().filter { 
            it != Gesture.NONE && it != targetGestureState.value 
        }.random()
        targetGestureState.value = next
        isMatchState.value = false
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HUDOverlay(
        gesture: String,
        sentence: String,
        isHandInFrame: Boolean,
        holdProgress: Float,
        suggestions: List<String>,
        onSuggestionClick: (String) -> Unit
    ) {
        val detectedGesture by detectedGestureState
        val spokenSentence by spokenSentenceState
        val isHandDetected by isHandInFrameState
        val isSpeaking by isSpeakingState

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .padding(top = 120.dp), // Luxury clearance
                verticalArrangement = Arrangement.Bottom
            ) {
                // Detected Gesture Label (Right Aligned Top)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "LANDMARK STATUS",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Mood Indicator
                                    val isSmiling by isSmilingState
                                    val isSurprised by isSurprisedState
                                    
                                    val moodIcon = when {
                                        isSurprised -> "🤔"
                                        isSmiling > 0.6f -> "😄"
                                        else -> "😐"
                                    }
                                    Text(moodIcon, fontSize = 14.sp)
                                }
                                StatusIndicator(isHandDetected)
                            }
                        }
                    }
                }

                // NEW: Live Transcription (Glassmorphic Top Overlay)
                val liveTranscription by liveTranscriptionState
                AnimatedVisibility(
                    visible = liveTranscription.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier.padding(top = 24.dp).align(Alignment.CenterHorizontally)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.85f),
                        border = BorderStroke(2.dp, CyanPrimary.copy(alpha = 0.6f)),
                        shadowElevation = 8.dp
                    ) {
                        Text(
                            liveTranscription,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Smart Reply Suggestions
                AnimatedVisibility(
                    visible = suggestions.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEachIndexed { index, suggestion ->
                            val isSelected = selectedSuggestionIndexState.intValue == index
                            SuggestionChip(
                                onClick = { onSuggestionClick(suggestion) },
                                shape = RoundedCornerShape(12.dp),
                                label = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(suggestion, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    labelColor = if (isSelected) Color.Black else Color.White
                                ),
                                border = if (isSelected) null else BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f))
                            )
                        }
                    }
                }

                // Glassmorphic Sentence Engine Bar
                androidx.compose.animation.AnimatedVisibility(
                    visible = sentenceBufferState.isNotEmpty(),
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    val scaleByHold by animateFloatAsState(
                    targetValue = if (gestureHoldProgressState.floatValue > 0f) 1.05f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "scale"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .scale(scaleByHold),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.5f)),
                    shadowElevation = 12.dp
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "BUILDING SENTENCE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 2.sp,
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    sentenceBufferState.joinToString(" "),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp
                                    ),
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = { speak(sentenceBufferState.joinToString(" ")) },
                                modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speak", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(onClick = { 
                                sentenceBufferState.clear()
                                englishSentenceBufferState.clear()
                                smartReplyManager.clearHistory()
                            }, modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)) {
                                Icon(Icons.Default.Delete, "Clear", tint = Color.White)
                            }
                        }
                    }
                }
                }

                // Bottom Content
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = spokenSentence.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(bottom = 24.dp)
                            .clickable { speak(spokenSentence) },
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isSpeaking) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    ) {
                            Row(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSpeaking) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                }
                                Text(
                                    text = spokenSentence,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        textAlign = TextAlign.Center,
                                        shadow = androidx.compose.ui.graphics.Shadow(
                                            color = Color.Black.copy(alpha = 0.5f),
                                            blurRadius = 8f
                                        )
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Current Tracking Label
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isHandDetected) "TRACKING: $detectedGesture" else "SCANNING FOR HAND...",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = if (isHandDetected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun StatusIndicator(isDetected: Boolean) {
        val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "AlphaPulse"
        )

        val color = if (isDetected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = if (isDetected) pulseAlpha else 1f }
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isDetected) "SYSTEM ACTIVE" else "IDLE",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = color
                )
            )
        }
    }

    // GestureListener Callbacks
    override fun onTrackingUpdate(gesture: Gesture, landmarks: List<List<NormalizedLandmark>>, customName: String?) {
        runOnUiThread {
            isHandInFrameState.value = true
            
            // Logic for Context-Aware Selection
            if (isContextModeState.value && suggestionsState.isNotEmpty()) {
                selectedSuggestionIndexState.intValue = when (gesture) {
                    Gesture.INDEX_FINGER_UP -> 0
                    Gesture.PEACE_SIGN -> 1
                    Gesture.THREE_FINGERS -> 2
                    else -> -1
                }
            } else {
                selectedSuggestionIndexState.intValue = -1
            }

            detectedGestureState.value = when {
                selectedSuggestionIndexState.intValue != -1 && selectedSuggestionIndexState.intValue < suggestionsState.size -> 
                    "${if (isHindiModeState.value) "उत्तर" else "REPLY"}: ${suggestionsState[selectedSuggestionIndexState.intValue]}"
                customName != null -> "${if (isHindiModeState.value) "कस्टम" else "CUSTOM"}: $customName"
                gesture != Gesture.NONE -> GestureLocalizer.getSentence(gesture, isHindiModeState.value)
                else -> if (isHindiModeState.value) "ट्रैकिंग..." else "Tracking..."
            }
            landmarksState.value = landmarks
        }
    }

    override fun onGestureFinalized(gesture: Gesture) {
        runOnUiThread {
            // Handle Context-Aware Selection
            if (isContextModeState.value && suggestionsState.isNotEmpty()) {
                val index = when (gesture) {
                    Gesture.INDEX_FINGER_UP -> 0
                    Gesture.PEACE_SIGN -> 1
                    Gesture.THREE_FINGERS -> 2
                    else -> -1
                }
                if (index != -1 && index < suggestionsState.size) {
                    val selection = suggestionsState[index]
                    speak(selection)
                    spokenSentenceState.value = selection
                    if (!sentenceBufferState.contains(selection)) {
                        sentenceBufferState.add(selection)
                        
                        // Smart Reply and Suggester need English
                        lifecycleScope.launch {
                            val englishSelection = if (isHindiModeState.value) 
                                translationManager.translateHiToEn(selection) else selection
                            
                            englishSentenceBufferState.add(englishSelection)
                            smartReplyManager.addMessage(englishSelection, true)
                            updateMergedSuggestions()
                        }
                    }
                    return@runOnUiThread
                }
            }

            if (onlyCustomGesturesState.value) return@runOnUiThread // Logic safety

            if (appModeState.value == AppMode.LEARN) {
                if (gesture == targetGestureState.value) {
                    learnScoreState.intValue += 10
                    learnStreakState.intValue += 1
                    isMatchState.value = true
                    val feedback = if (isHindiModeState.value) "सही! यह ${GestureLocalizer.getSentence(gesture, true)} है" 
                                    else "Correct! That is ${gesture.name.replace("_", " ")}"
                    speak(feedback)
                } else {
                    learnStreakState.intValue = 0
                }
            } else if (appModeState.value == AppMode.TRANSLATE) {
                val sentence = GestureLocalizer.getSentence(gesture, isHindiModeState.value)
                val englishKeyword = gesture.sentence
                
                spokenSentenceState.value = sentence
                if (gesture != Gesture.NONE && !sentenceBufferState.contains(sentence)) {
                    sentenceBufferState.add(sentence)
                    englishSentenceBufferState.add(englishKeyword)
                    smartReplyManager.addMessage(englishKeyword, false)
                    updateMergedSuggestions()
                }
                speak(sentence)
            }
        }
    }

    override fun onGestureProgress(progress: Float) {
        runOnUiThread {
            gestureHoldProgressState.floatValue = progress
        }
    }

    override fun onCustomGestureFinalized(name: String) {
        runOnUiThread {
            if (appModeState.value == AppMode.TRANSLATE) {
                spokenSentenceState.value = name
                if (!sentenceBufferState.contains(name)) {
                    sentenceBufferState.add(name)
                    smartReplyManager.addMessage(name, false)
                    updateMergedSuggestions() // Update phrase suggestions immediately
                }
                speak(name)
            }
        }
    }

    override fun onHandLost() {
        runOnUiThread {
            isHandInFrameState.value = false
            detectedGestureState.value = "Scanning..."
            landmarksState.value = emptyList()
            spokenSentenceState.value = "" // Clear last sentence on hand lost
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkTtsLanguage() {
        if (isHindiModeState.value) {
            val primary = Locale("hi", "IN")
            val result = tts?.setLanguage(primary)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallback = Locale("hi")
                val result2 = tts?.setLanguage(fallback)
                if (result2 == TextToSpeech.LANG_MISSING_DATA || result2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                    runOnUiThread {
                        Toast.makeText(this, "Hindi speech engine data is missing. Please check your system settings.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            tts?.setLanguage(Locale.US)
        }
    }

    private fun speak(text: String) {
        if (text.isEmpty() || tts == null) return
        
        var modifiedText = text
        if (isSurprisedState.value) {
            modifiedText += "?"
        }

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AvowGesture")
        
        // Emotion modulation
        if (isSmilingState.floatValue > 0.6f) {
            tts?.setPitch(1.3f)
            tts?.setSpeechRate(1.2f)
        } else {
            tts?.setPitch(1.0f)
            tts?.setSpeechRate(1.0f)
        }

        checkTtsLanguage()
        tts?.speak(modifiedText, TextToSpeech.QUEUE_FLUSH, params, "AvowGesture")
    }

    private fun updateMergedSuggestions(smartSuggestions: List<String> = emptyList()) {
        val phraseSuggestions = sentenceSuggester.getSuggestions(englishSentenceBufferState.toList())
        val isHindi = isHindiModeState.value
        
        lifecycleScope.launch {
            val combined = (phraseSuggestions + smartSuggestions).distinct()
            val finalSuggestions = if (isHindi) {
                val translated = mutableListOf<String>()
                for (s in combined) {
                    translated.add(translationManager.translateEnToHi(s))
                }
                translated
            } else {
                combined
            }
            
            runOnUiThread {
                suggestionsState.clear()
                suggestionsState.addAll(finalSuggestions)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager?.destroy()
        cameraExecutor.shutdown()
        gestureAnalyzer?.close()
        smartReplyManager.close()
        faceDetector?.close()
        translationManager.close()
        tts?.stop()
        tts?.shutdown()
    }
}