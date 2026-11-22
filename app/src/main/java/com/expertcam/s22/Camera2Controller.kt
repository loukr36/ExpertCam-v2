package com.expertcam.s22

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, VIDEO }
enum class FlashOption(val label: String) { OFF("Off"), AUTO("Auto"), ON("On") }

enum class VideoResolution(val label: String, val size: Size, val bitRate: Int) {
    FHD_1080P("1080p", Size(1920, 1080), 20_000_000),
    UHD_4K("4K", Size(3840, 2160), 48_000_000)
}

data class CapturedMedia(
    val uri: Uri,
    val mimeType: String,
    val isVideo: Boolean
)

data class CameraUiState(
    val activeLens: SafeLensType = SafeLensType.WIDE,
    val activeCameraId: String = "",
    val hasTeleLens: Boolean = false,
    val hasFrontLens: Boolean = false,
    val mode: CameraMode = CameraMode.PHOTO,
    val videoResolution: VideoResolution = VideoResolution.FHD_1080P,
    val flash: FlashOption = FlashOption.OFF,
    val zoomDisplay: Float = 1.0f,
    val isRecording: Boolean = false,
    val recordingSeconds: Long = 0L,
    val lastCapturedMedia: CapturedMedia? = null,
    val errorBanner: String? = null
)

class VideoRecorderHelper(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorderHelper"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    var currentOutputUri: Uri? = null
        private set
    private var isExternalUri: Boolean = false
    var isRecording: Boolean = false
        private set

    fun prepare(
        resolution: VideoResolution,
        orientationHint: Int,
        includeAudio: Boolean,
        externalOutputUri: Uri? = null
    ): Surface {
        release()

        val targetUri: Uri = if (externalOutputUri != null) {
            isExternalUri = true
            externalOutputUri
        } else {
            isExternalUri = false
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "VID_EXPERT_$timestamp.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/ExpertCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Impossible de créer le fichier vidéo MediaStore")
        }

        currentOutputUri = targetUri
        val pfd = context.contentResolver.openFileDescriptor(targetUri, "rw")
            ?: throw IllegalStateException("Descripteur vidéo inaccessible")
        outputPfd = pfd

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            if (includeAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.setVideoEncodingBitRate(resolution.bitRate)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(resolution.size.width, resolution.size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (includeAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(48000)
                recorder.setAudioEncodingBitRate(192000)
            }
            recorder.setOrientationHint(orientationHint)
            recorder.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur préparation MediaRecorder", e)
            recorder.release()
            cleanupPending()
            throw e
        }

        mediaRecorder = recorder
        return recorder.surface
    }

    fun start() {
        mediaRecorder?.start() ?: throw IllegalStateException("MediaRecorder non initialisé")
        isRecording = true
    }

    fun stop(): Uri? {
        val uri = currentOutputUri
        var ok = false
        try {
            if (isRecording) {
                mediaRecorder?.stop()
                ok = true
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vidéo trop courte ou erreur stop(): ${e.message}")
            ok = false
        } finally {
            isRecording = false
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
            try {
                outputPfd?.close()
            } catch (_: Exception) {}
            outputPfd = null
        }

        if (uri != null && !isExternalUri) {
            if (ok) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                try {
                    context.contentResolver.update(uri, values, null, null)
                } catch (_: Exception) {}
            } else {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {}
                currentOutputUri = null
                return null
            }
        }
        return if (ok) uri else null
    }

    fun release() {
        if (isRecording) {
            stop()
            return
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        try {
            outputPfd?.close()
        } catch (_: Exception) {}
        outputPfd = null
    }

    private fun cleanupPending() {
        try {
            outputPfd?.close()
        } catch (_: Exception) {}
        outputPfd = null
        if (!isExternalUri) {
            currentOutputUri?.let {
                try {
                    context.contentResolver.delete(it, null, null)
                } catch (_: Exception) {}
            }
        }
        currentOutputUri = null
    }
}

/**
 * Sélectionne une résolution native exacte dans SCALER_STREAM_CONFIGURATION_MAP du capteur physique
 * (ex: 1920x1080 ou 1440x1080) afin d'éviter l'erreur CamX -38 liée à une taille d'écran non native.
 */
fun pickExactPhysicalPreviewSize(
    cameraManager: CameraManager,
    targetId: String,
    fallbackChars: CameraCharacteristics
): Size {
    val chars = try {
        cameraManager.getCameraCharacteristics(targetId)
    } catch (_: Exception) {
        fallbackChars
    }
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: fallbackChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)

    val preferredOrder = listOf(
        Size(1920, 1080),
        Size(1440, 1080),
        Size(1280, 960),
        Size(1280, 720)
    )
    for (pref in preferredOrder) {
        if (sizes.any { it.width == pref.width && it.height == pref.height }) {
            return pref
        }
    }
    return sizes
        .filter { it.width <= 1920 && it.height <= 1440 }
        .maxByOrNull { it.width.toLong() * it.height.toLong() }
        ?: sizes.minByOrNull { abs((it.width.toFloat() / it.height.toFloat()) - (4f / 3f)) }
        ?: Size(1920, 1080)
}

