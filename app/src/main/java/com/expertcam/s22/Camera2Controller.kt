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
 * Contrôleur Camera2 anti-crash pour Samsung Galaxy S22+ :
 * - Interdiction stricte d'appeler setPhysicalCameraId() (évite erreur -38).
 * - Ouverture en cascade : ID caché dédié d'abord, puis repli sur "0" avec UNE seule surface active,
 *   stabilisation désactivée (OIS/EIS OFF) et zoom >= 1.0f.
 * - Photo garantie via ImageReader JPEG ou capture haute qualité du TextureView en repli.
 * - Ratio d'image corrigé par matrice TextureView.setTransform sans déformation.
 */
class Camera2Controller(
    private val context: Context,
    val scanner: CameraScanner,
    private val externalOutputUri: Uri? = null,
    private val onMediaCaptured: (CapturedMedia) -> Unit
) {
    companion object {
        private const val TAG = "Camera2Controller"
    }

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

    private var currentCandidate: CameraCandidate? = null
    private var currentChars: CameraCharacteristics? = null
    private var previewSize: Size = Size(1920, 1080)
    private var sensorOrientation: Int = 90

    // Liste en cascade pour l'objectif courant
    private var candidateQueue: List<CameraCandidate> = emptyList()
    private var candidateIndex: Int = 0
    private var usingSingleSurfaceFallback: Boolean = false
    private var isPausedOrClosed: Boolean = false

    private val _uiState = MutableStateFlow(
        CameraUiState(
            hasTeleLens = scanner.hasTeleAvailable(),
            hasFrontLens = scanner.hasFrontAvailable()
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("ExpertCam2MainBg").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
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

        val list = scanner.getCandidatesFor(requestedLens)
            .filter { it.cameraId !in scanner.brokenUwIds }

        if (list.isEmpty()) {
            postError("Aucun capteur disponible pour ${requestedLens.zoomLabel}")
            return
        }

        candidateQueue = list
        candidateIndex = 0
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
        tryOpenCurrentCandidate()
    }

    @SuppressLint("MissingPermission")
    private fun tryOpenCurrentCandidate() {
        if (isPausedOrClosed || textureView?.isAvailable != true) return
        val candidate = candidateQueue.getOrNull(candidateIndex) ?: run {
            postError("Échec ouverture ${uiState.value.activeLens.zoomLabel} : tous les candidats ont échoué")
            return
        }

        // Sécurité absolue : ne jamais ouvrir un ID banni 0.6x en mode principal
        if (candidate.cameraId in scanner.brokenUwIds) {
            advanceCascade("ID ${candidate.cameraId} banni (0.6x)")
            return
        }

        val chars = scanner.getCharacteristics(candidate.cameraId) ?: run {
            advanceCascade("Caractéristiques introuvables pour ID ${candidate.cameraId}")
            return
        }

        closeCameraInternal()

        currentCandidate = candidate
        currentChars = chars
        usingSingleSurfaceFallback = candidate.isFallbackLogicalZero
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        prepareStreamsForCandidate(chars, attachJpegReader = !usingSingleSurfaceFallback)

        _uiState.update { it.copy(activeCameraId = candidate.cameraId) }

        try {
            if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Timeout verrou Camera2")
            }
            cameraManager.openCamera(
                candidate.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openCloseLock.release()
                        if (isPausedOrClosed) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSessionStandard(videoSurface = null)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        openCloseLock.release()
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        openCloseLock.release()
                        camera.close()
                        if (cameraDevice == camera) cameraDevice = null
                        advanceCascade("onError #$error (${translateCameraError(error)}) sur ID ${candidate.cameraId}")
                    }
                },
                backgroundHandler
            )
        } catch (e: IllegalArgumentException) {
            openCloseLock.release()
            advanceCascade("ID ${candidate.cameraId} refusé (IllegalArgumentException)")
        } catch (e: SecurityException) {
            openCloseLock.release()
            advanceCascade("ID ${candidate.cameraId} bloqué (SecurityException)")
        } catch (e: CameraAccessException) {
            openCloseLock.release()
            advanceCascade("CameraAccessException #${e.reason} sur ID ${candidate.cameraId}: ${e.message}")
        } catch (e: Exception) {
            openCloseLock.release()
            advanceCascade("Exception ouverture ID ${candidate.cameraId}: ${e.message}")
        }
    }

    private fun advanceCascade(reason: String) {
        Log.w(TAG, "Cascade -> échec candidat #$candidateIndex : $reason")
        if (isPausedOrClosed) return

        val nextIdx = candidateIndex + 1
        if (nextIdx < candidateQueue.size) {
            candidateIndex = nextIdx
            backgroundHandler?.postDelayed({ tryOpenCurrentCandidate() }, 200L)
        } else {
            postError(reason)
        }
    }

    private fun prepareStreamsForCandidate(
        chars: CameraCharacteristics,
        attachJpegReader: Boolean
    ) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
        val tvW = textureView?.width ?: 1080
        val tvH = textureView?.height ?: 1920
        previewSize = chooseOptimalPreviewSize(previewSizes, tvW, tvH)

        jpegImageReader?.close()
        jpegImageReader = null

        if (attachJpegReader) {
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
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

    /**
     * Crée une session Camera2 avec UNIQUEMENT des OutputConfiguration(surface) standards.
     * Aucun appel à setPhysicalCameraId().
     */
    private fun createSessionStandard(videoSurface: Surface?) {
        val device = cameraDevice ?: return
        val candidate = currentCandidate ?: return
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

            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurfaceRef?.release()
            val previewSurface = Surface(st)
            previewSurfaceRef = previewSurface

            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurface)

            if (videoSurface != null) {
                surfaces.add(videoSurface)
            } else if (!usingSingleSurfaceFallback) {
                jpegImageReader?.surface?.let { surfaces.add(it) }
            }

            // OutputConfiguration standard uniquement — JAMAIS setPhysicalCameraId
            val configs = surfaces.map { OutputConfiguration(it) }

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
                    if (cameraDevice == null || isPausedOrClosed) {
                        session.close()
                        return
                    }
                    captureSession = session
                    applyAutoControls(builder, isLogicalFallback = usingSingleSurfaceFallback)
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        if (videoSurface != null) {
                            videoRecorder.start()
                            _uiState.update { it.copy(isRecording = true, recordingSeconds = 0L, errorBanner = null) }
                        } else {
                            _uiState.update { it.copy(errorBanner = null) }
                        }
                    } catch (e: Exception) {
                        if (videoSurface != null) {
                            handleVideoStartFailure("Erreur démarrage flux vidéo: ${e.message}")
                        } else {
                            advanceCascade("setRepeatingRequest échoué sur ID ${candidate.cameraId}: ${e.message}")
                        }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (videoSurface != null) {
                        handleVideoStartFailure("Configuration vidéo refusée sur ID ${candidate.cameraId}")
                    } else if (!usingSingleSurfaceFallback) {
                        // Repli immédiat en surface unique (sans ImageReader) sur le même capteur
                        usingSingleSurfaceFallback = true
                        jpegImageReader?.close()
                        jpegImageReader = null
                        createSessionStandard(videoSurface = null)
                    } else {
                        advanceCascade("onConfigureFailed sur ID ${candidate.cameraId}")
                    }
                }
            }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                configs,
                sessionExecutor,
                callback
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            if (videoSurface != null) {
                handleVideoStartFailure("Exception session vidéo: ${e.message}")
            } else {
                advanceCascade("Exception session ID ${candidate.cameraId}: ${e.message}")
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
        createSessionStandard(videoSurface = null)
    }

    private fun applyAutoControls(
        builder: CaptureRequest.Builder,
        isLogicalFallback: Boolean
    ) {
        val state = _uiState.value
        val candidate = currentCandidate ?: return
        val chars = currentChars ?: return

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // Exposition + Flash auto
        val aeMode = when (state.flash) {
            FlashOption.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            FlashOption.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            FlashOption.OFF -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)

        // Autofocus continu automatique
        val afMode = if (state.mode == CameraMode.VIDEO) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        } else {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)

        // Stabilisation : strictement OFF en repli logique "0" pour éviter le réveil du 0.6x
        if (isLogicalFallback) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        } else if (state.mode == CameraMode.VIDEO) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        // Zoom strictement >= 1.0f en mode caméra principale
        val requestedZoom = when {
            candidate.isFallbackLogicalZero -> state.zoomDisplay.coerceAtLeast(candidate.fixedZoomRatio)
            candidate.lensType == SafeLensType.TELE -> (state.zoomDisplay / 3.0f).coerceAtLeast(1.0f)
            else -> state.zoomDisplay.coerceAtLeast(1.0f)
        }.coerceAtLeast(1.0f)

        applySafeZoom(builder, chars, requestedZoom)
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
        backgroundHandler?.post {
            try {
                applyAutoControls(builder, isLogicalFallback = usingSingleSurfaceFallback)
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
        val chars = currentChars ?: return
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

        // Si ImageReader est actif sur la session, capture JPEG plein capteur
        if (!usingSingleSurfaceFallback && readerSurface != null && device != null && session != null) {
            backgroundHandler?.post {
                try {
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    applyAutoControls(req, isLogicalFallback = false)
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
            // Repli garanti sans reconfigurer la session sur "0"
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
                createSessionStandard(videoSurface = videoSurface)
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
        backgroundHandler?.post {
            val savedUri = videoRecorder.stop()
            _uiState.update { it.copy(isRecording = false, recordingSeconds = 0L) }
            createSessionStandard(videoSurface = null)
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

    /**
     * Choisit une résolution de prévisualisation proche du ratio de l'écran pour limiter le recadrage.
     */
    private fun chooseOptimalPreviewSize(sizes: Array<Size>, viewWidth: Int, viewHeight: Int): Size {
        val targetLandscapeRatio = if (viewWidth > 0 && viewHeight > 0) {
            max(viewWidth, viewHeight).toFloat() / minOf(viewWidth, viewHeight).toFloat()
        } else {
            4f / 3f
        }
        val capped = sizes.filter { it.width <= 1920 && it.height <= 1440 }
        val pool = if (capped.isNotEmpty()) capped else sizes.toList()
        return pool.minByOrNull { s ->
            val r = s.width.toFloat() / s.height.toFloat()
            abs(r - targetLandscapeRatio) * 10f - (s.width / 10000f)
        } ?: Size(1920, 1080)
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

    /**
     * Applique une matrice Center-Crop exacte sur TextureView afin que l'image ne soit jamais
     * étirée ni écrasée quelles que soient la résolution du capteur et la taille de l'écran.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val tv = textureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // En orientation portrait, le buffer capteur paysage (W x H) est pivoté de 90° -> (H x W)
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
