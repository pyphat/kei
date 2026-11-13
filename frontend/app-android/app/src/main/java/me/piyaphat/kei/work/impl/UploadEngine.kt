package me.piyaphat.kei.work.impl

import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.piyaphat.kei.core.api.FileHasher
import me.piyaphat.kei.core.api.UploadBatchPlanner
import me.piyaphat.kei.work.api.UploadManifestChapter
import me.piyaphat.kei.work.api.UploadManifestFile
import me.piyaphat.kei.work.api.UploadManifestRequest
import me.piyaphat.kei.work.api.UploadFile
import me.piyaphat.kei.work.api.UploadRemote
import me.piyaphat.kei.work.api.UploadSource
import me.piyaphat.kei.work.api.UploadTask
import me.piyaphat.kei.work.api.UploadTaskStore
import javax.inject.Inject

class UploadEngine @Inject constructor(
    private val store: UploadTaskStore,
    private val source: UploadSource,
    private val hasher: FileHasher,
    private val batches: UploadBatchPlanner,
    private val remote: UploadRemote
) {
    suspend fun run(taskId: Long, progress: suspend (UploadTask) -> Unit = {}): UploadTask {
        var task = store.get(taskId) ?: throw IllegalArgumentException("upload task not found")
        var hashed = 0
        val fileCount = task.chapters.sumOf { it.files.size }
        task = task.copy(state = "hashing", total = fileCount, error = null)
        store.update(task)
        progress(task)
        task.chapters.forEachIndexed { chapterIndex, chapter ->
            chapter.files.forEachIndexed { fileIndex, file ->
                if (file.sha256 == null) {
                    val digest = source.open(file).use(hasher::sha256Hex)
                    val files = task.chapters[chapterIndex].files.toMutableList()
                    files[fileIndex] = file.copy(sha256 = digest)
                    val chapters = task.chapters.toMutableList()
                    chapters[chapterIndex] = chapter.copy(files = files)
                    task = task.copy(chapters = chapters)
                }
                hashed++
                task = task.copy(done = hashed, total = fileCount)
                store.update(task)
                progress(task)
            }
        }

        val manifest = remote.manifest(UploadManifestRequest(
            series_id = task.targetSeriesId,
            series_title = if (task.targetSeriesId == null) task.seriesTitle else null,
            chapters = task.chapters.map { chapter ->
                UploadManifestChapter(chapter.number, chapter.title, chapter.files.mapIndexed { index, file ->
                    UploadManifestFile(index, file.ext, file.size, requireNotNull(file.sha256))
                })
            }
        ))
        task = task.copy(
            targetSeriesId = manifest.series_id,
            seriesId = manifest.series_id,
            chapterIds = manifest.chapters.map { it.chapter_id },
            state = "uploading",
            done = 0,
            total = manifest.chapters.sumOf { it.missing.size }
        )
        store.update(task)
        progress(task)

        val uploadItems = manifest.chapters.flatMapIndexed { chapterIndex, result ->
            result.missing.mapNotNull { index ->
                task.chapters.getOrNull(chapterIndex)?.files?.getOrNull(index)?.let { file ->
                    PageUpload(result.chapter_id, index, chapterIndex, file)
                }
            }
        }
        val total = uploadItems.size
        val lock = Mutex()
        var done = 0
        batches.plan(uploadItems, { it.file.size }, 3, 64L * 1024 * 1024).forEach { batch ->
            coroutineScope {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        remote.putPage(item.chapterId, item.index, item.file) { source.open(item.file) }
                        lock.withLock {
                            done++
                            task = task.copy(done = done, total = total)
                            store.update(task)
                            progress(task)
                        }
                    }
                }.awaitAll()
            }
        }

        val seriesId = task.targetSeriesId
        if (task.coverPageIndex != 0 && seriesId != null) {
            task.chapters.firstOrNull()?.let { chapter ->
                task.chapterIds.firstOrNull()?.let { remote.setCover(seriesId, it, task.coverPageIndex) }
            }
        }
        task = task.copy(state = "done", done = total, total = total, error = null)
        store.update(task)
        progress(task)
        return task
    }

    private data class PageUpload(
        val chapterId: Long,
        val index: Int,
        val chapterIndex: Int,
        val file: UploadFile
    )
}
