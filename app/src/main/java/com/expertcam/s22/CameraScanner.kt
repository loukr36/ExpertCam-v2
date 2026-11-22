package com.expertcam.s22

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlin.math.abs

enum class SafeLensType(val label: String, val zoomLabel: String) {
    WIDE("W", "1x"),
    TELE("T", "3x"),
    FRONT("FRONT", "Selfie")
}

enum class SensorRole(val label: String) {
    ULTRAWIDE_BROKEN("0.6x UW"),
    WIDE_1X("1x W"),
    TELE_3X("3x T"),
    FRONT("Avant"),
    UNKNOWN("Autre")
}

data class ScannedCameraInfo(
    val id: String,
    val facing: Int,
    val focalLengthMm: Float,
    val isInCameraIdList: Boolean,
    val isHidden: Boolean,
    val isLogicalMultiCam: Boolean,
    val physicalChildren: Set<String>,
    val role: SensorRole,
    val maxDigitalZoom: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float
) {
    fun shortBadge(): String {
        val hiddenTag = if (isHidden) " [Caché]" else ""
        val logicalTag = if (isLogicalMultiCam) " [Logique]" else ""
        return "ID $id • ${role.label} (${String.format(Locale.US, "%.2f", focalLengthMm)}mm)$hiddenTag$logicalTag"
    }
}

data class CameraCandidate(
    val cameraId: String,
    val lensType: SafeLensType,
    val isFallbackLogicalZero: Boolean,
    val fixedZoomRatio: Float,
    val focalLengthMm: Float
)

/**
 * Scanne cameraManager.cameraIdList ET tous les IDs numériques "0".."100".
 * Identifie le capteur 0.6x cassé (exclu du mode principal mais conservé pour le diagnostic),
 * classe les candidats 1x (IDs cachés en priorité, puis "0"), 3x (IDs cachés en priorité, puis "0")
 * et la caméra frontale. N'utilise jamais setPhysicalCameraId.
 */
class CameraScanner(context: Context) {

