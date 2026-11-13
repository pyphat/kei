package me.piyaphat.kei.work.api

import androidx.work.ForegroundInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class UploadFile(
    val path: String,
    val location: String,
    val ext: String,
    val size: Long,
    val sha256: String? = null
)

@Serializable
data class UploadChapter(
    val number: Double,
    val title: String,
    val files: List<UploadFile>
)

@Serializable
data class SkippedUploadFile(val path: String, val reason: String)

@Serializable
data class UploadTask(
    val id: Long = 0,
    val targetSeriesId: Long? = null,
    val seriesTitle: String = "",
    val coverPageIndex: Int = 0,
    val chapters: List<UploadChapter> = emptyList(),
    val permissionUris: List<String> = emptyList(),
    val skipped: List<SkippedUploadFile> = emptyList(),
    val state: String = "queued",
    val done: Int = 0,
    val total: Int = 0,
    val seriesId: Long? = null,
    val chapterIds: List<Long> = emptyList(),
    val error: String? = null
)

interface UploadScheduler {
    suspend fun create(task: UploadTask): Long
    suspend fun update(task: UploadTask)
    fun enqueue(taskId: Long)
    fun cancel(taskId: Long)
    suspend fun remove(taskId: Long)
    suspend fun reconcile()
    fun observe(): Flow<List<UploadTask>>
}

interface UploadTaskRepository : UploadTaskStore {
    suspend fun create(task: UploadTask): Long
    suspend fun all(): List<UploadTask>
    suspend fun delete(id: Long)
    fun observe(): Flow<List<UploadTask>>
}

interface UploadNotifier {
    fun foregroundInfo(taskId: Long, title: String, done: Int, total: Int): ForegroundInfo
    fun notifyDone(taskId: Long, title: String, seriesId: Long)
    fun notifyFailed(taskId: Long, title: String, message: String)
}
