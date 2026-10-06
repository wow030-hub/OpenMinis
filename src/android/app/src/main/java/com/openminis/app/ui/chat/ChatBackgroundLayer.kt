package com.openminis.app.ui.chat

/**
 * Custom chat background image, drawn either as an iOS "liquid glass" (blurred
 * + theme-coloured veil) or tiled flat edge-to-edge.
 *
 * The user picks any photo from the system picker; it is downscaled and copied
 * into the app's own private storage (the photo picker only grants temporary
 * read access, so keeping our own copy is what makes the wallpaper survive a
 * restart).
 *
 * Blur is gated on API 31+ because it is implemented with
 * [android.graphics.RenderEffect]; older devices get the same veil without the
 * blur, which still reads as a frosted surface. The tiled mode needs no
 * version gate at all — it is plain [drawImage] on every API.
 *
 * No new XML drawables or string resources are referenced: everything here is
 * Compose code plus [MaterialTheme] colours, so the resource table stays
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Private-storage path of the wallpaper, persisted across restarts. */
private const val WALLPAPER_FILE = "chat_background.png"

/** Longest edge of the decoded wallpaper; the chat only needs ~screen resolution. */
private const val MAX_BITMAP_EDGE = 1400

/** How the selected wallpaper is drawn behind the chat. */
enum class ChatBackgroundMode {
    /** Blur + a theme-coloured veil so the message text stays legible. */
    BLUR,

    /** Repeat the whole photo flat across the chat area (no blur, no veil). */
    TILED,
}

