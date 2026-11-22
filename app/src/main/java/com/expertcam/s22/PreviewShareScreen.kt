package com.expertcam.s22

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PreviewShareScreen(
    media: CapturedMedia,
    isSystemCaptureIntent: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onConfirmSystemIntent: () -> Unit
) {
    val context = LocalContext.current
    var photoBitmap by remember(media.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(media.uri) {
        if (!media.isVideo) {
            photoBitmap = withContext(Dispatchers.IO) {
                try {
                    val rotation = context.contentResolver.openInputStream(media.uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        when (exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f

                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val rawBmp = context.contentResolver.openInputStream(media.uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    } ?: return@withContext null

                    if (rotation != 0f) {
                        val m = Matrix().apply { postRotate(rotation) }
                        Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                    } else rawBmp
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AnthraciteSurface)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text("Caméra", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Text(
                text = if (media.isVideo) "VIDÉO MP4" else "PHOTO JPEG",
                color = ExpertGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            if (isSystemCaptureIntent) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(ExpertGold)
                        .clickable(onClick = onConfirmSystemIntent)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Valider", tint = OledBlack, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Utiliser", color = OledBlack, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(Modifier.size(64.dp))
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (media.isVideo) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val mc = MediaController(ctx)
                            mc.setAnchorView(this)
                            setMediaController(mc)
                            setVideoURI(media.uri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    }
                )
            } else {
                val bmp = photoBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Aperçu capture",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(text = "Chargement...", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141416))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShareActionButton(
                label = "Supprimer",
                badgeColor = Color(0xFF3A1C1C),
                textColor = RecordRed,
                onClick = {
                    val ok = ShareHelper.deleteMedia(context, media.uri)
                    Toast.makeText(
                        context,
                        if (ok) "Média supprimé" else "Impossible de supprimer ce fichier",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onDeleted()
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = RecordRed, modifier = Modifier.size(22.dp))
            }

            ShareActionButton(
                label = "Snap",
                badgeColor = Color(0xFFFFFC00),
                textColor = OledBlack,
                onClick = {
                    ShareHelper.shareDirectToPackage(
                        context, media.uri, media.mimeType, ShareHelper.PKG_SNAPCHAT, "Snapchat"
                    )
                }
            ) {
                Text("S", color = OledBlack, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }

            ShareActionButton(
                label = "Insta",
                badgeColor = Color(0xFFE1306C),
                textColor = Color.White,
                onClick = {
                    ShareHelper.shareDirectToPackage(
                        context, media.uri, media.mimeType, ShareHelper.PKG_INSTAGRAM, "Instagram"
                    )
                }
            ) {
                Text("IG", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            ShareActionButton(
                label = "WhatsApp",
                badgeColor = Color(0xFF25D366),
                textColor = OledBlack,
                onClick = {
                    ShareHelper.shareDirectToPackage(
                        context, media.uri, media.mimeType, ShareHelper.PKG_WHATSAPP, "WhatsApp"
                    )
                }
            ) {
                Text("WA", color = OledBlack, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }

            ShareActionButton(
                label = "Autres",
                badgeColor = Color(0xFF2C2C2E),
                textColor = ExpertGold,
                onClick = { ShareHelper.shareUniversal(context, media.uri, media.mimeType) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Autres", tint = ExpertGold, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ShareActionButton(
    label: String,
    badgeColor: Color,
    textColor: Color,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            iconContent()
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            color = if (textColor == RecordRed) RecordRed else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
