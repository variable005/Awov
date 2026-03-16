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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.avow.ui.theme.*
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import android.speech.tts.UtteranceProgressListener
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.clickable

class MainActivity : ComponentActivity(), GestureAnalyzer.GestureListener {

    private lateinit var cameraExecutor: ExecutorService
    private var gestureAnalyzer: GestureAnalyzer? = null
    private var tts: TextToSpeech? = null

    enum class AppMode { TRANSLATE, LEARN, TEACH }

    // UI State
    private var detectedGestureState = mutableStateOf("No Hand")
    private var spokenSentenceState = mutableStateOf("")
    private var sentenceBufferState = mutableStateListOf<String>() // For Sentence Engine
    private var isHandInFrameState = mutableStateOf(false)
    private var isSpeakingState = mutableStateOf(false)
    private var landmarksState = mutableStateOf<List<NormalizedLandmark>>(emptyList())
    
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

    private val PREFS_NAME = "avow_gestures"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCustomGestures() // NEW: Load on startup

        cameraExecutor = Executors.newSingleThreadExecutor()
        gestureAnalyzer = GestureAnalyzer(this, this)
        
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

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "AVOW AAC",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    val selectedTab = when(appModeState.value) {
                        AppMode.TRANSLATE -> 0
                        AppMode.LEARN -> 1
                        AppMode.TEACH -> 2
                    }
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.width(280.dp), // Increased width for 3 tabs
                        containerColor = Color.Transparent,
                        contentColor = CyanPrimary,
                        divider = {},
                        indicator = { tabPositions ->
                            if (tabPositions.isNotEmpty()) {
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                        color = MaterialTheme.colorScheme.primary,
                                        height = 3.dp
                                    )
                            }
                        }
                    ) {
                        Tab(
                            selected = appModeState.value == AppMode.TRANSLATE,
                            onClick = { appModeState.value = AppMode.TRANSLATE },
                            text = { Text("LIVE", style = MaterialTheme.typography.labelSmall) }
                        )
                        Tab(
                            selected = appModeState.value == AppMode.LEARN,
                            onClick = { 
                                appModeState.value = AppMode.LEARN 
                                resetLearnSession()
                            },
                            text = { Text("LEARN", style = MaterialTheme.typography.labelSmall) }
                        )
                        Tab(
                            selected = appModeState.value == AppMode.TEACH,
                            onClick = { appModeState.value = AppMode.TEACH },
                            text = { Text("TEACH", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    IconButton(onClick = { showSheet = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hasCameraPermission) {
                CameraPreview()
                LandmarkOverlay(landmarksState.value)
                ViewfinderOverlay()
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Camera Permission Required",
                        color = Color.White
                    )
                }
            }

            // HUD Overlays
            when (appModeState.value) {
                AppMode.TRANSLATE -> HUDOverlay()
                AppMode.LEARN -> LearnModeUI()
                AppMode.TEACH -> TeachModeUI()
            }

            if (showSheet) {
                GestureDictionarySheet(
                    sheetState = sheetState,
                    onDismiss = { showSheet = false }
                )
            }
        }
    }
}

    @Composable
    fun LandmarkOverlay(landmarks: List<NormalizedLandmark>) {
        if (landmarks.isEmpty()) return

        val primaryColor = MaterialTheme.colorScheme.primary
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
        val tertiaryColor = MaterialTheme.colorScheme.tertiary

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val connections = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4, // Thumb
                0 to 5, 5 to 6, 6 to 7, 7 to 8, // Index
                5 to 9, 9 to 10, 10 to 11, 11 to 12, // Middle
                9 to 13, 13 to 14, 14 to 15, 15 to 16, // Ring
                13 to 17, 17 to 18, 18 to 19, 19 to 20, // Pinky
                0 to 17 // Palm base
            )

            // Draw gesture connections
            connections.forEach { (start, end) ->
                val startPoint = landmarks[start]
                val endPoint = landmarks[end]
                val p1 = androidx.compose.ui.geometry.Offset(startPoint.x() * width, startPoint.y() * height)
                val p2 = androidx.compose.ui.geometry.Offset(endPoint.x() * width, endPoint.y() * height)
                
                // Single clean line
                drawLine(
                    color = primaryColor.copy(alpha = 0.6f),
                    start = p1, end = p2,
                    strokeWidth = 4f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Draw clean dots
            landmarks.forEach { landmark ->
                val center = androidx.compose.ui.geometry.Offset(landmark.x() * width, landmark.y() * height)
                drawCircle(
                    color = if (landmark == landmarks[0]) onSurfaceColor else tertiaryColor,
                    radius = 6f,
                    center = center
                )
            }
        }
    }

    @Composable
    fun ViewfinderOverlay() {
        val primaryColor = MaterialTheme.colorScheme.primary
        
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val w = size.width
            val h = size.height
            val len = 60f
            val color = primaryColor.copy(alpha = 0.6f)
            val stroke = 4f

            // Corners
            // Top Left
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(len, 0f), stroke)
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, len), stroke)
            // Top Right
            drawLine(color, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - len, 0f), stroke)
            drawLine(color, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, len), stroke)
            // Bottom Left
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(len, h), stroke)
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - len), stroke)
            // Bottom Right
            drawLine(color, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - len, h), stroke)
            drawLine(color, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - len), stroke)
            
            // Central tech marks
            drawRect(
                color = primaryColor.copy(alpha = 0.3f),
                topLeft = androidx.compose.ui.geometry.Offset(w/2 - 20f, h/2 - 1f),
                size = androidx.compose.ui.geometry.Size(40f, 2f)
            )
            drawRect(
                color = primaryColor.copy(alpha = 0.3f),
                topLeft = androidx.compose.ui.geometry.Offset(w/2 - 1f, h/2 - 20f),
                size = androidx.compose.ui.geometry.Size(2f, 40f)
            )
        }
    }

    @Composable
    fun CameraPreview() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val previewView = remember { PreviewView(context) }

        LaunchedEffect(Unit) {
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

                    val landmarks = landmarksState.value
                    var palmSize = 1.0f
                    if (landmarks.size >= 10) {
                        val wrist = landmarks[0]
                        val middleMCP = landmarks[9]
                        palmSize = Math.hypot(
                            (middleMCP.x() - wrist.x()).toDouble(),
                            (middleMCP.y() - wrist.y()).toDouble()
                        ).toFloat()
                    }
                    if (palmSize < 0.01f) palmSize = 1.0f

                    val customMatch = gestureAnalyzer?.findMatchingCustomGesture(landmarks, customGesturesState, palmSize)
                    
                    gestureAnalyzer?.analyze(processedBitmap, timestamp)
                    
                    if (customMatch != null && appModeState.value == AppMode.TRANSLATE) {
                        runOnUiThread {
                            detectedGestureState.value = "CUSTOM: $customMatch"
                        }
                    }

                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
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
                Text(
                    "Gesture Dictionary",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Gesture.values().filter { it != Gesture.NONE }.forEach { gesture ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {}
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                gesture.name.replace("_", " "),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "\"${gesture.sentence}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "TEACH NEW SIGN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        TextField(
                            value = gestureName,
                            onValueChange = { gestureName = it },
                            label = { Text("Sign Name (e.g. 'Coffee')") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isRecording
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        if (isRecording) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("HOLD STEADY...", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Button(
                                onClick = { startRecording(gestureName) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = gestureName.isNotBlank() && isHandInFrameState.value,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("RECORD 2S SNAPSHOT")
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
            customGesturesState[name] = currentLandmarks
            gestureAnalyzer?.updateCustomTemplates(customGesturesState.toMap())
            persistCustomGestures() // Save to disk
            speak("Learned new sign: $name")
        }
    }

    private fun persistCustomGestures() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(customGesturesState.toMap())
        prefs.edit().putString("templates", json).apply()
    }

    private fun loadCustomGestures() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    modifier = Modifier.size(120.dp).scale(scale),
                    tint = Color.Green
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "STREAK: ${learnStreakState.intValue}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Yellow
                )
                Text(
                    "+10 POINTS",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyanPrimary
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress Header
                ElevatedCard(
                    modifier = Modifier.padding(top = 16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                ) {
                    Text(
                        "PROGRESS: $score GESTURES MASTERED",
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
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isMatch) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "REPLICATE THIS SIGN",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { holdProgress },
                                    modifier = Modifier.size(100.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 8.dp,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text(
                                    targetGesture.sentence.uppercase(),
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                targetGesture.hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp)
                            )
                            
                            LinearProgressIndicator(
                                progress = { score.toFloat() / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(8.dp),
                                color = Color(0xFF4CAF50),
                                strokeCap = StrokeCap.Round
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
                            Text("STREAK: $streak", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), color = Color.Black)
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

    @Composable
    fun HUDOverlay() {
        val detectedGesture by detectedGestureState
        val spokenSentence by spokenSentenceState
        val isHandDetected by isHandInFrameState
        val isSpeaking by isSpeakingState

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Detected Gesture Label (Right Aligned Top)
                Box(modifier = Modifier.fillMaxWidth()) {
                    ElevatedCard(
                        modifier = Modifier
                            .align(Alignment.TopEnd),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "LANDMARK STATUS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            StatusIndicator(isHandDetected)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

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

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .scale(scaleByHold),
                    shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "BUILDING SENTENCE",
                                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    sentenceBufferState.joinToString(" "),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { speak(sentenceBufferState.joinToString(" ")) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speak", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { sentenceBufferState.clear() }) {
                                Icon(Icons.Default.Delete, "Clear", tint = MaterialTheme.colorScheme.error)
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
                text = if (isDetected) "LIVE" else "OFFLINE",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = color
                )
            )
        }
    }

    // GestureListener Callbacks
    override fun onTrackingUpdate(gesture: Gesture, landmarks: List<NormalizedLandmark>) {
        runOnUiThread {
            isHandInFrameState.value = true
            detectedGestureState.value = if (gesture != Gesture.NONE) gesture.name else "Tracking..."
            landmarksState.value = landmarks
        }
    }

    override fun onGestureFinalized(gesture: Gesture) {
        runOnUiThread {
            if (appModeState.value == AppMode.LEARN) {
                if (gesture == targetGestureState.value) {
                    learnScoreState.intValue += 10
                    learnStreakState.intValue += 1
                    isMatchState.value = true
                    speak("Correct! That is ${gesture.name.replace("_", " ")}")
                    // Wait a bit then move to next
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // This delay is now handled by SuccessSplash, but keeping it here for now based on instruction
                    }, 2000)
                } else {
                    learnStreakState.intValue = 0 // Reset streak on incorrect gesture
                }
            } else if (appModeState.value == AppMode.TRANSLATE) {
                spokenSentenceState.value = gesture.sentence
                if (gesture != Gesture.NONE && !sentenceBufferState.contains(gesture.sentence)) {
                    sentenceBufferState.add(gesture.sentence)
                }
                speak(gesture.sentence)
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
            spokenSentenceState.value = name
            if (!sentenceBufferState.contains(name)) {
                sentenceBufferState.add(name)
            }
            speak(name)
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

    private fun speak(text: String) {
        if (text.isEmpty()) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AvowGesture")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "AvowGesture")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        gestureAnalyzer?.close()
        tts?.stop()
        tts?.shutdown()
    }
}