/**
 * Persistent home for the chat-wallpaper choice plus the bubble nine-slice
 * overlay settings.
 *
 * [uri] is a filesystem path into the app's own `filesDir`, never the
 * photo-picker URI: the picker hands out temporary read access that dies on
 * restart, so the picked photo is copied in and downscaled first.
 *
 * `bubble*` properties are stored as individual keys rather than one JSON blob
 * so that reading any of them stays a plain SharedPreferences lookup.
 */
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

    // --- Bubble nine-slice overlay -------------------------------------------
    // Shared prefs prefix (see BUBBLE_PREFIX in the companion object below) so
    // bubble settings can never collide with the wallpaper path key.

    /**
     * Whether user bubbles carry the nine-slice wallpaper overlay. Read through
     * a StateFlow so flipping the menu toggle recomposes the visible bubbles.
     */
    private val _bubbleEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean(BUBBLE_PREFIX + "enabled", false))
    val bubbleEnabled: StateFlow<Boolean> = _bubbleEnabled

    fun setBubbleNineSliceEnabled(enabled: Boolean) {
        _bubbleEnabled.value = enabled
        prefs.edit().putBoolean(BUBBLE_PREFIX + "enabled", enabled).apply()
    }

    /** Whether the chat wallpaper is drawn tiled rather than blurred. */
    private val _chatBackgroundMode: MutableStateFlow<ChatBackgroundMode> =
        MutableStateFlow(loadChatBackgroundMode())
    val chatBackgroundMode: StateFlow<ChatBackgroundMode> = _chatBackgroundMode

    fun setChatBackgroundMode(mode: ChatBackgroundMode) {
        _chatBackgroundMode.value = mode
        prefs.edit().putInt(BUBBLE_PREFIX + "mode", mode.ordinal).apply()
    }

    private fun loadChatBackgroundMode(): ChatBackgroundMode {
        val ord = prefs.getInt(BUBBLE_PREFIX + "mode", ChatBackgroundMode.BLUR.ordinal)
        return ChatBackgroundMode.values().getOrElse(ord) { ChatBackgroundMode.BLUR }
    }

    // --- Bubble crop boundaries (0-1 of the source image) -------------------
    var bubbleCropLeft: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "crop_left", 0.25f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "crop_left", value).apply()

    var bubbleCropTop: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "crop_top", 0.25f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "crop_top", value).apply()

    var bubbleCropRight: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "crop_right", 0.75f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "crop_right", value).apply()

    var bubbleCropBottom: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "crop_bottom", 0.75f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "crop_bottom", value).apply()

    // --- Bubble repeat region (0-1 of the cropped centre block) -------------
    var bubbleRepeatXStart: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "repeat_x_start", 0f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "repeat_x_start", value).apply()

    var bubbleRepeatXEnd: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "repeat_x_end", 1f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "repeat_x_end", value).apply()

    var bubbleRepeatYStart: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "repeat_y_start", 0f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "repeat_y_start", value).apply()

    var bubbleRepeatYEnd: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "repeat_y_end", 1f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "repeat_y_end", value).apply()

    /** Overall scale applied to the source before cropping. */
    var bubbleImageScale: Float
        get() = prefs.getFloat(BUBBLE_PREFIX + "image_scale", 1f)
        set(value) = prefs.edit().putFloat(BUBBLE_PREFIX + "image_scale", value).apply()

    /** [BubbleImageRenderMode] as an ordinal, so the class outlives the enum. */
    var bubbleRenderMode: Int
        get() = prefs.getInt(BUBBLE_PREFIX + "render_mode", BubbleImageRenderMode.TILED.ordinal)
        set(value) = prefs.edit().putInt(BUBBLE_PREFIX + "render_mode", value).apply()

    fun safeRenderMode(ordinal: Int): BubbleImageRenderMode =
        BubbleImageRenderMode.values().getOrElse(ordinal) { BubbleImageRenderMode.TILED }

    /** Build the overlay config for the bubble renderer. */
    fun getBubbleNineSliceConfig(uriString: String?): BubbleImageStyleConfig {
        val enabled = _bubbleEnabled.value && uriString != null
        return BubbleImageStyleConfig(
            uri = uriString,
            cropLeft = bubbleCropLeft,
            cropTop = bubbleCropTop,
            cropRight = bubbleCropRight,
            cropBottom = bubbleCropBottom,
            repeatXStart = bubbleRepeatXStart,
            repeatXEnd = bubbleRepeatXEnd,
            repeatYStart = bubbleRepeatYStart,
            repeatYEnd = bubbleRepeatYEnd,
            imageScale = bubbleImageScale,
            renderMode = safeRenderMode(bubbleRenderMode),
            enabled = enabled,
        )
    }

    /** Write the whole bubble config in one call; each property defaults to its own current value. */
    fun setBubbleNineSliceConfig(
        cropLeft: Float = bubbleCropLeft,
        cropTop: Float = bubbleCropTop,
        cropRight: Float = bubbleCropRight,
        cropBottom: Float = bubbleCropBottom,
        repeatXStart: Float = bubbleRepeatXStart,
        repeatXEnd: Float = bubbleRepeatXEnd,
        repeatYStart: Float = bubbleRepeatYStart,
        repeatYEnd: Float = bubbleRepeatYEnd,
        imageScale: Float = bubbleImageScale,
        renderMode: BubbleImageRenderMode = safeRenderMode(bubbleRenderMode),
        enabled: Boolean = _bubbleEnabled.value,
    ) {
        bubbleCropLeft = cropLeft
        bubbleCropTop = cropTop
        bubbleCropRight = cropRight
        bubbleCropBottom = cropBottom
        bubbleRepeatXStart = repeatXStart
        bubbleRepeatXEnd = repeatXEnd
        bubbleRepeatYStart = repeatYStart
        bubbleRepeatYEnd = repeatYEnd
        bubbleImageScale = imageScale
        bubbleRenderMode = renderMode.ordinal
        setBubbleNineSliceEnabled(enabled)
    }

    companion object {
        @Volatile private var INSTANCE: ChatBackgroundStore? = null

        /** Shared prefs key prefix for every bubble nine-slice setting. */
        const val BUBBLE_PREFIX = "bubble_nine_slice_"

        fun getInstance(context: Context): ChatBackgroundStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChatBackgroundStore(context.applicationContext).also { INSTANCE = it }
            }

        /** Delete the stored wallpaper file if one exists. */
        fun deleteStoredFile(context: Context) {
            File(context.filesDir, WALLPAPER_FILE).delete()
        }

        /**
         * Copy [source] (from the system photo picker) into the app's files
         * dir, downscaled to at most [MAX_BITMAP_EDGE] px on the long side.
         * Returns the stored path, or null if the image could not be read.
         *
         * The decode goes through [ImageDecoder] when available (API 28+)
         * because Samsung devices routinely hand out HEIC, which
         * `BitmapFactory` cannot parse. Older devices keep the simpler
         * `BitmapFactory` path, gated with `Build.VERSION.SDK_INT` so it never
         * triggers a lint NewApi error.
         */
        fun saveToAppStorage(context: Context, source: Uri): String? {
            val dest = File(context.filesDir, WALLPAPER_FILE)
            return try {
                val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(source)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, probe)
                }
                val rawW = probe.outWidth
                val rawH = probe.outHeight
                if (rawW <= 0 || rawH <= 0) {
                    dest.delete()
                    return null
                }
                val scale = MAX_BITMAP_EDGE.toFloat() / maxOf(rawW, rawH)
                val targetW = (rawW * scale).toInt().coerceAtLeast(1)
                val targetH = (rawH * scale).toInt().coerceAtLeast(1)
                val result: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                    context.contentResolver.openInputStream(source)?.use { stream ->
                        val inputSource = ImageDecoder.createSource(context.contentResolver, source)
                        val decoder = ImageDecoder.decodeBitmap(inputSource) { src, _, _ ->
                            src.setTargetSize(targetW, targetH)
                        }
                        // The stream is left open only so the resolver holds
                        // the file descriptor; decodeBitmap used the Source,
                        // not the stream. Close it to avoid a leak.
                        stream.close()
                        decoder
                    }
                } else {
                    val sample = maxOf(1, maxOf(rawW, rawH) / MAX_BITMAP_EDGE)
                    context.contentResolver.openInputStream(source)?.use { stream ->
                        BitmapFactory.Options().apply { inSampleSize = sample }.let { opt ->
                            BitmapFactory.decodeStream(stream, null, opt)
                        }
                    }
                }
                result?.let { bmp ->
                    FileOutputStream(dest).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                    dest.absolutePath
                } ?: run {
                    dest.delete()
                    null
                }
            } catch (e: Throwable) {
                dest.delete()
                null
            }
        }
    }
}

