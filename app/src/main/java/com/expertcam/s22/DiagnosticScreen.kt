package com.expertcam.s22

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private data class DiagTestOption(
    val key: String,
    val label: String,
    val cameraId: String,
    val zoomOverride: Float? = null
)

private data class DiagResult(
    val key: String,
    val label: String,
    val success: Boolean,
    val frames: Int,
    val message: String
)

private val DiagGreen = Color(0xFF34C759)
private val DiagRedBg = Color(0xFF2D1010)
private val DiagGreenBg = Color(0xFF102A18)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticScreen(
    scanner: CameraScanner,
    onBackToCamera: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val sessionExecutor = remember { Executors.newSingleThreadExecutor() }

    val cameras = remember {
        scanner.scanAllCameras()
        scanner.allCameras
    }

    val testOptions = remember(cameras) {
        buildList {
            // Boutons dédiés ID 0 avec zooms 0.6x, 1.0x, 3.0x
            add(DiagTestOption("id0_06", "ID 0 [0.6x]", "0", 0.6f))
            add(DiagTestOption("id0_10", "ID 0 [1.0x]", "0", 1.0f))
            add(DiagTestOption("id0_30", "ID 0 [3.0x]", "0", 3.0f))
            // Un bouton individuel pour CHAQUE caméra détectée (y compris 0.6x et cachées)
            cameras.forEach { cam ->
                val hiddenTag = if (cam.isHidden) " Caché" else ""
                add(
                    DiagTestOption(
                        key = "cam_${cam.id}",
                        label = "ID ${cam.id} (${cam.role.label}$hiddenTag)",
                        cameraId = cam.id,
                        zoomOverride = null
                    )
                )
            }
        }
    }

    val results = remember { mutableStateMapOf<String, DiagResult>() }
    var activeTestLabel by remember { mutableStateOf("Aucun test actif") }
    var activeStatusMsg by remember { mutableStateOf("Choisissez une caméra ou appuyez sur TOUT TESTER (Auto)") }
    var activeIsError by remember { mutableStateOf(false) }
    var liveFrameCount by remember { mutableIntStateOf(0) }
    var isRunningAuto by remember { mutableStateOf(false) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var activeDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var activeSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    val framesAtomic = remember { AtomicInteger(0) }
    var watchdogJob by remember { mutableStateOf<Job?>(null) }
    var autoJob by remember { mutableStateOf<Job?>(null) }

    val bgThread = remember { HandlerThread("ExpertCamDiagBg").apply { start() } }
    val bgHandler = remember { Handler(bgThread.looper) }

    fun stopCurrentTest() {
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            activeSession?.stopRepeating()
        } catch (_: Exception) {}
        try {
            activeSession?.close()
        } catch (_: Exception) {}
        activeSession = null
        try {
            activeDevice?.close()
        } catch (_: Exception) {}
        activeDevice = null
        try {
            activeSurface?.release()
        } catch (_: Exception) {}
        activeSurface = null
    }

    DisposableEffect(Unit) {
        onDispose {
            autoJob?.cancel()
            stopCurrentTest()
            bgThread.quitSafely()
            sessionExecutor.shutdown()
        }
    }

    fun recordResult(option: DiagTestOption, success: Boolean, msg: String) {
        if (!success) {
            watchdogJob?.cancel()
            watchdogJob = null
        }
        val frames = framesAtomic.get()
        results[option.key] = DiagResult(
            key = option.key,
            label = option.label,
            success = success,
            frames = frames,
            message = msg
        )
        activeIsError = !success
        activeStatusMsg = msg
    }

    @SuppressLint("MissingPermission")
    fun runSingleTest(option: DiagTestOption) {
        stopCurrentTest()
        results.remove(option.key)
        framesAtomic.set(0)
        liveFrameCount = 0
        activeTestLabel = option.label
        activeIsError = false
        activeStatusMsg = "Ouverture en cours..."

        val tv = textureViewRef
        val st = tv?.surfaceTexture
        if (tv == null || st == null || !tv.isAvailable) {
            recordResult(option, false, "TextureView non prêt")
            return
        }

        // Watchdog écran noir (3 secondes)
        watchdogJob = scope.launch {
            delay(3000L)
            val currentFrames = framesAtomic.get()
            liveFrameCount = currentFrames
            val existing = results[option.key]
            if (currentFrames == 0 && (existing == null || existing.success)) {
                recordResult(
                    option,
                    false,
                    "ÉCRAN NOIR SILENCIEUX : 0 image reçue du capteur"
                )
            } else if (currentFrames > 0) {
                recordResult(
                    option,
                    true,
                    "OK — $currentFrames images reçues en 3s"
                )
            }
        }

        try {
            val chars = cameraManager.getCameraCharacteristics(option.cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val chosenSize = sizes?.firstOrNull { it.width <= 1280 && it.height <= 960 }
                ?: sizes?.lastOrNull()
                ?: Size(1280, 720)

            cameraManager.openCamera(
                option.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        activeDevice = camera
                        try {
                            st.setDefaultBufferSize(chosenSize.width, chosenSize.height)
                            val surface = Surface(st)
                            activeSurface = surface
                            // OutputConfiguration standard uniquement — JAMAIS setPhysicalCameraId
                            val config = OutputConfiguration(surface)
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(config),
                                sessionExecutor,
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        activeSession = session
                                        try {
                                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            req.addTarget(surface)
                                            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                            req.set(
                                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                                            )
                                            req.set(
                                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                                            )
                                            if (option.zoomOverride != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                val zRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                                val z = if (zRange != null) {
                                                    option.zoomOverride.coerceIn(zRange.lower, zRange.upper)
                                                } else option.zoomOverride
                                                req.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                                            }
                                            session.setRepeatingRequest(req.build(), null, bgHandler)
                                            scope.launch {
                                                activeStatusMsg = "Flux démarré — attente d'images (watchdog 3s)..."
                                            }
                                        } catch (e: Exception) {
                                            scope.launch {
                                                recordResult(
                                                    option,
                                                    false,
                                                    "Erreur setRepeatingRequest (${e.javaClass.simpleName}): ${e.message}"
                                                )
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        scope.launch {
                                            recordResult(
                                                option,
                                                false,
                                                "onConfigureFailed : refus de configuration du flux par le pilote Samsung"
                                            )
                                        }
                                    }
                                }
                            )
                            camera.createCaptureSession(sessionConfig)
                        } catch (e: CameraAccessException) {
                            scope.launch {
                                recordResult(
                                    option,
                                    false,
                                    "CameraAccessException (#${e.reason}): ${e.message}"
                                )
                            }
                        } catch (e: Exception) {
                            scope.launch {
                                recordResult(
                                    option,
                                    false,
                                    "${e.javaClass.simpleName}: ${e.message}"
                                )
                            }
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (activeDevice == camera) activeDevice = null
                        scope.launch {
                            recordResult(option, false, "onDisconnected : caméra déconnectée par le système")
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (activeDevice == camera) activeDevice = null
                        scope.launch {
                            recordResult(
                                option,
                                false,
                                "onError ${translateCameraError(error)}"
                            )
                        }
                    }
                },
                bgHandler
            )
        } catch (e: SecurityException) {
            recordResult(option, false, "SecurityException : ${e.message}")
        } catch (e: CameraAccessException) {
            recordResult(option, false, "CameraAccessException (#${e.reason}) : ${e.message}")
        } catch (e: Exception) {
            recordResult(option, false, "${e.javaClass.simpleName} : ${e.message}")
        }
    }

    fun buildFullReport(): String = buildString {
        appendLine("=== RAPPORT DIAGNOSTIC EXPERTCAM ===")
        appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE})")
        appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("--- INVENTAIRE CAPTEURS (${cameras.size}) ---")
        cameras.forEach { cam ->
            appendLine(
                "• ID=${cam.id} | Role=${cam.role.label} | Focale=${String.format(Locale.US, "%.2f", cam.focalLengthMm)}mm " +
                    "| Public=${cam.isInCameraIdList} | Logique=${cam.isLogicalMultiCam} " +
                    "| Zoom=[${cam.minZoomRatio}..${cam.maxZoomRatio}] | Phys=${cam.physicalChildren}"
            )
        }
        appendLine()
        appendLine("--- RÉSULTATS DES TESTS (${results.size}/${testOptions.size}) ---")
        testOptions.forEach { opt ->
            val res = results[opt.key]
            if (res == null) {
                appendLine("• ${opt.label} : NON TESTÉ")
            } else {
                val tag = if (res.success) "OK" else "ÉCHEC"
                appendLine("• ${opt.label} : [$tag] (${res.frames} frames) -> ${res.message}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Barre supérieure Diagnostic
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AnthraciteSurface)
                    .clickable {
                        autoJob?.cancel()
                        stopCurrentTest()
                        onBackToCamera()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Retour Caméra", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "DIAGNOSTIC S22+",
                color = ExpertGold,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.height(8.dp))

        // Infos appareil
        Text(
            text = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            color = TextSecondary,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(8.dp))

        // Aperçu direct du capteur testé + statut en direct
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF141416))
                .border(
                    width = 1.dp,
                    color = if (activeIsError) RecordRed else ExpertGold.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(108.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(OledBlack)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            textureViewRef = tv
                            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    val count = framesAtomic.incrementAndGet()
                                    if (count == 1 || count % 10 == 0) {
                                        liveFrameCount = count
                                    }
                                }
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = activeTestLabel,
                    color = ExpertGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Images reçues : $liveFrameCount",
                    color = if (liveFrameCount > 0) DiagGreen else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = activeStatusMsg,
                    color = if (activeIsError) RecordRed else Color.White,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Bouton TOUT TESTER (Auto) — 3,5 secondes par option
        Button(
            onClick = {
                if (isRunningAuto) {
                    autoJob?.cancel()
                    stopCurrentTest()
                    isRunningAuto = false
                    activeStatusMsg = "Test automatique interrompu"
                } else {
                    autoJob?.cancel()
                    isRunningAuto = true
                    autoJob = scope.launch {
                        for (opt in testOptions) {
                            runSingleTest(opt)
                            delay(3500L)
                            // Clôture le résultat si pas encore enregistré
                            if (results[opt.key] == null) {
                                val frames = framesAtomic.get()
                                if (frames > 0) {
                                    recordResult(opt, true, "OK — $frames images reçues")
                                } else {
                                    recordResult(opt, false, "ÉCRAN NOIR SILENCIEUX : 0 image reçue du capteur")
                                }
                            }
                            stopCurrentTest()
                            delay(250L)
                        }
                        isRunningAuto = false
                        activeTestLabel = "Test automatique terminé"
                        activeIsError = false
                        activeStatusMsg = "Appuyez sur COPIER LE RAPPORT COMPLET en bas."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunningAuto) RecordRed else Color(0xFF2C2C2E),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isRunningAuto) "ARRÊTER LE TEST AUTO" else "TOUT TESTER (Auto — 3,5s / capteur)",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        // Liste scrollable : Boutons de test individuels + Inventaire + Résultats
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Tests manuels (appuyez pour tester)",
                color = ExpertGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                testOptions.forEach { opt ->
                    val res = results[opt.key]
                    val bgColor = when {
                        res == null -> Color(0xFF1C1C1E)
                        res.success -> DiagGreenBg
                        else -> DiagRedBg
                    }
                    val borderColor = when {
                        res == null -> Color.White.copy(alpha = 0.2f)
                        res.success -> DiagGreen
                        else -> RecordRed
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                            .clickable(enabled = !isRunningAuto) { runSingleTest(opt) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = opt.label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Text(
                text = "Inventaire des capteurs détectés (${cameras.size})",
                color = ExpertGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            cameras.forEach { cam ->
                Text(
                    text = cam.shortBadge() + " | zoom=[${cam.minZoomRatio}..${cam.maxZoomRatio}]",
                    color = if (cam.role == SensorRole.ULTRAWIDE_BROKEN) RecordRed else Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (results.isNotEmpty()) {
                Text(
                    text = "Journal des bugs & résultats",
                    color = ExpertGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                testOptions.forEach { opt ->
                    val res = results[opt.key] ?: return@forEach
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (res.success) DiagGreenBg else DiagRedBg)
                            .border(
                                1.dp,
                                if (res.success) DiagGreen.copy(alpha = 0.6f) else RecordRed.copy(alpha = 0.8f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "${opt.label} — ${if (res.success) "OK (${res.frames} img)" else "BUG DÉTECTÉ"}",
                            color = if (res.success) DiagGreen else RecordRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = res.message,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bouton jaune COPIER LE RAPPORT COMPLET
        Button(
            onClick = {
                val report = buildFullReport()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Rapport ExpertCam", report))
                Toast.makeText(context, "Rapport complet copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = ExpertGold,
                contentColor = OledBlack
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("COPIER LE RAPPORT COMPLET", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
    }
}
