package me.piyaphat.kei.ui.upload

import me.piyaphat.kei.core.api.DetectedChapter
import me.piyaphat.kei.work.api.UploadChapter
import me.piyaphat.kei.work.api.UploadFile

internal object UploadPreviewMapper {
    fun map(
        detected: List<DetectedChapter>,
        files: Map<String, UploadFile>
    ): List<UploadChapter> = detected.mapNotNull { chapter ->
        val pages = chapter.files.mapNotNull(files::get)
        pages.takeIf { it.isNotEmpty() }?.let {
            UploadChapter(chapter.number, chapter.title, it)
        }
    }
}