/**
 * Draw the wallpaper: either a blurred image with a theme-coloured glass veil
 * on top, or the photo repeated flat across the chat area.
 *
 * Must be placed as the FIRST child of the chat's root [Box] so it sits above
 * the Scaffold container colour but below the message list. It is not
 * clickable, so it never steals touches from the messages above it.
 *
 * [mode] is [ChatBackgroundMode.BLUR] by default (legacy behaviour); set it to
 * [ChatBackgroundMode.TILED] to repeat the photo edge-to-edge with no veil.
 */
@Composable
fun ChatBackgroundLayer(
    uriString: String?,
    modifier: Modifier = Modifier,
    mode: ChatBackgroundMode = ChatBackgroundMode.BLUR,
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

    if (mode == ChatBackgroundMode.TILED) {
        // Repeat the whole photo flat. The entire image is a single tile, so
        // there is no nine-slice split — just wrap the draw origin whenever it
        // reaches the right or bottom edge.
        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .drawBehind {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val tileW = bitmap.width.toFloat()
                    val tileH = bitmap.height.toFloat()
                    if (tileW <= 0f || tileH <= 0f) return@drawBehind
                    var y = 0f
                    while (y < h) {
                        var x = 0f
                        while (x < w) {
                            drawImage(
                                bitmap,
                                IntOffset(0, 0),
                                IntSize(tileW.roundToInt(), tileH.roundToInt()),
                                IntOffset(x.roundToInt(), y.roundToInt()),
                                IntSize(
                                    (w - x).coerceAtMost(tileW).roundToInt(),
                                    (h - y).coerceAtMost(tileH).roundToInt(),
                                ),
                            )
                            x += tileW
                        }
                        y += tileH
                    }
                },
        )
        return
    }

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

/**
 * Read the full bubble nine-slice config from the [ChatBackgroundStore] and
 * hand it to the bubble renderer.
 *
 * Collects [ChatBackgroundStore.uri] and [ChatBackgroundStore.bubbleEnabled]
 * as state so that flipping the menu toggle recomposes the bubbles. The
 * crop/repeat/scale values are plain SharedPreferences reads: they only change
 * from the settings screen, which always follows a `remember { }`-keyed
 * recomposition, so no extra state plumbing is needed for them.
 *
 * [bitmap] is the pre-decoded wallpaper. Pass the one instance produced once in
 * [ChatScreen] rather than decoding per bubble — bubbles live in a LazyColumn,
 * so a per-item decode would hit the disk on every scroll row.
 */
@Composable
fun bubbleNineSliceConfig(
    store: ChatBackgroundStore,
    bitmap: ImageBitmap? = null,
): BubbleImageStyleConfig {
    val uri by store.uri.collectAsState()
    val enabled by store.bubbleEnabled.collectAsState()
    return BubbleImageStyleConfig(
        uri = uri,
        bitmap = bitmap,
        cropLeft = store.bubbleCropLeft,
        cropTop = store.bubbleCropTop,
        cropRight = store.bubbleCropRight,
        cropBottom = store.bubbleCropBottom,
        repeatXStart = store.bubbleRepeatXStart,
        repeatXEnd = store.bubbleRepeatXEnd,
        repeatYStart = store.bubbleRepeatYStart,
        repeatYEnd = store.bubbleRepeatYEnd,
        imageScale = store.bubbleImageScale,
        renderMode = store.safeRenderMode(store.bubbleRenderMode),
        enabled = enabled && uri != null,
    )
}

/**
 * Decode the wallpaper from either a plain path or a content/file URI.
 *
 * Downscales with `inSampleSize` so a phone-camera original (often 100 MB)
 * never lands in memory at full size — the chat needs roughly screen
 * resolution, not print resolution.
 */
fun loadWallpaperBitmap(context: Context, path: String): ImageBitmap? {
    var bitmap: Bitmap? = null
    if (path.startsWith("content://") || path.startsWith("file://")) {
        context.contentResolver.openInputStream(Uri.parse(path))?.use { stream ->
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, probe)
            val w = probe.outWidth
            val h = probe.outHeight
            var sample = 1
            while (maxOf(w / sample, h / sample) > MAX_BITMAP_EDGE) {
                sample *= 2
            }
            bitmap = BitmapFactory.Options().apply { inSampleSize = sample }.let { opt ->
                context.contentResolver.openInputStream(Uri.parse(path))?.use { s2 ->
                    BitmapFactory.decodeStream(s2, null, opt)
                }
            }
        }
    } else {
        val file = File(path)
        if (file.exists()) {
            FileInputStream(file).use { stream ->
                val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, probe)
                val w = probe.outWidth
                val h = probe.outHeight
                var sample = 1
                while (maxOf(w / sample, h / sample) > MAX_BITMAP_EDGE) {
                    sample *= 2
                }
                FileInputStream(file).use { s2 ->
                    val opt = BitmapFactory.Options().apply { inSampleSize = sample }
                    bitmap = BitmapFactory.decodeStream(s2, null, opt)
                }
            }
        }
    }
    return bitmap?.asImageBitmap()
}
