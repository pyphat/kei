package me.piyaphat.kei.data.impl.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import me.piyaphat.kei.work.api.UploadTask
import me.piyaphat.kei.work.api.UploadTaskRepository
import me.piyaphat.kei.work.api.UploadTaskStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomUploadTaskStore @Inject constructor(private val db: KeiDatabase) : UploadTaskRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(id: Long): UploadTask? = db.uploadTasks().get(id)?.let(::decode)
    override suspend fun update(task: UploadTask) = db.uploadTasks().update(encode(task))
    override suspend fun create(task: UploadTask): Long =
        db.uploadTasks().insert(encode(task.copy(state = "queued", done = 0, error = null)))
    override suspend fun all(): List<UploadTask> = db.uploadTasks().all().map(::decode)
    override suspend fun delete(id: Long) = db.uploadTasks().delete(id)
    override fun observe() = db.uploadTasks().observe().map { rows -> rows.map(::decode) }

    fun encode(task: UploadTask) = UploadTaskEntity(
        id = task.id,
        targetSeriesId = task.targetSeriesId,
        seriesTitle = task.seriesTitle,
        coverPageIndex = task.coverPageIndex,
        chapters = json.encodeToString(task.chapters),
        permissionUris = json.encodeToString(task.permissionUris),
        skipped = json.encodeToString(task.skipped),
        state = task.state,
        done = task.done,
        total = task.total,
        seriesId = task.seriesId,
        chapterIds = json.encodeToString(task.chapterIds),
        error = task.error
    )

    fun decode(entity: UploadTaskEntity) = UploadTask(
        entity.id, entity.targetSeriesId, entity.seriesTitle, entity.coverPageIndex,
        json.decodeFromString(entity.chapters), json.decodeFromString(entity.permissionUris),
        json.decodeFromString(entity.skipped), entity.state, entity.done, entity.total, entity.seriesId,
        json.decodeFromString(entity.chapterIds), entity.error
    )
}
