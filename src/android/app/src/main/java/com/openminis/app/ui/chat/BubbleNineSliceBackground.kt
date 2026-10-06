package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 聊天气泡九宫格平铺背景，对齐 Operit 的 `BubbleImageBackgroundSurface`。
 *
 * 渲染模式：
 *   * [BubbleImageRenderMode.TILED]（默认）：四个角块按 `crop` 比例定位并各画一次，
 *     中心区域用 `repeat` 子矩形平铺填满。
 *   * [BubbleImageRenderMode.NINE_PATCH]：经典九宫格拉伸，角块固定、四边与中心拉伸。
 *   * [BubbleImageRenderMode.STRETCH]：整图拉伸兜底。
 *
 * `crop` 是**显示区域的比例**（不是图片比例）：`crop.left` 表示左角块占气泡宽度的
 * 比例。这样 1400px 的大图也不会把角块撑成 350px 而盖掉中心。`imageScale` 在切分前
 * 统一缩放位图（0.1–4.0），用来微调纹样密度。
 *
 * 全程纯 Compose 绘制（`drawImage` 的 src/dst 重载）：Compose 1.9 的 `Canvas` 已移除
 * `nativeCanvas`，`DrawScope.drawRect` 也不再有 `RectF` 重载，所以任何走
 * `android.graphics.Paint` / `BitmapShader` 的写法都会直接编译失败。
 */
@Composable
fun BubbleNineSliceBackground(
    bitmap: ImageBitmap?,
    config: BubbleImageStyleConfig,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    scrimAlpha: Float = 0f,
    content: @Composable () -> Unit,
) {
    // 蒙层颜色在 Composable 作用域内读取 —— `drawBehind` 的 lambda 不是 @Composable，
    // 在里面读 MaterialTheme 会编译失败。
    val scrimColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind {
                bitmap?.let { bmp -> drawNineSlice(bmp, config, scrimAlpha, scrimColor) }
            },
    ) {
        content()
    }
}

private fun DrawScope.drawNineSlice(
    bitmap: ImageBitmap,
    config: BubbleImageStyleConfig,
    scrimAlpha: Float,
    scrimColor: Color,
) {
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    if (w <= 0f || h <= 0f) return

    val scale = config.imageScale.coerceAtLeast(0.1f).coerceAtMost(4f)
    val imgW = bitmap.width * scale
    val imgH = bitmap.height * scale

    val crop = config.crop
    // 源切分线（像素）
    val sxL = crop.left * imgW
    val sxT = crop.top * imgH
    val sxR = crop.right * imgW
    val sxB = crop.bottom * imgH

    // 目标切分线（像素）——按显示区域比例，保证大图片不放大角块
    val dxL = crop.left * w
    val dxT = crop.top * h
    val dxR = crop.right * w
    val dxB = crop.bottom * h

    val midW = (dxR - dxL).coerceAtLeast(0f)
    val midH = (dxB - dxT).coerceAtLeast(0f)

    // repeat 是「中心切分块内的子矩形」的比例，默认即整个中心块
    val rp = config.repeat
    val rL = sxL + rp.xStart * (sxR - sxL)
    val rT = sxT + rp.yStart * (sxB - sxT)
    val rR = sxL + rp.xEnd * (sxR - sxL)
    val rB = sxT + rp.yEnd * (sxB - sxT)

    // 单块绘制：源 (sl,st,sr,sb) → 目标 (dx,dy,dw,dh)
    fun drawBlock(sl: Float, st: Float, sr: Float, sb: Float,
                  dx: Float, dy: Float, dw: Float, dh: Float) {
        val sw = sr - sl
        val sh = sb - st
        if (sw <= 0.5f || sh <= 0.5f || dw <= 0.5f || dh <= 0.5f) return
        drawImage(
            bitmap,
            IntOffset(sl.roundToInt(), st.roundToInt()),
            IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
            IntOffset(dx.roundToInt(), dy.roundToInt()),
            IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
        )
    }

    // 单块平铺：以 (sr-sl, sb-st) 为步进，铺满 (dw, dh) 区域
    fun tileBlock(sl: Float, st: Float, sr: Float, sb: Float,
                  dx: Float, dy: Float, dw: Float, dh: Float) {
        val sw = sr - sl
        val sh = sb - st
        if (sw <= 0.5f || sh <= 0.5f || dw <= 0.5f || dh <= 0.5f) return
        var x = dx
        while (x < dx + dw - 0.5f) {
            var y = dy
            val endX = (dx + dw).coerceAtMost(x + sw)
            while (y < dy + dh - 0.5f) {
                val endY = (dy + dh).coerceAtMost(y + sh)
                drawImage(
                    bitmap,
                    IntOffset(sl.roundToInt(), st.roundToInt()),
                    IntSize(sw.roundToInt(), sh.roundToInt()),
                    IntOffset(x.roundToInt(), y.roundToInt()),
                    IntSize((endX - x).roundToInt(), (endY - y).roundToInt()),
                )
                y += sh
            }
            x += sw
        }
    }

    when (config.renderMode) {
        BubbleImageRenderMode.STRETCH -> {
            drawBlock(0f, 0f, imgW, imgH, 0f, 0f, w, h)
        }

        BubbleImageRenderMode.NINE_PATCH -> {
            // 角块保持源块比例；四边与中心拉伸
            drawBlock(0f, 0f, sxL, sxT, 0f, 0f, dxL, dxT)
            drawBlock(sxL, 0f, sxR, sxT, dxL, 0f, midW, dxT)
            drawBlock(sxR, 0f, imgW, sxT, dxR, 0f, w - dxR, dxT)
            drawBlock(0f, sxT, sxL, sxB, 0f, dxT, dxL, midH)
            drawBlock(sxL, sxT, sxR, sxB, dxL, dxT, midW, midH)
            drawBlock(sxR, sxT, imgW, sxB, dxR, dxT, w - dxR, midH)
            drawBlock(0f, sxB, sxL, imgH, 0f, dxB, dxL, h - dxB)
            drawBlock(sxL, sxB, sxR, imgH, dxL, dxB, midW, h - dxB)
            drawBlock(sxR, sxB, imgW, imgH, dxR, dxB, w - dxR, h - dxB)
        }

        BubbleImageRenderMode.TILED -> {
            // 角块各画一次（按源块拉伸到目标角块）；边与中心用 repeat 单元平铺
            drawBlock(0f, 0f, sxL, sxT, 0f, 0f, dxL, dxT)
            drawBlock(sxR, 0f, imgW, sxT, dxR, 0f, w - dxR, dxT)
            drawBlock(0f, sxB, sxL, imgH, 0f, dxB, dxL, h - dxB)
            drawBlock(sxR, sxB, imgW, imgH, dxR, dxB, w - dxR, h - dxB)
            tileBlock(rL, rT, rR, rB, dxL, 0f, midW, dxT)
            tileBlock(rL, rT, rR, rB, 0f, dxT, dxL, midH)
            tileBlock(rL, rT, rR, rB, dxR, dxT, w - dxR, midH)
            tileBlock(rL, rT, rR, rB, dxL, dxB, midW, h - dxB)
            tileBlock(rL, rT, rR, rB, dxL, dxT, midW, midH)
        }
    }

    if (scrimAlpha > 0f) {
        drawRect(scrimColor.copy(alpha = scrimAlpha))
    }
}

