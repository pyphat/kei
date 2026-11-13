package me.piyaphat.kei.data.api

import kotlin.math.abs

fun initialReaderPage(routePage: Int?, progressChapterId: Long?, chapterId: Long, progressPage: Int?): Int =
    routePage ?: progressPage.takeIf { progressChapterId == chapterId } ?: 0

fun readerTapTarget(xFraction: Float, invert: Boolean, rtl: Boolean): Int = when {
    xFraction !in 0f..1f -> 0
    xFraction in 0.33f..0.67f -> 0
    else -> {
        val right = (xFraction > 0.5f) xor invert
        if (right xor rtl) 1 else -1
    }
}

fun smartReaderFit(pageWidth: Int, pageHeight: Int): String =
    if (pageWidth > pageHeight) "width" else "height"

fun readerProgressPercent(page: Int, pageCount: Int): Double =
    if (pageCount <= 0) 0.0 else ((page + 1).coerceIn(0, pageCount) * 100.0 / pageCount)

fun readerSwipePage(currentPage: Int, pageCount: Int, deltaX: Float, rtl: Boolean, zoomed: Boolean): Int? {
    if (zoomed || abs(deltaX) < 48f) return null
    val forward = if (rtl) deltaX > 0 else deltaX < 0
    return (currentPage + if (forward) 1 else -1).takeIf { it in 0 until pageCount }
}
