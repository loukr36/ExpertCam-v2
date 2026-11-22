package com.expertcam.s22

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {

    private const val TAG = "ShareHelper"

    const val PKG_SNAPCHAT = "com.snapchat.android"
    const val PKG_INSTAGRAM = "com.instagram.android"
    const val PKG_WHATSAPP = "com.whatsapp"

    fun shareDirectToPackage(
        context: Context,
        mediaUri: Uri,
        mimeType: String,
        targetPackage: String,
        appLabel: String
    ) {
        val shareableUri = ensureFileProviderUri(context, mediaUri, mimeType) ?: mediaUri

        if (!isPackageInstalled(context, targetPackage)) {
            Toast.makeText(
                context,
                "$appLabel non installé — ouverture du partage Android",
                Toast.LENGTH_SHORT
            ).show()
            shareUniversal(context, shareableUri, mimeType)
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareableUri)
            setPackage(targetPackage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.grantUriPermission(
                targetPackage,
                shareableUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Échec envoi direct vers $targetPackage, fallback ShareSheet", e)
            shareUniversal(context, shareableUri, mimeType)
        }
    }

    fun shareUniversal(context: Context, mediaUri: Uri, mimeType: String) {
        val shareableUri = ensureFileProviderUri(context, mediaUri, mimeType) ?: mediaUri
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareableUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(baseIntent, "Partager via ExpertCam").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(context, "Aucune application de partage disponible", Toast.LENGTH_SHORT).show()
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun ensureFileProviderUri(context: Context, sourceUri: Uri, mimeType: String): Uri? {
        return try {
            val sharedDir = File(context.cacheDir, "shared").apply {
                if (!exists()) mkdirs()
            }
            sharedDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach {
                it.delete()
            }

            val ext = if (mimeType.startsWith("video/")) "mp4" else "jpg"
            val tempFile = File(sharedDir, "share_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
        } catch (e: Exception) {
            Log.w(TAG, "Utilisation directe de l'URI source: ${e.message}")
            null
        }
    }

    fun deleteMedia(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erreur suppression média $uri", e)
            false
        }
    }
}
