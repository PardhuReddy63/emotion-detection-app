package com.example.emotiondetectionapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.* 
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

@SuppressLint("UnsafeOptInUsageError") // For experimental ImageAnalysis use
@Composable
fun CameraScreen(navController: NavController) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    var detectedEmotion by remember { mutableStateOf("No face detected") }
    var emotionScore by remember { mutableIntStateOf(0) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Permission state and launcher for Camera
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasCameraPermission = it
        if (!it) {
            Log.e("CameraScreen", "Camera permission denied.")
            // Optionally, navigate back or show a message if permission is denied
            navController.popBackStack()
        }
    }

    // Request camera permission on launch if not already granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize()) {

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                Card {
                    Text(
                        text = "Emotion: $detectedEmotion ($emotionScore/100)",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text("Back")
            }
        }

        LaunchedEffect(previewView) {
            startCamera(
                context,
                lifecycleOwner,
                previewView,
                cameraExecutor
            ) { emotion, score ->
                detectedEmotion = emotion
                emotionScore = score
            }
        }
    } else {
        // Show a message or a button to request permission again
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required to use this feature.")
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }
}


@SuppressLint("UnsafeOptInUsageError") // For ImageProxy.getImage()
private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraExecutor: ExecutorService,
    onEmotionDetected: (String, Int) -> Unit
) {

    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({

        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()

        val detector = FaceDetection.getClient(options)

        imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

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
                            val score = (smileProb * 100).toInt().coerceIn(1, 100)

                            onEmotionDetected(emotion, score)

                        } else {
                            onEmotionDetected("No face detected", 0)
                        }
                    }
                    .addOnFailureListener {
                        Log.e("CameraScreen", "Face detection failed: ${it.message}")
                        onEmotionDetected("Detection failed", 0)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            Log.e("CameraScreen", "Camera binding failed: ${e.message}")
        }

    }, ContextCompat.getMainExecutor(context))
}
