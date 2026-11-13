package me.piyaphat.kei.work.api

import java.io.InputStream
import kotlinx.serialization.Serializable

class UploadHttpException(val status: Int, message: String) : RuntimeException(message)

@Serializable data class UploadManifestFile(val idx: Int, val ext: String, val size: Long, val sha256: String)
@Serializable data class UploadManifestChapter(val number: Double, val title: String, val files: List<UploadManifestFile>)
@Serializable data class UploadManifestRequest(
    val series_id: Long? = null,
    val series_title: String? = null,
    val chapters: List<UploadManifestChapter>
)
@Serializable data class UploadManifestChapterResponse(val chapter_id: Long, val number: Double, val missing: List<Int>)
@Serializable data class UploadManifestResponse(val series_id: Long, val chapters: List<UploadManifestChapterResponse>)

interface UploadRemote {
    suspend fun manifest(request: UploadManifestRequest): UploadManifestResponse
    suspend fun putPage(chapterId: Long, index: Int, file: UploadFile, open: () -> InputStream)
    suspend fun setCover(seriesId: Long, chapterId: Long, page: Int)
}

interface UploadTaskStore {
    suspend fun get(id: Long): UploadTask?
    suspend fun update(task: UploadTask)
}

fun interface UploadSource {
    fun open(file: UploadFile): InputStream
}

interface UploadCleanup {
    fun finish(task: UploadTask)
}