/**
 * Contrôleur Camera2 avancé Qualcomm CamX / Samsung pour Galaxy S22+ (Android 16) :
 * - Méthode A : ID=0 + setPhysicalCameraId("5"/"6") + taille physique native + sessionParameters.
 * - Méthode B : ID=0 sans setPhysicalCameraId + sessionParameters pré-verrouillé sur 1.0f / 3.0f.
 * - Méthode C : ID=0 + setPhysicalCameraId("5"/"6") + modes d'opération Samsung (0x8008, 0x8001, 0x800C, 0x8004).
 * - Bascule automatique garantie sur la caméra frontale (ID=1) si Android 16 refuse toutes les méthodes sur ID=0.
 */
class Camera2Controller(
    private val context: Context,
    val scanner: CameraScanner,
    private val externalOutputUri: Uri? = null,
    private val onMediaCaptured: (CapturedMedia) -> Unit
) {
    companion object {
        private const val TAG = "Camera2Controller"
        val SAMSUNG_VENDOR_OP_MODES = listOf(0x8008, 0x8001, 0x800C, 0x8004)
    }

    private data class RearOpenAttempt(
        val label: String,
        val openCameraId: String,
        val physicalCameraId: String?,
        val operationMode: Int,
        val initialZoomRatio: Float,
        val useSessionParameters: Boolean,
        val lensType: SafeLensType
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val videoRecorder = VideoRecorderHelper(context)
    private val sessionExecutor = Executors.newSingleThreadExecutor()
    private val openCloseLock = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var textureView: TextureView? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewSurfaceRef: Surface? = null
    private var jpegImageReader: ImageReader? = null

    private var currentAttempt: RearOpenAttempt? = null
    private var currentLogicalChars: CameraCharacteristics? = null
    private var currentPhysicalChars: CameraCharacteristics? = null
    private var previewSize: Size = Size(1920, 1080)
    private var sensorOrientation: Int = 90

    private var attemptQueue: List<RearOpenAttempt> = emptyList()
    private var attemptIndex: Int = 0
    private val winningAttemptByLens = mutableMapOf<SafeLensType, RearOpenAttempt>()
    private val attemptGeneration = AtomicInteger(0)
    private val attemptReceivedFrame = AtomicBoolean(false)
    private var watchdogRunnable: Runnable? = null

    private var isPausedOrClosed: Boolean = false
    private var useSingleSurfaceMode: Boolean = false

    private val _uiState = MutableStateFlow(
        CameraUiState(
            hasTeleLens = scanner.hasTeleAvailable(),
            hasFrontLens = scanner.hasFrontAvailable()
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("ExpertCam2CamXBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        cancelWatchdog()
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
        } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    fun attachTextureView(tv: TextureView) {
        textureView = tv
        isPausedOrClosed = false
        if (tv.isAvailable) {
            openLensCascade(_uiState.value.activeLens)
        } else {
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                    if (!isPausedOrClosed) {
                        openLensCascade(_uiState.value.activeLens)
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    fun openLensCascade(requestedLens: SafeLensType) {
        isPausedOrClosed = false
        if (textureView?.isAvailable != true) return
        startBackgroundThread()

        val queue = buildAttemptQueue(requestedLens)
        if (queue.isEmpty()) {
            if (requestedLens != SafeLensType.FRONT && scanner.hasFrontAvailable()) {
                fallbackToFrontCamera("Aucun capteur arrière sain — bascule sur Selfie (ID 1)")
            } else {
                postError("Aucun capteur caméra disponible")
            }
            return
        }

        attemptQueue = queue
        attemptIndex = 0
        _uiState.update {
            it.copy(
                activeLens = requestedLens,
                zoomDisplay = when (requestedLens) {
                    SafeLensType.TELE -> 3.0f
                    else -> 1.0f
                },
                errorBanner = null
            )
        }
        tryExecuteCurrentAttempt()
    }

    private fun buildAttemptQueue(lens: SafeLensType): List<RearOpenAttempt> {
        if (lens == SafeLensType.FRONT) {
            val frontId = scanner.getCandidatesFor(SafeLensType.FRONT).firstOrNull()?.cameraId ?: "1"
            return listOf(
                RearOpenAttempt(
                    label = "Front ID $frontId",
                    openCameraId = frontId,
                    physicalCameraId = null,
                    operationMode = SessionConfiguration.SESSION_REGULAR,
                    initialZoomRatio = 1.0f,
                    useSessionParameters = false,
                    lensType = SafeLensType.FRONT
                )
            )
        }

        val logicalBackId = scanner.allCameras
            .firstOrNull { it.id == "0" && it.facing == CameraCharacteristics.LENS_FACING_BACK }?.id
            ?: scanner.allCameras
                .firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.isInCameraIdList && it.id !in scanner.brokenUwIds }?.id
            ?: "0"

        val widePhysId = scanner.allCameras
            .firstOrNull { it.role == SensorRole.WIDE_1X && it.id != logicalBackId && it.id !in scanner.brokenUwIds }?.id
            ?: "5"

        val telePhysId = scanner.allCameras
            .firstOrNull { it.role == SensorRole.TELE_3X && it.id != logicalBackId && it.id !in scanner.brokenUwIds }?.id
            ?: "6"

        val targetPhysId = if (lens == SafeLensType.TELE) telePhysId else widePhysId
        val targetZoom = if (lens == SafeLensType.TELE) 3.0f else 1.0f

        val list = mutableListOf<RearOpenAttempt>()

        // Priorité immédiate à la méthode qui a déjà réussi pour cet objectif
        winningAttemptByLens[lens]?.let { list.add(it) }

        // Méthode A : ID=0 + setPhysicalCameraId("5"/"6") + taille physique native + sessionParameters
        list.add(
            RearOpenAttempt(
                label = "Méthode A (ID $logicalBackId + phys $targetPhysId)",
                openCameraId = logicalBackId,
                physicalCameraId = targetPhysId,
                operationMode = SessionConfiguration.SESSION_REGULAR,
                initialZoomRatio = targetZoom,
                useSessionParameters = true,
                lensType = lens
            )
        )

        // Méthode B : ID=0 sans setPhysicalCameraId + sessionParameters pré-verrouillé sur 1.0f ou 3.0f
        list.add(
            RearOpenAttempt(
                label = "Méthode B (ID $logicalBackId zoom=${targetZoom}x)",
                openCameraId = logicalBackId,
                physicalCameraId = null,
                operationMode = SessionConfiguration.SESSION_REGULAR,
                initialZoomRatio = targetZoom,
                useSessionParameters = true,
                lensType = lens
            )
        )

        // Méthode C : ID=0 + setPhysicalCameraId("5"/"6") + modes d'opération Samsung vendor
        for (opMode in SAMSUNG_VENDOR_OP_MODES) {
            val hex = "0x${opMode.toString(16).uppercase(Locale.US)}"
            list.add(
                RearOpenAttempt(
                    label = "Méthode C ($hex phys=$targetPhysId)",
                    openCameraId = logicalBackId,
                    physicalCameraId = targetPhysId,
                    operationMode = opMode,
                    initialZoomRatio = targetZoom,
                    useSessionParameters = true,
                    lensType = lens
                )
            )
        }

        return list.distinctBy {
            "${it.openCameraId}_${it.physicalCameraId}_${it.operationMode}_${it.initialZoomRatio}_${it.useSessionParameters}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryExecuteCurrentAttempt() {
        if (isPausedOrClosed || textureView?.isAvailable != true) return
        val attempt = attemptQueue.getOrNull(attemptIndex) ?: run {
            if (_uiState.value.activeLens != SafeLensType.FRONT && scanner.hasFrontAvailable()) {
                fallbackToFrontCamera(
                    "Capteurs arrière bloqués sur ID 0 (Android 16) — bascule auto sur Selfie (ID 1)"
                )
            } else {
                postError("Aucune méthode caméra n'a réussi")
            }
            return
        }

        // Ne jamais ouvrir ni cibler le capteur 0.6x cassé (ID 2) en mode principal
        if (attempt.openCameraId in scanner.brokenUwIds ||
            (attempt.physicalCameraId != null && attempt.physicalCameraId in scanner.brokenUwIds)
        ) {
            advanceAttempt("Capteur 0.6x banni ignoré", attemptGeneration.get())
            return
        }

        val logicalChars = scanner.getCharacteristics(attempt.openCameraId) ?: run {
            advanceAttempt("Caractéristiques introuvables pour ID ${attempt.openCameraId}", attemptGeneration.get())
            return
        }
        val physChars = if (attempt.physicalCameraId != null) {
            scanner.getCharacteristics(attempt.physicalCameraId) ?: logicalChars
        } else {
            logicalChars
        }

        val gen = attemptGeneration.incrementAndGet()
        attemptReceivedFrame.set(false)
        cancelWatchdog()
        closeCameraInternal()

        currentAttempt = attempt
        currentLogicalChars = logicalChars
        currentPhysicalChars = physChars
        sensorOrientation = physChars.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: logicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: 90

        // En mode arrière (ID=0), surface unique prioritaire pour ne pas saturer CamX ;
        // en mode Selfie (ID=1), ImageReader JPEG est attaché directement.
        useSingleSurfaceMode = attempt.lensType != SafeLensType.FRONT
        prepareStreamsForAttempt(attempt, logicalChars, physChars)

        _uiState.update {
            it.copy(
                activeCameraId = if (attempt.physicalCameraId != null) {
                    "${attempt.openCameraId}->${attempt.physicalCameraId}"
                } else {
                    attempt.openCameraId
                }
            )
        }

        // Watchdog 2.5s sur les tentatives arrière pour ne jamais rester bloqué sur écran noir
        if (attempt.lensType != SafeLensType.FRONT) {
            scheduleAttemptWatchdog(gen, attempt)
        }

        try {
            if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Timeout verrou Camera2")
            }
            cameraManager.openCamera(
                attempt.openCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openCloseLock.release()
                        if (isPausedOrClosed || gen != attemptGeneration.get()) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSessionForAttempt(attempt, videoSurface = null, gen = gen)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        openCloseLock.release()
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                        advanceAttempt("${attempt.label} -> onDisconnected", gen)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        openCloseLock.release()
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                        advanceAttempt("${attempt.label} -> onError ${translateCameraError(error)}", gen)
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            openCloseLock.release()
            advanceAttempt("${attempt.label} -> CameraAccessException #${e.reason}: ${e.message}", gen)
        } catch (e: SecurityException) {
            openCloseLock.release()
            advanceAttempt("${attempt.label} -> SecurityException", gen)
        } catch (e: Exception) {
            openCloseLock.release()
            advanceAttempt("${attempt.label} -> ${e.javaClass.simpleName}: ${e.message}", gen)
        }
    }

    private fun scheduleAttemptWatchdog(gen: Int, attempt: RearOpenAttempt) {
        cancelWatchdog()
        val r = Runnable {
            if (gen == attemptGeneration.get() && !attemptReceivedFrame.get() && !isPausedOrClosed) {
                advanceAttempt("${attempt.label} -> Timeout 2.5s (0 image)", gen)
            }
        }
        watchdogRunnable = r
        backgroundHandler?.postDelayed(r, 2500L)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun advanceAttempt(reason: String, gen: Int) {
        if (gen != attemptGeneration.get() || isPausedOrClosed) return
        cancelWatchdog()
        Log.w(TAG, "Cascade CamX échec #$attemptIndex : $reason")

        val next = attemptIndex + 1
        if (next < attemptQueue.size) {
            attemptIndex = next
            backgroundHandler?.postDelayed({ tryExecuteCurrentAttempt() }, 200L)
        } else {
            // Toutes les méthodes arrière ont échoué sur ID=0 -> bascule automatique sur Selfie (ID=1)
            if (_uiState.value.activeLens != SafeLensType.FRONT && scanner.hasFrontAvailable()) {
                fallbackToFrontCamera(
                    "Arrière bloqué ($reason) — bascule auto sur Selfie (ID 1)"
                )
            } else {
                postError(reason)
            }
        }
    }

    private fun fallbackToFrontCamera(bannerMessage: String) {
        Log.i(TAG, "Bascule automatique vers caméra frontale : $bannerMessage")
        val frontQueue = buildAttemptQueue(SafeLensType.FRONT)
        attemptQueue = frontQueue
        attemptIndex = 0
        _uiState.update {
            it.copy(
                activeLens = SafeLensType.FRONT,
                zoomDisplay = 1.0f,
                errorBanner = bannerMessage
            )
        }
        backgroundHandler?.postDelayed({ tryExecuteCurrentAttempt() }, 200L)
    }

    private fun prepareStreamsForAttempt(
        attempt: RearOpenAttempt,
        logicalChars: CameraCharacteristics,
        physChars: CameraCharacteristics
    ) {
        // Méthode A / C : taille exacte du capteur physique dans son SCALER_STREAM_CONFIGURATION_MAP
        previewSize = if (attempt.physicalCameraId != null) {
            pickExactPhysicalPreviewSize(cameraManager, attempt.physicalCameraId, logicalChars)
        } else {
            pickExactPhysicalPreviewSize(cameraManager, attempt.openCameraId, logicalChars)
        }

        jpegImageReader?.close()
        jpegImageReader = null

        if (!useSingleSurfaceMode) {
            val map = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
            val bestJpeg = chooseBestPhotoSize(jpegSizes, previewSize)
            jpegImageReader = ImageReader.newInstance(bestJpeg.width, bestJpeg.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    saveJpegImage(img)
                }, backgroundHandler)
            }
        }

        textureView?.let { tv ->
            tv.post { configureTransform(tv.width, tv.height) }
        }
    }

    @SuppressLint("WrongConstant")
    private fun buildSessionConfiguration(
        operationMode: Int,
        configs: List<OutputConfiguration>,
        callback: CameraCaptureSession.StateCallback
    ): SessionConfiguration {
        return SessionConfiguration(operationMode, configs, sessionExecutor, callback)
    }

    private fun createSessionForAttempt(
        attempt: RearOpenAttempt,
        videoSurface: Surface?,
        gen: Int
    ) {
        val device = cameraDevice ?: return
        val logicalChars = currentLogicalChars ?: return
        val tv = textureView ?: return
        val st = tv.surfaceTexture ?: return

        try {
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {}
            try {
                captureSession?.close()
            } catch (_: Exception) {}
            captureSession = null

            // Taille exacte native du capteur physique
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurfaceRef?.release()
            val previewSurface = Surface(st)
            previewSurfaceRef = previewSurface

            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurface)
            if (videoSurface != null) {
                surfaces.add(videoSurface)
            } else if (!useSingleSurfaceMode) {
                jpegImageReader?.surface?.let { surfaces.add(it) }
            }

            val configs = surfaces.map { s ->
                OutputConfiguration(s).apply {
                    if (attempt.physicalCameraId != null) {
                        setPhysicalCameraId(attempt.physicalCameraId)
                    }
                }
            }

            val template = if (videoSurface != null) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }

            val builder = device.createCaptureRequest(template).apply {
                addTarget(previewSurface)
                if (videoSurface != null) {
                    addTarget(videoSurface)
                }
            }
            previewBuilder = builder

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null || isPausedOrClosed || gen != attemptGeneration.get()) {
                        session.close()
                        return
                    }
                    captureSession = session
                    applyControls(builder, attempt)
                    try {
                        session.setRepeatingRequest(
                            builder.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    s: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    if (attemptReceivedFrame.compareAndSet(false, true)) {
                                        cancelWatchdog()
                                        if (attempt.lensType != SafeLensType.FRONT) {
                                            winningAttemptByLens[attempt.lensType] = attempt
                                            Log.i(TAG, "Méthode arrière validée : ${attempt.label}")
                                        }
                                    }
                                }
                            },
                            backgroundHandler
                        )
                        if (videoSurface != null) {
                            videoRecorder.start()
                            _uiState.update { it.copy(isRecording = true, recordingSeconds = 0L) }
                        }
                    } catch (e: Exception) {
                        if (videoSurface != null) {
                            handleVideoStartFailure("Erreur flux vidéo: ${e.message}")
                        } else {
                            advanceAttempt("${attempt.label} -> setRepeatingRequest: ${e.message}", gen)
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (videoSurface != null) {
                        handleVideoStartFailure("Configuration vidéo refusée (${attempt.label})")
                    } else {
                        advanceAttempt("${attempt.label} -> onConfigureFailed", gen)
                    }
                }
            }

            val sessionConfig = buildSessionConfiguration(
                operationMode = attempt.operationMode,
                configs = configs,
                callback = callback
            )

            // Méthodes A, B, C : pré-configure sessionParameters AVANT createCaptureSession
            if (attempt.useSessionParameters) {
                val initReqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    )
                    applySafeZoom(this, logicalChars, attempt.initialZoomRatio)
                }
                sessionConfig.setSessionParameters(initReqBuilder.build())
            }

            device.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            if (videoSurface != null) {
                handleVideoStartFailure("CameraAccessException vidéo: ${e.message}")
            } else {
                advanceAttempt("${attempt.label} -> CameraAccessException #${e.reason}: ${e.message}", gen)
            }
        } catch (e: Exception) {
            if (videoSurface != null) {
                handleVideoStartFailure("Exception session vidéo: ${e.message}")
            } else {
                advanceAttempt("${attempt.label} -> ${e.javaClass.simpleName}: ${e.message}", gen)
            }
        }
    }

    private fun handleVideoStartFailure(reason: String) {
        videoRecorder.release()
        val was4k = _uiState.value.videoResolution == VideoResolution.UHD_4K
        _uiState.update {
            it.copy(
                isRecording = false,
                recordingSeconds = 0L,
                videoResolution = VideoResolution.FHD_1080P,
                errorBanner = if (was4k) "4K non supporté — passage automatique en 1080p" else reason
            )
        }
        val attempt = currentAttempt ?: return
        createSessionForAttempt(attempt, videoSurface = null, gen = attemptGeneration.get())
    }

    private fun applyControls(builder: CaptureRequest.Builder, attempt: RearOpenAttempt) {
        val state = _uiState.value
        val logicalChars = currentLogicalChars ?: return

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        val aeMode = when (state.flash) {
            FlashOption.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashOption.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            FlashOption.OFF -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)

        val afMode = if (state.mode == CameraMode.VIDEO) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)

        if (attempt.lensType == SafeLensType.FRONT && state.mode == CameraMode.VIDEO) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        } else {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        val safeZoom = when (attempt.lensType) {
            SafeLensType.WIDE -> state.zoomDisplay.coerceIn(1.0f, 2.9f)
            SafeLensType.TELE -> state.zoomDisplay.coerceIn(3.0f, 10.0f)
            SafeLensType.FRONT -> state.zoomDisplay.coerceIn(1.0f, 2.0f)
        }.coerceAtLeast(1.0f)

        applySafeZoom(builder, logicalChars, safeZoom)
    }

    private fun applySafeZoom(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        zoomRatio: Float
    ) {
        val safeZoom = zoomRatio.coerceAtLeast(1.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val minZ = max(1.0f, range?.lower ?: 1.0f)
            val maxZ = max(minZ, range?.upper ?: 4.0f)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeZoom.coerceIn(minZ, maxZ))
        } else {
            val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val maxDig = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f)
                .coerceAtLeast(1.0f)
            val factor = safeZoom.coerceIn(1.0f, maxDig)
            val cropW = (activeRect.width() / factor).roundToInt().coerceAtLeast(1)
            val cropH = (activeRect.height() / factor).roundToInt().coerceAtLeast(1)
            val left = (activeRect.width() - cropW) / 2
            val top = (activeRect.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    private fun refreshRepeating() {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val attempt = currentAttempt ?: return
        backgroundHandler?.post {
            try {
                applyControls(builder, attempt)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "Erreur refreshRepeating: ${e.message}")
            }
        }
    }

    // --- Actions utilisateur ---

    fun switchLens(lens: SafeLensType) {
        if (videoRecorder.isRecording) return
        if (lens == _uiState.value.activeLens && cameraDevice != null) return
        openLensCascade(lens)
    }

    fun toggleFrontBack() {
        if (videoRecorder.isRecording) return
        val next = if (_uiState.value.activeLens == SafeLensType.FRONT) {
            SafeLensType.WIDE
        } else {
            SafeLensType.FRONT
        }
        openLensCascade(next)
    }

    fun setMode(mode: CameraMode) {
        if (videoRecorder.isRecording) return
        if (_uiState.value.mode == mode) return
        _uiState.update { it.copy(mode = mode) }
        refreshRepeating()
    }

    fun toggleVideoResolution() {
        if (videoRecorder.isRecording) return
        val next = if (_uiState.value.videoResolution == VideoResolution.FHD_1080P) {
            VideoResolution.UHD_4K
        } else {
            VideoResolution.FHD_1080P
        }
        _uiState.update { it.copy(videoResolution = next) }
    }

    fun cycleFlash() {
        val entries = FlashOption.entries
        val next = entries[(entries.indexOf(_uiState.value.flash) + 1) % entries.size]
        _uiState.update { it.copy(flash = next) }
        refreshRepeating()
    }

    fun applyZoomDelta(delta: Float) {
        val state = _uiState.value
        val newZoom = when (state.activeLens) {
            SafeLensType.WIDE -> (state.zoomDisplay * delta).coerceIn(1.0f, 2.9f)
            SafeLensType.TELE -> (state.zoomDisplay * delta).coerceIn(3.0f, 10.0f)
            SafeLensType.FRONT -> (state.zoomDisplay * delta).coerceIn(1.0f, 2.0f)
        }.coerceAtLeast(1.0f)

        _uiState.update { it.copy(zoomDisplay = newZoom) }
        refreshRepeating()
    }

    fun tapToFocus(normX: Float, normY: Float) {
        val session = captureSession ?: return
        val builder = previewBuilder ?: return
        val chars = currentPhysicalChars ?: currentLogicalChars ?: return
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        backgroundHandler?.post {
            try {
                val sensorX = (normY.coerceIn(0.05f, 0.95f) * activeArray.width()).roundToInt()
                val sensorY = ((1f - normX.coerceIn(0.05f, 0.95f)) * activeArray.height()).roundToInt()
                val half = 150
                val rect = Rect(
                    (sensorX - half).coerceAtLeast(0),
                    (sensorY - half).coerceAtLeast(0),
                    (sensorX + half).coerceAtMost(activeArray.width()),
                    (sensorY + half).coerceAtMost(activeArray.height())
                )
                val metering = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))

                builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, backgroundHandler)

                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "Erreur tapToFocus: ${e.message}")
            }
        }
    }

    // --- Capture Photo garantie sans crash ---

    fun capturePhoto() {
        val readerSurface = jpegImageReader?.surface
        val device = cameraDevice
        val session = captureSession
        val attempt = currentAttempt

        if (!useSingleSurfaceMode && readerSurface != null && device != null && session != null && attempt != null) {
            backgroundHandler?.post {
                try {
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    applyControls(req, attempt)
                    req.addTarget(readerSurface)
                    req.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())
                    req.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    session.capture(req.build(), null, backgroundHandler)
                } catch (e: Exception) {
                    Log.w(TAG, "Capture ImageReader échouée, repli TextureView.bitmap: ${e.message}")
                    captureFromTextureViewFallback()
                }
            }
        } else {
            captureFromTextureViewFallback()
        }
    }

    private fun captureFromTextureViewFallback() {
        val tv = textureView ?: return
        tv.post {
            val bmp = tv.bitmap
            if (bmp == null) {
                postError("Flux caméra non prêt pour la capture")
                return@post
            }
            backgroundHandler?.post {
                saveBitmapAsJpeg(bmp)
                bmp.recycle()
            }
        }
    }

    // --- Enregistrement Vidéo stable ---

    fun startVideoRecording(hasAudioPermission: Boolean) {
        if (videoRecorder.isRecording) return
        val attempt = currentAttempt ?: return
        val requestedRes = _uiState.value.videoResolution
        backgroundHandler?.post {
            try {
                val videoSurface = try {
                    videoRecorder.prepare(
                        resolution = requestedRes,
                        orientationHint = getJpegOrientation(),
                        includeAudio = hasAudioPermission,
                        externalOutputUri = externalOutputUri
                    )
                } catch (e: Exception) {
                    if (requestedRes == VideoResolution.UHD_4K) {
                        _uiState.update {
                            it.copy(
                                videoResolution = VideoResolution.FHD_1080P,
                                errorBanner = "4K indisponible — repli en 1080p"
                            )
                        }
                        videoRecorder.prepare(
                            resolution = VideoResolution.FHD_1080P,
                            orientationHint = getJpegOrientation(),
                            includeAudio = hasAudioPermission,
                            externalOutputUri = externalOutputUri
                        )
                    } else throw e
                }
                createSessionForAttempt(attempt, videoSurface = videoSurface, gen = attemptGeneration.get())
            } catch (e: Exception) {
                Log.e(TAG, "Erreur démarrage vidéo", e)
                videoRecorder.release()
                _uiState.update { it.copy(isRecording = false) }
                postError("Erreur vidéo : ${e.message}")
            }
        }
    }

    fun stopVideoRecording() {
        if (!videoRecorder.isRecording) return
        val attempt = currentAttempt
        backgroundHandler?.post {
            val savedUri = videoRecorder.stop()
            _uiState.update { it.copy(isRecording = false, recordingSeconds = 0L) }
            if (attempt != null) {
                createSessionForAttempt(attempt, videoSurface = null, gen = attemptGeneration.get())
            }
            if (savedUri != null) {
                val media = CapturedMedia(uri = savedUri, mimeType = "video/mp4", isVideo = true)
                _uiState.update { it.copy(lastCapturedMedia = media) }
                onMediaCaptured(media)
            }
        }
    }

    fun incrementRecordingSeconds() {
        if (_uiState.value.isRecording) {
            _uiState.update { it.copy(recordingSeconds = it.recordingSeconds + 1L) }
        }
    }

    // --- Sauvegarde MediaStore (DCIM/ExpertCam) ---

    private fun saveJpegImage(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            writeJpegBytesToMediaStore(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture buffer JPEG", e)
            postError("Erreur sauvegarde photo")
        } finally {
            image.close()
        }
    }

    private fun saveBitmapAsJpeg(bitmap: Bitmap) {
        try {
            val uri = createPhotoOutputUri() ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            finalizePhotoUri(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sauvegarde Bitmap JPEG", e)
            postError("Erreur sauvegarde photo")
        }
    }

    private fun writeJpegBytesToMediaStore(bytes: ByteArray) {
        val uri = createPhotoOutputUri() ?: return
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        finalizePhotoUri(uri)
    }

    private fun createPhotoOutputUri(): Uri? {
        if (externalOutputUri != null && _uiState.value.mode == CameraMode.PHOTO) {
            return externalOutputUri
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_EXPERT_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/ExpertCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun finalizePhotoUri(uri: Uri) {
        if (uri != externalOutputUri) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {}
        }
        val media = CapturedMedia(uri = uri, mimeType = "image/jpeg", isVideo = false)
        _uiState.update { it.copy(lastCapturedMedia = media) }
        onMediaCaptured(media)
    }

    fun refreshScannerCapabilities() {
        scanner.scanAllCameras()
        _uiState.update {
            it.copy(
                hasTeleLens = scanner.hasTeleAvailable(),
                hasFrontLens = scanner.hasFrontAvailable()
            )
        }
    }

    fun clearErrorBanner() {
        _uiState.update { it.copy(errorBanner = null) }
    }

    private fun postError(msg: String) {
        _uiState.update { it.copy(errorBanner = msg) }
    }

    fun closeCamera() {
        isPausedOrClosed = true
        attemptGeneration.incrementAndGet()
        cancelWatchdog()
        if (videoRecorder.isRecording) {
            stopVideoRecording()
        }
        closeCameraInternal()
        stopBackgroundThread()
    }

    private fun closeCameraInternal() {
        try {
            openCloseLock.acquire()
            try {
                captureSession?.stopRepeating()
            } catch (_: Exception) {}
            try {
                captureSession?.close()
            } catch (_: Exception) {}
            captureSession = null
            previewBuilder = null
            previewSurfaceRef?.release()
            previewSurfaceRef = null
            try {
                cameraDevice?.close()
            } catch (_: Exception) {}
            cameraDevice = null
            try {
                jpegImageReader?.close()
            } catch (_: Exception) {}
            jpegImageReader = null
        } catch (_: InterruptedException) {
        } finally {
            openCloseLock.release()
        }
    }

    private fun getJpegOrientation(): Int {
        val isFront = _uiState.value.activeLens == SafeLensType.FRONT
        return if (isFront) (sensorOrientation + 360) % 360 else sensorOrientation
    }

    private fun chooseBestPhotoSize(sizes: Array<Size>, refPreview: Size): Size {
        val refRatio = refPreview.width.toFloat() / refPreview.height.toFloat()
        val matching = sizes.filter {
            val r = it.width.toFloat() / it.height.toFloat()
            abs(r - refRatio) < 0.15f
        }
        val pool = if (matching.isNotEmpty()) matching else sizes.toList()
        return pool.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(4000, 3000)
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val tv = textureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val isRotated = (sensorOrientation == 90 || sensorOrientation == 270)
        val contentW = if (isRotated) previewSize.height.toFloat() else previewSize.width.toFloat()
        val contentH = if (isRotated) previewSize.width.toFloat() else previewSize.height.toFloat()

        val scaleX = viewWidth / contentW
        val scaleY = viewHeight / contentH
        val uniformScale = max(scaleX, scaleY)

        matrix.setScale(
            uniformScale / scaleX,
            uniformScale / scaleY,
            viewRect.centerX(),
            viewRect.centerY()
        )
        tv.setTransform(matrix)
    }
}

fun translateCameraError(error: Int): String = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "#1 IN_USE (Caméra déjà utilisée)"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "#2 MAX_CAMERAS (Limite caméras atteinte)"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "#3 DISABLED (Caméra désactivée par le système)"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "#4 ERROR_CAMERA_DEVICE - Panne matérielle"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "#5 CAMERA_SERVICE (Service caméra planté)"
    else -> "#$error INCONNU"
}