/** 气泡背景渲染模式，对齐 Operit 的 `renderMode`。 */
enum class BubbleImageRenderMode {
    TILED,        // 九宫格平铺（默认）
    NINE_PATCH,   // 九宫格拉伸
    STRETCH,      // 整图拉伸
}

/**
 * 气泡背景配置，对齐 Operit 的 `BubbleImageStyleConfig`。
 * 除 `uri` 外全部为 0-1 之间的比例，`imageScale` 例外。
 */
data class BubbleImageStyleConfig(
    val uri: String? = null,
    /** 预解码好的壁纸位图（由调用方在 IO 线程解码后传入，避免每个气泡重复解码）。 */
    val bitmap: ImageBitmap? = null,
    val cropLeft: Float = 0.25f,
    val cropTop: Float = 0.25f,
    val cropRight: Float = 0.75f,
    val cropBottom: Float = 0.75f,
    val repeatXStart: Float = 0f,
    val repeatXEnd: Float = 1f,
    val repeatYStart: Float = 0f,
    val repeatYEnd: Float = 1f,
    val imageScale: Float = 1f,
    val renderMode: BubbleImageRenderMode = BubbleImageRenderMode.TILED,
    val enabled: Boolean = false,
) {
    /** 归一化后的 crop 边界，clamp 后保证 left&lt;=right、top&lt;=bottom。 */
    val crop: BubbleImageCrop
        get() = BubbleImageCrop(
            left = cropLeft.coerceIn(0f, 1f),
            top = cropTop.coerceIn(0f, 1f),
            right = cropRight.coerceIn(0f, 1f),
            bottom = cropBottom.coerceIn(0f, 1f),
        ).clamp()

    val repeat: BubbleImageRepeatRegion
        get() = BubbleImageRepeatRegion(
            xStart = repeatXStart.coerceIn(0f, 1f),
            xEnd = repeatXEnd.coerceIn(0f, 1f),
            yStart = repeatYStart.coerceIn(0f, 1f),
            yEnd = repeatYEnd.coerceIn(0f, 1f),
        ).clamp()

    companion object {
        val NONE = BubbleImageStyleConfig()
    }
}

/** 归一化的 crop 边界（0-1）。 */
data class BubbleImageCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun clamp(): BubbleImageCrop {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(l, 1f)
        val t = top.coerceIn(0f, 1f)
        val b = bottom.coerceIn(t, 1f)
        return BubbleImageCrop(l, t, r, b)
    }
}

/** 中心块内的平铺单元比例（相对中心块）。 */
data class BubbleImageRepeatRegion(
    val xStart: Float,
    val xEnd: Float,
    val yStart: Float,
    val yEnd: Float,
) {
    fun clamp(): BubbleImageRepeatRegion {
        val xs = xStart.coerceIn(0f, 1f)
        val xe = xEnd.coerceIn(xs, 1f)
        val ys = yStart.coerceIn(0f, 1f)
        val ye = yEnd.coerceIn(ys, 1f)
        return BubbleImageRepeatRegion(xs, xe, ys, ye)
    }
}
