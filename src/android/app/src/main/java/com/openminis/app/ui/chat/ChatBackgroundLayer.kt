package com.openminis.app.ui.chat

/**
 * Custom chat background image with an iOS "liquid glass" treatment.
 *
 * The user picks any photo from the system picker; it is downscaled and copied
 * into the app's own private storage (the photo picker only grants temporary
 * read access, so keeping our own copy is what makes the wallpaper survive a
 * restart). It is then drawn behind the chat content, blurred, with a
 * theme-coloured glass veil on top so the message text stays legible.
 *
 * Blur is gated on API 31+ because it is implemented with
 * [android.graphics.RenderEffect]; older devices get the same veil without the
 * blur, which still reads as a frosted surface.
 *
 * No new XML drawables or string resources are referenced: everything here is
 * Compose code plus [MaterialTheme] colours, so the resource table can stay
 * untouched.
 */
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private-storage path of the wallpaper, persisted across restarts. */
private const val WALLPAPER_FILE = "chat_background.png"

/** Longest edge of the decoded wallpaper; the chat only needs ~screen resolution. */
private const val MAX_BITMAP_EDGE = 1400

class ChatBackgroundStore private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chat_background_prefs", Context.MODE_PRIVATE)

    private val _uri: MutableStateFlow<String?> =
        MutableStateFlow(prefs.getString("wallpaper_path", null))

    val uri: StateFlow<String?> = _uri

    /** Persist the wallpaper path (or null to clear) and notify observers. */
    fun setUri(value: String?) {
        _uri.value = value
        if (value == null) {
            File(appContext.filesDir, WALLPAPER_FILE).delete()
        }
        prefs.edit().putString("wallpaper_path", value).apply()
    }

    companion object {
        @Volatile private var INSTANCE: ChatBackgroundStore? = null

        fun getInstance(context: Context): ChatBackgroundStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatBackgroundStore(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Copy [source] (from the system photo picker) into the app's files dir,
         * downscaled to at most [MAX_BITMAP_EDGE] px on the long side. Returns
         * the stored path, or null if the image could not be read.
         */
        fun saveToAppStorage(context: Context, source: Uri): String? {
            // ImageDecoder (API 28+) handles HEIC/HEIF and bounds-decoding in one
            // step, avoiding OOM on 12MP+ photos. BitmapFactory fallback for 26-27.
            val dest = File(context.filesDir, WALLPAPER_FILE)
            return try {
                val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val decoder = ImageDecoder.createSource(context.contentResolver, source)
                    decoder.setOnHeaderDecodedListener { _, info, size ->
                        val w = info.size.width
                        val h = info.size.height
                        val longSide = maxOf(w, h)
                        if (longSide > MAX_BITMAP_EDGE) {
                            val scale = MAX_BITMAP_EDGE.toFloat() / longSide
                            size[0] = (w.toFloat() * scale).toInt().coerceAtLeast(1)
                            size[1] = (h.toFloat() * scale).toInt().coerceAtLeast(1)
                        }
                    }
                    decoder.decodeBitmap()
                } else {
                    val resolver = context.contentResolver
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, probe) }
                        ?: return null
                    var sample = 1
                    while (maxOf(probe.outWidth / sample, probe.outHeight / sample) > MAX_BITMAP_EDGE) sample *= 2
                    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, decode) }
                        ?: return null
                }
                FileOutputStream(dest).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                dest.absolutePath
            } catch (e: Throwable) {
                // HEIC-unsupported / oversized / I/O all land here; caller toasts the failure.
                null
            }
        }

        /** Delete the stored wallpaper file if one exists. */
        fun deleteStoredFile(context: Context) {
            File(context.filesDir, WALLPAPER_FILE).delete()
        }
    }
}

/**
 * Draw the wallpaper: blurred image + theme-coloured glass veil.
 *
 * Must be placed as the FIRST child of the chat's root [Box] so it sits above
 * the Scaffold container colour but below the message list. It is not
 * clickable, so it never steals touches from the messages above it.
 */
@Composable
fun ChatBackgroundLayer(
    uriString: String?,
    modifier: Modifier = Modifier,
    blurRadius: Float = 20f,
    /** Opacity of the theme-coloured veil — higher means more legible text. */
    scrimAlpha: Float = 0.45f,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = uriString,
    ) {
        value = uriString?.let { path ->
            withContext(Dispatchers.IO) { loadWallpaperBitmap(context, path) }
        }
    }
    val bitmap = image
    if (bitmap == null) return

    val glassTint = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= 31 && blurRadius > 0f) {
                        Modifier.blur(blurRadius.dp)
                    } else {
                        Modifier
                    },
                ),
        )
        // Liquid-glass veil: a soft top highlight falling into a solid wash.
        // Coloured by the active theme so it frosts in both light and dark.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            glassTint.copy(alpha = (scrimAlpha * 0.55f).coerceIn(0f, 1f)),
                            glassTint.copy(alpha = scrimAlpha),
                            glassTint.copy(alpha = scrimAlpha),
                        ),
                    ),
                ),
        )
    }
}

/** Decode the wallpaper from either a plain path or a content/file URI. */
private fun loadWallpaperBitmap(context: Context, path: String): ImageBitmap? {
    var bitmap: Bitmap? = null
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (path.startsWith("content://") || path.startsWith("file://")) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, probe)
            } ?: return null
        } else {
            val file = File(path)
            if (!file.exists()) return null
            FileInputStream(file).use { stream ->
                BitmapFactory.decodeStream(stream, null, probe)
            }
        }
        val rawW = probe.outWidth
        val rawH = probe.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sample = 1
        while (maxOf(rawW / sample, rawH / sample) > MAX_BITMAP_EDGE) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }

        bitmap = if (path.startsWith("content://") || path.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decode)
            }
        } else {
            FileInputStream(File(path)).use { stream ->
                BitmapFactory.decodeStream(stream, null, decode)
            }
        }
        return bitmap?.asImageBitmap()
}
