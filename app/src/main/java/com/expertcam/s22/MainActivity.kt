package com.expertcam.s22

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private enum class ActiveScreen { CAMERA, PREVIEW, DIAGNOSTIC }

class MainActivity : ComponentActivity() {

    private lateinit var cameraScanner: CameraScanner
    private lateinit var cameraController: Camera2Controller

    private var activeScreenState = mutableStateOf(ActiveScreen.CAMERA)
    private var previewMediaState = mutableStateOf<CapturedMedia?>(null)
    private var externalOutputUri: Uri? = null
    private var isImageCaptureIntent: Boolean = false
    private var isVideoCaptureIntent: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        parseIncomingSystemIntent(intent)

        cameraScanner = CameraScanner(applicationContext)
        cameraController = Camera2Controller(
            context = applicationContext,
            scanner = cameraScanner,
            externalOutputUri = externalOutputUri,
            onMediaCaptured = { captured ->
                runOnUiThread {
                    if (isImageCaptureIntent || isVideoCaptureIntent) {
                        previewMediaState.value = captured
                        activeScreenState.value = ActiveScreen.PREVIEW
                    }
                }
            }
        )

        if (isVideoCaptureIntent) {
            cameraController.setMode(CameraMode.VIDEO)
        }

        val requiredPermissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

        setContent {
            var hasCameraPerm by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }
            var hasAudioPerm by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }

            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                hasCameraPerm = grants[Manifest.permission.CAMERA] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                hasAudioPerm = grants[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasCameraPerm) {
                    cameraController.refreshScannerCapabilities()
                }
            }

            LaunchedEffect(Unit) {
                if (!hasCameraPerm || !hasAudioPerm) {
                    permLauncher.launch(requiredPermissions)
                }
            }

            val currentScreen by activeScreenState
            val previewMedia by previewMediaState
            val isSystemIntent = isImageCaptureIntent || isVideoCaptureIntent

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OledBlack)
            ) {
                if (!hasCameraPerm) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ExpertCam S22+",
                            color = ExpertGold,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Autorisez la caméra et le micro pour utiliser les capteurs 1x, 3x, Selfie et l'outil de diagnostic.",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { permLauncher.launch(requiredPermissions) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ExpertGold,
                                contentColor = OledBlack
                            )
                        ) {
                            Text("Autoriser la caméra", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    when (currentScreen) {
                        ActiveScreen.DIAGNOSTIC -> {
                            DiagnosticScreen(
                                scanner = cameraScanner,
                                onBackToCamera = {
                                    activeScreenState.value = ActiveScreen.CAMERA
                                }
                            )
                        }

                        ActiveScreen.PREVIEW -> {
                            val media = previewMedia
                            if (media != null) {
                                PreviewShareScreen(
                                    media = media,
                                    isSystemCaptureIntent = isSystemIntent,
                                    onBack = {
                                        previewMediaState.value = null
                                        activeScreenState.value = ActiveScreen.CAMERA
                                    },
                                    onDeleted = {
                                        previewMediaState.value = null
                                        activeScreenState.value = ActiveScreen.CAMERA
                                    },
                                    onConfirmSystemIntent = { returnSystemCaptureResult(media) }
                                )
                            } else {
                                activeScreenState.value = ActiveScreen.CAMERA
                            }
                        }

                        ActiveScreen.CAMERA -> {
                            CameraScreen(
                                controller = cameraController,
                                hasAudioPermission = hasAudioPerm,
                                isSystemCaptureIntent = isSystemIntent,
                                onOpenPreview = { media ->
                                    cameraController.closeCamera()
                                    previewMediaState.value = media
                                    activeScreenState.value = ActiveScreen.PREVIEW
                                },
                                onOpenDiagnostic = {
                                    cameraController.closeCamera()
                                    activeScreenState.value = ActiveScreen.DIAGNOSTIC
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cameraController.closeCamera()
    }

    override fun onResume() {
        super.onResume()
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPerm && activeScreenState.value == ActiveScreen.CAMERA) {
            cameraController.openLensCascade(cameraController.uiState.value.activeLens)
        }
    }

    private fun parseIncomingSystemIntent(incoming: Intent?) {
        val action = incoming?.action
        isImageCaptureIntent = action == MediaStore.ACTION_IMAGE_CAPTURE
        isVideoCaptureIntent = action == MediaStore.ACTION_VIDEO_CAPTURE

        externalOutputUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            incoming?.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            incoming?.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
        }
    }

    private fun returnSystemCaptureResult(media: CapturedMedia) {
        val resultIntent = Intent()
        if (externalOutputUri != null) {
            resultIntent.data = externalOutputUri
        } else {
            resultIntent.data = media.uri
            if (isImageCaptureIntent && !media.isVideo) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val thumb = contentResolver.openInputStream(media.uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (thumb != null) {
                        resultIntent.putExtra("data", thumb)
                    }
                } catch (_: Exception) {}
            }
        }
        resultIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
