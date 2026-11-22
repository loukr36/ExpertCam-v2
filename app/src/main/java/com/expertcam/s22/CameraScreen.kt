package com.expertcam.s22

import android.graphics.Bitmap
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

val OledBlack = Color(0xFF000000)
val AnthraciteSurface = Color(0xCC1C1C1E)
val ExpertGold = Color(0xFFF5C518)
val RecordRed = Color(0xFFFF3B30)
val TextSecondary = Color(0xFFA0A0A5)

@Composable
fun CameraScreen(
    controller: Camera2Controller,
    hasAudioPermission: Boolean,
    isSystemCaptureIntent: Boolean,
    onOpenPreview: (CapturedMedia) -> Unit,
    onOpenDiagnostic: () -> Unit
) {
    val context = LocalContext.current
    val state by controller.uiState.collectAsState()

    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            controller.closeCamera()
        }
    }

    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            delay(1000L)
            controller.incrementRecordingSeconds()
        }
    }

    LaunchedEffect(state.errorBanner) {
        if (state.errorBanner != null) {
            delay(5000L)
            controller.clearErrorBanner()
        }
    }

    LaunchedEffect(focusTapPoint) {
        if (focusTapPoint != null) {
            delay(1800L)
            focusTapPoint = null
        }
    }

    LaunchedEffect(state.lastCapturedMedia) {
        val media = state.lastCapturedMedia
        thumbnailBitmap = if (media != null) {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(media.uri, Size(140, 140), null)
                } catch (_: Exception) {
                    null
                }
            }
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 1. VISEUR PLEIN ÉCRAN (SANS GRILLE, 100 % AUTOMATIQUE)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewfinderSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.005f) {
                            controller.applyZoomDelta(zoom)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                            focusTapPoint = offset
                            controller.tapToFocus(
                                offset.x / viewfinderSize.width,
                                offset.y / viewfinderSize.height
                            )
                        }
                    }
                }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        controller.attachTextureView(tv)
                    }
                }
            )

            // Cercle jaune simple lors de la mise au point au toucher
            focusTapPoint?.let { pt ->
                Canvas(
                    modifier = Modifier
                        .offset {
                            val halfPx = 34.dp.toPx()
                            val sizePx = 68.dp.toPx().roundToInt()
                            IntOffset(
                                (pt.x - halfPx).roundToInt().coerceIn(0, (viewfinderSize.width - sizePx).coerceAtLeast(0)),
                                (pt.y - halfPx).roundToInt().coerceIn(0, (viewfinderSize.height - sizePx).coerceAtLeast(0))
                            )
                        }
                        .size(68.dp)
                ) {
                    drawCircle(
                        color = ExpertGold,
                        radius = size.minDimension / 2f,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // 2. BARRE SUPÉRIEURE : FLASH • 1080p/4K (VIDÉO) • DIAG
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val flashIcon = when (state.flash) {
                    FlashOption.OFF -> Icons.Default.FlashOff
                    FlashOption.AUTO -> Icons.Default.FlashAuto
                    FlashOption.ON -> Icons.Default.FlashOn
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(AnthraciteSurface)
                        .clickable { controller.cycleFlash() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = flashIcon,
                        contentDescription = "Flash",
                        tint = if (state.flash != FlashOption.OFF) ExpertGold else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.flash.label,
                        color = if (state.flash != FlashOption.OFF) ExpertGold else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Centre : Chronomètre REC ou sélecteur 1080p / 4K en mode vidéo
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isRecording) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(RecordRed.copy(alpha = 0.9f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.White, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = formatDuration(state.recordingSeconds),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (state.mode == CameraMode.VIDEO) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(AnthraciteSurface)
                                .border(
                                    width = 1.dp,
                                    color = if (state.videoResolution == VideoResolution.UHD_4K) ExpertGold else Color.Transparent,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable(enabled = !state.isRecording) {
                                    controller.toggleVideoResolution()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = state.videoResolution.label,
                                color = if (state.videoResolution == VideoResolution.UHD_4K) ExpertGold else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Bouton DIAG (ouvre l'écran de diagnostic complet)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(AnthraciteSurface)
                        .border(1.dp, ExpertGold.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                        .clickable(enabled = !state.isRecording, onClick = onOpenDiagnostic)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "DIAG",
                        color = ExpertGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Discret bandeau d'état uniquement en cas d'erreur
            state.errorBanner?.let { err ->
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xE62A0E0E))
                        .border(1.dp, RecordRed.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = err, color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // 3. ZONE INFÉRIEURE : OBJECTIFS 1x / 3x + PHOTO/VIDÉO + DÉCLENCHEUR
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sélecteur d'objectifs : UNIQUEMENT 1x et 3x (aucun 0.6x)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundLensButton(
                    label = "1x",
                    active = state.activeLens == SafeLensType.WIDE,
                    enabled = !state.isRecording,
                    onClick = { controller.switchLens(SafeLensType.WIDE) }
                )
                RoundLensButton(
                    label = "3x",
                    active = state.activeLens == SafeLensType.TELE,
                    enabled = !state.isRecording && state.hasTeleLens,
                    onClick = { controller.switchLens(SafeLensType.TELE) }
                )
            }

            Spacer(Modifier.height(14.dp))

            // Switch PHOTO | VIDÉO
            if (!isSystemCaptureIntent) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    ModeLabel("PHOTO", state.mode == CameraMode.PHOTO) {
                        controller.setMode(CameraMode.PHOTO)
                    }
                    ModeLabel("VIDÉO", state.mode == CameraMode.VIDEO) {
                        controller.setMode(CameraMode.VIDEO)
                    }
                }
            }

            // Miniature • Déclencheur • Selfie
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(AnthraciteSurface)
                        .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                        .clickable(enabled = state.lastCapturedMedia != null) {
                            state.lastCapturedMedia?.let(onOpenPreview)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val thumb = thumbnailBitmap
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "Dernière prise",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Aperçu",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable {
                            if (state.mode == CameraMode.VIDEO) {
                                if (state.isRecording) {
                                    controller.stopVideoRecording()
                                } else {
                                    controller.startVideoRecording(hasAudioPermission)
                                }
                            } else {
                                controller.capturePhoto()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        state.mode == CameraMode.PHOTO -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                        state.isRecording -> {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(RecordRed)
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(RecordRed)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.activeLens == SafeLensType.FRONT) ExpertGold.copy(alpha = 0.25f)
                            else AnthraciteSurface
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (state.activeLens == SafeLensType.FRONT) ExpertGold else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable(enabled = !state.isRecording && state.hasFrontLens) {
                            controller.toggleFrontBack()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Selfie",
                        tint = when {
                            state.isRecording -> TextSecondary
                            state.activeLens == SafeLensType.FRONT -> ExpertGold
                            else -> Color.White
                        },
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundLensButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) ExpertGold else AnthraciteSurface)
            .border(
                width = 1.5.dp,
                color = if (active) ExpertGold else Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = when {
                active -> OledBlack
                !enabled -> TextSecondary
                else -> Color.White
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = if (active) ExpertGold else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(2.dp)
                .background(if (active) ExpertGold else Color.Transparent)
        )
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}
