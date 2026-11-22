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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private data class DiagTestOption(
    val key: String,
    val group: String,
    val label: String,
    val openCameraId: String,
    val physicalCameraId: String? = null,
    val operationMode: Int = SessionConfiguration.SESSION_REGULAR,
    val zoomOverride: Float? = null,
    val useSessionParameters: Boolean = false
)

private data class DiagResult(
    val key: String,
    val label: String,
    val success: Boolean,
    val frames: Int,
    val message: String
)

private class DiagHardwareHolder {
    @Volatile var device: CameraDevice? = null
    @Volatile var session: CameraCaptureSession? = null
    @Volatile var surface: Surface? = null
}

private val DiagGreen = Color(0xFF34C759)
private val DiagRedBg = Color(0xFF2D1010)
private val DiagGreenBg = Color(0xFF102A18)

@SuppressLint("WrongConstant")
private fun createDiagSessionConfiguration(
    operationMode: Int,
    outputs: List<OutputConfiguration>,
    executor: Executor,
    callback: CameraCaptureSession.StateCallback
): SessionConfiguration {
    return SessionConfiguration(operationMode, outputs, executor, callback)
}

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
    val hw = remember { DiagHardwareHolder() }

    val cameras = remember {
        scanner.scanAllCameras()
        scanner.allCameras
    }

    val testOptions = remember(cameras) {
        val widePhysId = cameras.firstOrNull { it.role == SensorRole.WIDE_1X && it.id != "0" }?.id ?: "5"
        val telePhysId = cameras.firstOrNull { it.role == SensorRole.TELE_3X && it.id != "0" }?.id ?: "6"

        buildList {
            // Méthode A : setPhysicalCameraId("5") / ("6") + taille physique native + sessionParameters
            add(
                DiagTestOption(
                    key = "meth_a_phys5",
                    group = "Méthode A — Phys natif + SessionParams",
                    label = "A: ID0 + Phys $widePhysId (1.0x)",
                    openCameraId = "0",
                    physicalCameraId = widePhysId,
                    operationMode = SessionConfiguration.SESSION_REGULAR,
                    zoomOverride = 1.0f,
                    useSessionParameters = true
                )
            )
            add(
                DiagTestOption(
                    key = "meth_a_phys6",
                    group = "Méthode A — Phys natif + SessionParams",
                    label = "A: ID0 + Phys $telePhysId (3.0x)",
                    openCameraId = "0",
                    physicalCameraId = telePhysId,
                    operationMode = SessionConfiguration.SESSION_REGULAR,
                    zoomOverride = 3.0f,
                    useSessionParameters = true
                )
            )

            // Méthode B : ID=0 sans setPhysicalCameraId + sessionParameters pré-verrouillé 3.0x / 1.0x
            add(
                DiagTestOption(
                    key = "meth_b_zoom30",
                    group = "Méthode B — ID0 SessionParams (sans PhysId)",
                    label = "B: ID0 [3.0x] SessParam",
                    openCameraId = "0",
                    physicalCameraId = null,
                    operationMode = SessionConfiguration.SESSION_REGULAR,
                    zoomOverride = 3.0f,
                    useSessionParameters = true
                )
            )
            add(
                DiagTestOption(
                    key = "meth_b_zoom10",
                    group = "Méthode B — ID0 SessionParams (sans PhysId)",
                    label = "B: ID0 [1.0x] SessParam",
                    openCameraId = "0",
                    physicalCameraId = null,
                    operationMode = SessionConfiguration.SESSION_REGULAR,
                    zoomOverride = 1.0f,
                    useSessionParameters = true
                )
            )

            // Méthode C : Modes d'opération Samsung vendor sur ID=0 + setPhysicalCameraId("5")
            for (opMode in Camera2Controller.SAMSUNG_VENDOR_OP_MODES) {
                val hex = "0x${opMode.toString(16).uppercase(Locale.US)}"
                add(
                    DiagTestOption(
                        key = "meth_c_${hex}_p$widePhysId",
                        group = "Méthode C — Modes Samsung Vendor (ID0 + Phys $widePhysId)",
                        label = "C: $hex (Phys $widePhysId)",
                        openCameraId = "0",
                        physicalCameraId = widePhysId,
                        operationMode = opMode,
                        zoomOverride = 1.0f,
                        useSessionParameters = true
                    )
                )
            }
            add(
                DiagTestOption(
                    key = "meth_c_0x8008_p$telePhysId",
                    group = "Méthode C — Modes Samsung Vendor (ID0 + Phys $widePhysId)",
                    label = "C: 0x8008 (Phys $telePhysId)",
                    openCameraId = "0",
                    physicalCameraId = telePhysId,
                    operationMode = 0x8008,
                    zoomOverride = 3.0f,
                    useSessionParameters = true
                )
            )

            // Tests directs individuels de chaque capteur détecté
            cameras.forEach { cam ->
                val hiddenTag = if (cam.isHidden) " Caché" else ""
                add(
                    DiagTestOption(
                        key = "direct_${cam.id}",
                        group = "Tests directs par ID",
                        label = "ID ${cam.id} (${cam.role.label}$hiddenTag)",
                        openCameraId = cam.id,
                        physicalCameraId = null,
                        operationMode = SessionConfiguration.SESSION_REGULAR,
                        zoomOverride = null,
                        useSessionParameters = false
                    )
                )
            }
        }
    }

    val results = remember { mutableStateMapOf<String, DiagResult>() }
    var activeTestLabel by remember { mutableStateOf("Aucun test actif") }
    var activeStatusMsg by remember { mutableStateOf("Appuyez sur une méthode (A, B, C) ou sur TOUT TESTER (Auto)") }
    var activeIsError by remember { mutableStateOf(false) }
    var liveFrameCount by remember { mutableIntStateOf(0) }
    var isRunningAuto by remember { mutableStateOf(false) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val framesAtomic = remember { AtomicInteger(0) }
    val testGeneration = remember { AtomicInteger(0) }
    var watchdogJob by remember { mutableStateOf<Job?>(null) }
    var autoJob by remember { mutableStateOf<Job?>(null) }

    val bgThread = remember { HandlerThread("ExpertCamDiagCamXBg").apply { start() } }
    val bgHandler = remember { Handler(bgThread.looper) }

    fun stopCurrentTest() {
        testGeneration.incrementAndGet()
        watchdogJob?.cancel()
        watchdogJob = null
        try {
            hw.session?.stopRepeating()
        } catch (_: Exception) {}
        try {
            hw.session?.close()
        } catch (_: Exception) {}
        hw.session = null
        try {
            hw.device?.close()
        } catch (_: Exception) {}
        hw.device = null
        try {
            hw.surface?.release()
        } catch (_: Exception) {}
        hw.surface = null
    }

    DisposableEffect(Unit) {
        onDispose {
            autoJob?.cancel()
            stopCurrentTest()
            bgThread.quitSafely()
            sessionExecutor.shutdown()
        }
    }

    fun recordResult(option: DiagTestOption, gen: Int, success: Boolean, msg: String) {
        if (gen != testGeneration.get()) return
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
        val gen = testGeneration.get()
        results.remove(option.key)
        framesAtomic.set(0)
        liveFrameCount = 0
        activeTestLabel = option.label
        activeIsError = false
        activeStatusMsg = "Ouverture en cours..."

        val tv = textureViewRef
        val st = tv?.surfaceTexture
        if (tv == null || st == null || !tv.isAvailable) {
            recordResult(option, gen, false, "TextureView non prêt")
            return
        }

        // Watchdog écran noir (3 secondes)
        watchdogJob = scope.launch {
            delay(3000L)
            if (gen != testGeneration.get()) return@launch
            val currentFrames = framesAtomic.get()
            liveFrameCount = currentFrames
            val existing = results[option.key]
            if (currentFrames == 0 && (existing == null || existing.success)) {
                recordResult(
                    option,
                    gen,
                    false,
                    "ÉCRAN NOIR SILENCIEUX : 0 image reçue du capteur"
                )
            } else if (currentFrames > 0) {
                recordResult(
                    option,
                    gen,
                    true,
                    "OK — $currentFrames images reçues en 3s"
                )
            }
        }

        try {
            val logicalChars = cameraManager.getCameraCharacteristics(option.openCameraId)
            val chosenSize = if (option.physicalCameraId != null) {
                pickExactPhysicalPreviewSize(cameraManager, option.physicalCameraId, logicalChars)
            } else {
                pickExactPhysicalPreviewSize(cameraManager, option.openCameraId, logicalChars)
            }
            val opHex = "0x${option.operationMode.toString(16).uppercase(Locale.US)}"
            val metaTag = "[open=${option.openCameraId}, phys=${option.physicalCameraId ?: "none"}, res=${chosenSize.width}x${chosenSize.height}, op=$opHex, zoom=${option.zoomOverride ?: "def"}]"

            cameraManager.openCamera(
                option.openCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != testGeneration.get()) {
                            camera.close()
                            return
                        }
                        hw.device = camera
                        try {
                            st.setDefaultBufferSize(chosenSize.width, chosenSize.height)
                            val surface = Surface(st)
                            hw.surface = surface

                            val outConfig = OutputConfiguration(surface).apply {
                                if (option.physicalCameraId != null) {
                                    setPhysicalCameraId(option.physicalCameraId)
                                }
                            }

                            val sessionConfig = createDiagSessionConfiguration(
                                operationMode = option.operationMode,
                                outputs = listOf(outConfig),
                                executor = sessionExecutor,
                                callback = object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        if (gen != testGeneration.get()) {
                                            session.close()
                                            return
                                        }
                                        hw.session = session
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
                                                val zRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                                val z = if (zRange != null) {
                                                    option.zoomOverride.coerceIn(zRange.lower, zRange.upper)
                                                } else option.zoomOverride
                                                req.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                                            }
                                            session.setRepeatingRequest(req.build(), null, bgHandler)
                                            scope.launch {
                                                if (gen == testGeneration.get()) {
                                                    activeStatusMsg = "Session OK $metaTag — attente images (3s)..."
                                                }
                                            }
                                        } catch (e: Exception) {
                                            scope.launch {
                                                recordResult(
                                                    option,
                                                    gen,
                                                    false,
                                                    "setRepeatingRequest $metaTag -> ${e.javaClass.simpleName}: ${e.message}"
                                                )
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        scope.launch {
                                            recordResult(
                                                option,
                                                gen,
                                                false,
                                                "onConfigureFailed $metaTag : refus du pilote Samsung/CamX"
                                            )
                                        }
                                    }
                                }
                            )

                            if (option.useSessionParameters) {
                                val initBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                                    )
                                    set(
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                                    )
                                    if (option.zoomOverride != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val zRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                        val z = if (zRange != null) {
                                            option.zoomOverride.coerceIn(zRange.lower, zRange.upper)
                                        } else option.zoomOverride
                                        set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
                                    }
                                }
                                sessionConfig.setSessionParameters(initBuilder.build())
                            }

                            camera.createCaptureSession(sessionConfig)
                        } catch (e: CameraAccessException) {
                            scope.launch {
                                recordResult(
                                    option,
                                    gen,
                                    false,
                                    "CameraAccessException (#${e.reason}) $metaTag : ${e.message}"
                                )
                            }
                        } catch (e: Exception) {
                            scope.launch {
                                recordResult(
                                    option,
                                    gen,
                                    false,
                                    "${e.javaClass.simpleName} $metaTag : ${e.message}"
                                )
                            }
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (hw.device == camera) hw.device = null
                        scope.launch {
                            recordResult(option, gen, false, "onDisconnected $metaTag : déconnectée par Android")
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (hw.device == camera) hw.device = null
                        scope.launch {
                            recordResult(
                                option,
                                gen,
                                false,
                                "onError ${translateCameraError(error)} $metaTag"
                            )
                        }
                    }
                },
                bgHandler
            )
        } catch (e: SecurityException) {
            recordResult(option, gen, false, "SecurityException : ${e.message}")
        } catch (e: CameraAccessException) {
            recordResult(option, gen, false, "CameraAccessException (#${e.reason}) : ${e.message}")
        } catch (e: Exception) {
            recordResult(option, gen, false, "${e.javaClass.simpleName} : ${e.message}")
        }
    }

    fun buildFullReport(): String = buildString {
        appendLine("=== RAPPORT DIAGNOSTIC EXPERTCAM (CAMX / SAMSUNG) ===")
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
        // Barre supérieure
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
                text = "DIAGNOSTIC CAMX S22+",
                color = ExpertGold,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            color = TextSecondary,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(8.dp))

        // Aperçu direct + statut du test courant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(145.dp)
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
                    .width(104.dp)
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
                    fontSize = 12.sp,
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
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bouton TOUT TESTER (Auto)
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
                            val activeGen = testGeneration.get()
                            delay(3500L)
                            if (results[opt.key] == null) {
                                val frames = framesAtomic.get()
                                if (frames > 0) {
                                    recordResult(opt, activeGen, true, "OK — $frames images reçues")
                                } else {
                                    recordResult(opt, activeGen, false, "ÉCRAN NOIR SILENCIEUX : 0 image reçue du capteur")
                                }
                            }
                            stopCurrentTest()
                            delay(250L)
                        }
                        isRunningAuto = false
                        activeTestLabel = "Tous les tests A / B / C terminés"
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
                text = if (isRunningAuto) "ARRÊTER LE TEST AUTO" else "TOUT TESTER (Auto — Méthodes A, B, C + IDs)",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(8.dp))

        // Groupes de tests + Inventaire + Journal des résultats
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val grouped = remember(testOptions) { testOptions.groupBy { it.group } }
            grouped.forEach { (groupTitle, opts) ->
                Text(
                    text = groupTitle,
                    color = ExpertGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    opts.forEach { opt ->
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
            }

            Text(
                text = "Inventaire des capteurs détectés (${cameras.size})",
                color = ExpertGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            cameras.forEach { cam ->
                Text(
                    text = cam.shortBadge() + " | zoom=[${cam.minZoomRatio}..${cam.maxZoomRatio}] | phys=${cam.physicalChildren}",
                    color = if (cam.role == SensorRole.ULTRAWIDE_BROKEN) RecordRed else Color.White,
                    fontSize = 10.sp,
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
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
