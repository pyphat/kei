package me.piyaphat.kei.ui.series

import kotlin.math.max

data class BitmapRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun cropRectFromTransform(
    bitmapWidth: Int,
    bitmapHeight: Int,
    frameWidth: Float,
    frameHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): BitmapRect {
    require(bitmapWidth > 0 && bitmapHeight > 0 && frameWidth > 0 && frameHeight > 0)
    val scale = max(frameWidth / bitmapWidth, frameHeight / bitmapHeight) * zoom.coerceAtLeast(1f)
    val maxX = ((bitmapWidth * scale - frameWidth) / 2).coerceAtLeast(0f)
    val maxY = ((bitmapHeight * scale - frameHeight) / 2).coerceAtLeast(0f)
    val left = (((bitmapWidth * scale - frameWidth) / 2 - offsetX.coerceIn(-maxX, maxX)) / scale)
        .coerceIn(0f, bitmapWidth.toFloat())
    val top = (((bitmapHeight * scale - frameHeight) / 2 - offsetY.coerceIn(-maxY, maxY)) / scale)
        .coerceIn(0f, bitmapHeight.toFloat())
    return BitmapRect(left, top,
        (frameWidth / scale).coerceAtMost(bitmapWidth - left),
        (frameHeight / scale).coerceAtMost(bitmapHeight - top))
}