    companion object {
        private const val TAG = "CameraScanner"
        private const val WIDE_REF_MM = 5.4f
        private const val UW_TOLERANCE_MM = 0.45f
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _allCameras = mutableListOf<ScannedCameraInfo>()
    val allCameras: List<ScannedCameraInfo> get() = _allCameras

    private val _brokenUwIds = mutableSetOf<String>()
    val brokenUwIds: Set<String> get() = _brokenUwIds

    private val wideCandidates = mutableListOf<CameraCandidate>()
    private val teleCandidates = mutableListOf<CameraCandidate>()
    private val frontCandidates = mutableListOf<CameraCandidate>()

    var summaryLine: String = ""
        private set

    init {
        scanAllCameras()
    }

    fun scanAllCameras() {
        _allCameras.clear()
        _brokenUwIds.clear()
        wideCandidates.clear()
        teleCandidates.clear()
        frontCandidates.clear()

        val publicIds = try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur cameraIdList", e)
            emptyList()
        }
        val publicIdSet = publicIds.toSet()

        // Ensemble ordonné : tous les IDs publics + scan complet "0".."100"
        val probeIds = linkedSetOf<String>()
        probeIds.addAll(publicIds)
        for (i in 0..100) {
            probeIds.add(i.toString())
        }

        val rawFound = mutableListOf<RawCameraEntry>()
        for (id in probeIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val isLogical = caps.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
                val maxDig = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f)
                    .coerceAtLeast(1f)
                val (minZr, maxZr) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val r = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    (r?.lower ?: 1f) to (r?.upper ?: maxDig)
                } else {
                    1f to maxDig
                }
                val children = chars.physicalCameraIds.toSet()
                rawFound.add(
                    RawCameraEntry(
                        id = id,
                        facing = facing,
                        focal = focal,
                        inList = id in publicIdSet,
                        isLogical = isLogical,
                        children = children,
                        maxDig = maxDig,
                        minZoom = minZr,
                        maxZoom = maxZr
                    )
                )
            } catch (_: IllegalArgumentException) {
                // ID inexistant sur cet appareil : ignoré proprement
            } catch (e: Exception) {
                Log.w(TAG, "Erreur lecture ID $id: ${e.message}")
            }
        }

        val backEntries = rawFound.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.focal > 0f }
        val distinctBackFocals = backEntries.map { it.focal }.distinct().sorted()
        val shortestBackFocal = if (distinctBackFocals.size >= 2) distinctBackFocals.first() else null

        // Classification des rôles pour chaque capteur détecté
        for (entry in rawFound) {
            val role = when (entry.facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> SensorRole.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> {
                    when {
                        shortestBackFocal != null &&
                            entry.focal > 0f &&
                            entry.focal <= shortestBackFocal + UW_TOLERANCE_MM &&
                            entry.id != "0" -> {
                            _brokenUwIds.add(entry.id)
                            SensorRole.ULTRAWIDE_BROKEN
                        }
                        entry.focal >= WIDE_REF_MM + 1.1f -> SensorRole.TELE_3X
                        entry.focal > 0f -> SensorRole.WIDE_1X
                        else -> SensorRole.UNKNOWN
                    }
                }
                else -> SensorRole.UNKNOWN
            }

            _allCameras.add(
                ScannedCameraInfo(
                    id = entry.id,
                    facing = entry.facing,
                    focalLengthMm = entry.focal,
                    isInCameraIdList = entry.inList,
                    isHidden = !entry.inList,
                    isLogicalMultiCam = entry.isLogical,
                    physicalChildren = entry.children,
                    role = role,
                    maxDigitalZoom = entry.maxDig,
                    minZoomRatio = entry.minZoom,
                    maxZoomRatio = entry.maxZoom
                )
            )
        }

        val backCameraZero = _allCameras.firstOrNull {
            it.id == "0" && it.facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: _allCameras.firstOrNull {
            it.facing == CameraCharacteristics.LENS_FACING_BACK && it.isInCameraIdList && it.id !in _brokenUwIds
        }

        // Candidats 1x (W) : IDs dédiés / cachés non-logiques en priorité, puis "0" en repli
        val dedicatedWide = _allCameras
            .filter {
                it.facing == CameraCharacteristics.LENS_FACING_BACK &&
                    it.role == SensorRole.WIDE_1X &&
                    it.id != "0" &&
                    it.id !in _brokenUwIds
            }
            .sortedWith(
                compareBy<ScannedCameraInfo> { it.isLogicalMultiCam }
                    .thenBy { abs(it.focalLengthMm - WIDE_REF_MM) }
                    .thenBy { it.id.toIntOrNull() ?: 999 }
            )

        dedicatedWide.forEach { cam ->
            wideCandidates.add(
                CameraCandidate(
                    cameraId = cam.id,
                    lensType = SafeLensType.WIDE,
                    isFallbackLogicalZero = false,
                    fixedZoomRatio = 1.0f,
                    focalLengthMm = cam.focalLengthMm
                )
            )
        }
        if (backCameraZero != null) {
            wideCandidates.add(
                CameraCandidate(
                    cameraId = backCameraZero.id,
                    lensType = SafeLensType.WIDE,
                    isFallbackLogicalZero = true,
                    fixedZoomRatio = 1.0f,
                    focalLengthMm = backCameraZero.focalLengthMm
                )
            )
        }

        // Candidats 3x (T) : IDs cachés / dédiés téléobjectif en priorité, puis "0" avec zoom 3.0x
        val dedicatedTele = _allCameras
            .filter {
                it.facing == CameraCharacteristics.LENS_FACING_BACK &&
                    it.role == SensorRole.TELE_3X &&
                    it.id != "0" &&
                    it.id !in _brokenUwIds
            }
            .sortedWith(
                compareBy<ScannedCameraInfo> { it.isLogicalMultiCam }
                    .thenByDescending { it.focalLengthMm }
                    .thenBy { it.id.toIntOrNull() ?: 999 }
            )

        dedicatedTele.forEach { cam ->
            teleCandidates.add(
                CameraCandidate(
                    cameraId = cam.id,
                    lensType = SafeLensType.TELE,
                    isFallbackLogicalZero = false,
                    fixedZoomRatio = 1.0f,
                    focalLengthMm = cam.focalLengthMm
                )
            )
        }
        if (backCameraZero != null) {
            teleCandidates.add(
                CameraCandidate(
                    cameraId = backCameraZero.id,
                    lensType = SafeLensType.TELE,
                    isFallbackLogicalZero = true,
                    fixedZoomRatio = 3.0f,
                    focalLengthMm = backCameraZero.focalLengthMm
                )
            )
        }

        // Candidats FRONT (Selfie)
        _allCameras
            .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedWith(
                compareByDescending<ScannedCameraInfo> { it.id == "1" }
                    .thenByDescending { it.isInCameraIdList }
                    .thenBy { it.isLogicalMultiCam }
            )
            .forEach { cam ->
                frontCandidates.add(
                    CameraCandidate(
                        cameraId = cam.id,
                        lensType = SafeLensType.FRONT,
                        isFallbackLogicalZero = false,
                        fixedZoomRatio = 1.0f,
                        focalLengthMm = cam.focalLengthMm
                    )
                )
            }

        summaryLine = _allCameras.joinToString(", ") {
            "${it.id}(${String.format(Locale.US, "%.1f", it.focalLengthMm)}mm/${it.role.label})"
        }
        Log.i(TAG, "Caméras scannées: $summaryLine | UW exclus: $_brokenUwIds")
    }

    fun getCandidatesFor(lens: SafeLensType): List<CameraCandidate> = when (lens) {
        SafeLensType.WIDE -> wideCandidates.toList()
        SafeLensType.TELE -> teleCandidates.toList()
        SafeLensType.FRONT -> frontCandidates.toList()
    }

    fun hasTeleAvailable(): Boolean = teleCandidates.isNotEmpty()

    fun hasFrontAvailable(): Boolean = frontCandidates.isNotEmpty()

    fun getCharacteristics(cameraId: String): CameraCharacteristics? {
        return try {
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (_: Exception) {
            null
        }
    }

    private data class RawCameraEntry(
        val id: String,
        val facing: Int,
        val focal: Float,
        val inList: Boolean,
        val isLogical: Boolean,
        val children: Set<String>,
        val maxDig: Float,
        val minZoom: Float,
        val maxZoom: Float
    )
}
