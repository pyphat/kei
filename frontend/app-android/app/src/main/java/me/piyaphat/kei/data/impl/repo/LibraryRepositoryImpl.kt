package me.piyaphat.kei.data.impl.repo

import androidx.room.withTransaction
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.piyaphat.kei.data.api.*
import me.piyaphat.kei.data.impl.db.*
import me.piyaphat.kei.data.impl.network.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(FlowPreview::class)
class LibraryRepositoryImpl @Inject constructor(
    private val db: KeiDatabase,
    private val api: ApiFactory,
    stream: EventStream
) : LibraryRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            stream.events.collect { event ->
                when (event) {
                    KeiEvent.Library, KeiEvent.Reconnected -> quietly("library refresh") { refresh() }
                    is KeiEvent.Progress -> quietly("progress refresh") { refreshContinue() }
                    else -> Unit
                }
            }
        }
        scope.launch {
            stream.events.mapNotNull {
                when (it) {
                    is KeiEvent.Page -> it.chapterId
                    is KeiEvent.Chapter -> it.chapterId
                    else -> null
                }
            }.debounce(1_000).collect { id ->
                var seriesId: Long? = null
                quietly("chapter lookup") { seriesId = db.chapters().get(id)?.seriesId }
                seriesId?.let { quietly("series refresh") { refreshSeries(it) } }
                quietly("jobs refresh") { refreshJobs() }
            }
        }
    }

    override fun series(): Flow<List<Series>> = db.series().observe().map { rows -> rows.map(::series) }
    override fun continueReading(): Flow<List<ContinueItem>> =
        db.continueItems().observe().map { rows -> rows.map(::continueItem) }
    override fun activeJobs(): Flow<List<Job>> = db.jobs().observe().map { rows -> rows.map(::job) }
    override fun seriesDetail(id: Long): Flow<SeriesDetail?> =
        combine(db.series().observe(id), db.chapters().observe(id)) { row, chapters ->
            row?.let { SeriesDetail(series(it), chapters.map(::chapter)) }
        }

    override suspend fun refresh() = guarded {
        // shortcut: N+1 detail requests per refresh, add a bulk endpoint if libraries get large
        val details = api.service.series().map { api.service.series(it.id) }
        val jobs = api.service.jobs()
        val continueItems = api.service.continueReading()
        db.withTransaction {
            val ids = details.map { it.id }
            if (ids.isEmpty()) {
                db.series().clear()
                db.chapters().deleteAll()
            } else {
                db.series().deleteMissing(ids)
                db.chapters().deleteMissing(ids)
            }
            details.forEach { detail ->
                db.series().put(listOf(entity(detail)))
                db.chapters().deleteSeries(detail.id)
                db.chapters().put(detail.chapters.map { chapterEntity(detail.id, it) })
            }
            db.jobs().clear()
            db.jobs().put(jobs.map(::jobEntity))
            db.continueItems().clear()
            db.continueItems().put(continueItems.map(::continueEntity))
        }
    }

    override suspend fun refreshSeries(id: Long) = guarded {
        val detail = api.service.series(id)
        db.withTransaction {
            db.series().put(listOf(entity(detail)))
            db.chapters().deleteSeries(id)
            db.chapters().put(detail.chapters.map { chapterEntity(id, it) })
        }
    }

    override suspend fun updateSeries(id: Long, title: String?, author: String?, tags: List<String>?) = guarded {
        api.service.patchSeries(id, NameDto(title, author, tags))
        refreshSeries(id)
    }

    override suspend fun deleteSeries(id: Long) = guarded {
        api.service.deleteSeries(id)
        db.withTransaction {
            db.series().delete(id)
            db.chapters().deleteSeries(id)
            db.continueItems().deleteSeries(id)
            db.jobs().deleteSeries(id)
        }
    }

    override suspend fun updateChapter(id: Long, title: String?, number: Double?) = guarded {
        api.service.patchChapter(id, ChapterPatchDto(title, number))
        db.chapters().get(id)?.let { refreshSeries(it.seriesId) }
        Unit
    }

    override suspend fun deleteChapter(id: Long) = guarded {
        api.service.deleteChapter(id)
        db.chapters().get(id)?.let { row ->
            db.chapters().delete(id)
            refreshSeries(row.seriesId)
            refreshContinue()
            refreshJobs()
        }
        Unit
    }

    override suspend fun setCoverFromPage(seriesId: Long, chapterId: Long, page: Int) = guarded {
        api.service.coverFromPage(seriesId, CoverPageDto(chapterId, page))
        refreshSeries(seriesId)
    }

    override suspend fun uploadCover(seriesId: Long, jpegBytes: ByteArray) = guarded {
        val body = jpegBytes.toRequestBody("image/jpeg".toMediaType())
        api.service.uploadCover(seriesId, MultipartBody.Part.createFormData("file", "cover.jpg", body))
        refreshSeries(seriesId)
    }

    override suspend fun translate(chapterId: Long, action: String) = guarded {
        api.service.translate(chapterId, ActionDto(action))
        db.chapters().get(chapterId)?.let { refreshSeries(it.seriesId) }
        refreshJobs()
    }

    private suspend fun refreshContinue() = guarded {
        db.continueItems().clear()
        db.continueItems().put(api.service.continueReading().map(::continueEntity))
    }

    private suspend fun refreshJobs() = guarded {
        db.jobs().clear()
        db.jobs().put(api.service.jobs().map(::jobEntity))
    }

    private fun entity(s: SeriesDto) = SeriesEntity(s.id, s.title, s.author,
        json.encodeToString(s.tags), s.cover_url, s.chapter_count, s.page_count, s.updated,
        s.progress?.chapter_id, s.progress?.page, s.progress?.percent)
    private fun series(s: SeriesEntity) = Series(s.id, s.title, s.author,
        runCatching { json.decodeFromString<List<String>>(s.tags) }.getOrDefault(emptyList()),
        s.coverUrl, s.chapterCount, s.pageCount, s.updated,
        if (s.progressChapterId == null) null else SeriesProgress(
            s.progressChapterId, s.progressPage ?: 0, s.progressPercent ?: 0.0))
    private fun chapterEntity(id: Long, c: ChapterDto) = ChapterEntity(
        c.id, id, c.number, c.title, c.page_count, c.present_count, c.processed_count,
        c.translated_count, c.state)
    private fun chapter(c: ChapterEntity) = ChapterSummary(c.id, c.number, c.title,
        c.pageCount, c.presentCount, c.processedCount, c.translatedCount, c.state)
    private fun continueEntity(c: ContinueDto) = ContinueEntity(c.series_id, c.series_title,
        c.cover_url, c.chapter_id, c.chapter_title, c.chapter_number, c.page, c.percent, c.ts)
    private fun continueItem(c: ContinueEntity) = ContinueItem(c.seriesId, c.seriesTitle,
        c.coverUrl, c.chapterId, c.chapterTitle, c.chapterNumber, c.page, c.percent, c.ts)
    private fun jobEntity(j: JobDto) = JobEntity(j.chapter_id, j.series_id, j.series_title,
        j.chapter_number, j.chapter_title, j.page_count, j.present_count, j.processed_count,
        j.translated_count, j.state, json.encodeToString(j.failed), j.current?.page, j.current?.stage)
    private fun job(j: JobEntity) = Job(j.chapterId, j.seriesId, j.seriesTitle,
        j.chapterNumber, j.chapterTitle, j.pageCount, j.presentCount, j.processedCount,
        j.translatedCount, j.state,
        runCatching { json.decodeFromString<List<FailedDto>>(j.failed).map { it.page to it.error } }
            .getOrDefault(emptyList()),
        j.currentPage?.let { it to j.currentStage })

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw KeiException(when (e.code()) {
            401 -> Kind.Unauthorized
            404 -> Kind.NotFound
            in 500..599 -> Kind.Server
            else -> Kind.Unknown
        }, e.message(), e)
    } catch (e: IOException) {
        throw KeiException(Kind.Offline, e.message ?: "offline", e)
    } catch (e: Exception) {
        if (e is KeiException) throw e
        throw KeiException(Kind.Unknown, e.message ?: "unknown error", e)
    }

    private suspend fun quietly(operation: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull() ?: return
        if (failure is CancellationException) throw failure
        Log.d("LibraryRepository", "$operation failed: ${(failure as? KeiException)?.kind ?: failure.javaClass.simpleName}")
    }
}
