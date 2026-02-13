package com.example.emotiondetectionapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.emotiondetectionapp.network.RetrofitClient
import com.example.emotiondetectionapp.network.TextRequest
import kotlinx.coroutines.launch

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import android.util.Log
import android.app.Activity.RESULT_OK

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val navController = rememberNavController()

            NavHost(navController = navController, startDestination = "emotion_screen") {
                composable("emotion_screen") {
                    EmotionScreen()
                }
                composable("camera_screen") {
                    CameraScreen(navController = navController)
                }
            }
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    fun EmotionScreen() {

        var userInput by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("Enter text or use voice") }
        var history by remember { mutableStateOf(listOf<String>()) }
        var isLoading by remember { mutableStateOf(false) }

        val context = LocalContext.current

        // Modern Voice Input Launcher
        val voiceLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
                if (activityResult.resultCode == RESULT_OK) {
                    val spokenText =
                        activityResult.data?.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                        )?.get(0)

                    spokenText?.let {
                        userInput = it
                    }
                }
            }

        val cameraLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.TakePicturePreview()
            ) { bitmap ->

                bitmap?.let {
                    detectEmotionFromBitmap(it) { emotion ->
                        result = emotion
                    }
                }
            }

        // Permission Check for Camera
        var hasCameraPermission by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            hasCameraPermission = it
            if (it) {
                Log.d("Permission", "Camera permission granted, launching camera.")
                // Re-launch camera if permission was just granted
                cameraLauncher.launch(null)
            } else {
                Log.e("Permission", "Camera permission denied.")
            }
        }

        // Permission Check for Microphone
        var hasMicrophonePermission by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }

        val microphonePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            hasMicrophonePermission = it
            if (it) {
                Log.d("Permission", "Microphone permission granted, starting voice input.")
                // Re-launch voice input if permission was just granted
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                intent.putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak something..."
                )
                voiceLauncher.launch(intent)
            } else {
                Log.e("Permission", "Microphone permission denied.")
            }
        }

        fun startVoiceInput() {
            if (hasMicrophonePermission) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                intent.putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Speak something..."
                )
                voiceLauncher.launch(intent)
            } else {
                microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        fun openCamera() {
            if (hasCameraPermission) {
                cameraLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }


        Surface(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {

                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {

                        OutlinedTextField(
                            value = userInput,
                            onValueChange = { userInput = it },
                            label = { Text("Enter your text") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            Button(
                                onClick = {
                                    if (userInput.isNotEmpty()) {
                                        isLoading = true

                                        lifecycleScope.launch {
                                            try {
                                                val response =
                                                    RetrofitClient.api.predictEmotion(
                                                        TextRequest(userInput)
                                                    )

                                                result = response.emotion

                                                history = (
                                                        listOf("${response.emotion} - ${System.currentTimeMillis()}")
                                                                + history
                                                        ).take(10)

                                            } catch (e: Exception) {
                                                result = "Error: ${e.message}"
                                            }

                                            isLoading = false
                                        }
                                    } else {
                                        result = "Please enter some text"
                                    }
                                }
                            ) {
                                Text("Detect")
                            }

                            Button(
                                onClick = { startVoiceInput() }
                            ) {
                                Text("🎤 Speak")
                            }
                        }

                        // New Camera Button
                        Spacer(modifier = Modifier.height(10.dp)) // Add a small spacer for separation
                        Button(
                            onClick = { openCamera() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📷 Take Selfie")
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        if (isLoading) {
                            CircularProgressIndicator()
                        } else {

                            AnimatedContent(
                                targetState = result,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(400)) togetherWith
                                            fadeOut(animationSpec = tween(200))
                                },
                                label = ""
                            ) { animatedResult ->

                                val scale by animateFloatAsState(
                                    targetValue = 1.1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    ),
                                    label = ""
                                )

                                Text(
                                    text = animatedResult,
                                    modifier = Modifier.scale(scale),
                                    style = MaterialTheme.typography.headlineLarge
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                Text(
                    text = "Emotion History",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(history) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = item,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun detectEmotionFromBitmap(
    bitmap: Bitmap,
    onResult: (String) -> Unit
) {

    val image = InputImage.fromBitmap(bitmap, 0)

    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // IMPORTANT
        .build()

    val detector = FaceDetection.getClient(options)

    detector.process(image)
        .addOnSuccessListener { faces ->

            if (faces.isNotEmpty()) {

                val face = faces[0]
                val smileProb = face.smilingProbability ?: 0f

                val emotion = when {
                    smileProb > 0.7f -> "😊 Happy"
                    smileProb > 0.3f -> "🙂 Slight Smile"
                    else -> "😐 Neutral"
                }

                onResult(emotion)

            } else {
                onResult("No face detected")
            }
        }
        .addOnFailureListener {
            onResult("Detection failed")
        }
}