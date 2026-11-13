package me.piyaphat.kei.ui.upload

import android.net.Uri
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.piyaphat.kei.data.api.LibraryRepository
import me.piyaphat.kei.data.api.Series
import me.piyaphat.kei.R
import me.piyaphat.kei.work.api.SkippedUploadFile
import me.piyaphat.kei.work.api.UploadChapter
import me.piyaphat.kei.work.api.UploadScheduler
import me.piyaphat.kei.work.api.UploadTask
import javax.inject.Inject

data class UploadPreviewChapter(val key: Int, val chapter: UploadChapter, val included: Boolean = true)

data class UploadUiState(
    val scanning: Boolean = false,
    val scannedFiles: Int = 0,
    val preview: ScanResult? = null,
    val chapters: List<UploadPreviewChapter> = emptyList(),
    val series: List<Series> = emptyList(),
    val tasks: List<UploadTask> = emptyList(),
    val targetSeriesId: Long? = null,
    val seriesTitle: String = "",
    val coverPageIndex: Int = 0,
    val heldPermissions: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: UploadScanner,
    private val scheduler: UploadScheduler,
    private val library: LibraryRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(UploadUiState())
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var nextKey = 0

    init {
        viewModelScope.launch {
            try {
                scheduler.reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { state -> state.copy(error = e.message) }
            }
        }
        viewModelScope.launch { library.series().collect { rows -> mutable.update { it.copy(series = rows) } } }
        viewModelScope.launch { scheduler.observe().collect { rows -> mutable.update { it.copy(tasks = rows) } } }
    }

    fun scanFolder(uri: Uri) = scan(listOf(uri)) { onCount -> scanner.folder(uri, onCount) }
    fun scanArchive(uri: Uri) = scan(listOf(uri)) { onCount -> scanner.archive(uri, onCount) }
    fun scanImages(uris: List<Uri>) = scan(uris) { onCount -> scanner.images(uris, onCount) }

    private fun scan(permissions: List<Uri>, block: suspend ((Int) -> Unit) -> ScanResult) {
        mutable.update { it.copy(scanning = true, scannedFiles = 0, error = null, heldPermissions = permissions.map(Uri::toString)) }
        scanJob = viewModelScope.launch {
            try {
                val result = block { count -> mutable.update { it.copy(scannedFiles = count) } }
                nextKey = 0
                mutable.update {
                    it.copy(
                        scanning = false,
                        preview = result,
                        chapters = result.chapters.map { chapter -> UploadPreviewChapter(nextKey++, chapter) },
                        seriesTitle = result.seriesTitle,
                        targetSeriesId = null,
                        coverPageIndex = 0,
                        heldPermissions = emptyList()
                    )
                }
            } catch (e: CancellationException) {
                scanner.releasePermissions(permissions.map(Uri::toString))
                throw e
            } catch (e: Exception) {
                scanner.releasePermissions(permissions.map(Uri::toString))
                mutable.update { it.copy(scanning = false, heldPermissions = emptyList(), error = e.message) }
            }
        }
    }

    fun cancelScan() {
        val held = mutable.value.heldPermissions
        scanJob?.cancel()
        scanner.releasePermissions(held)
        mutable.update { it.copy(scanning = false, heldPermissions = emptyList()) }
    }

    fun setTarget(id: Long?) = mutable.update { state ->
        val offset = if (state.preview?.draftDirectory != null && state.preview.chapters.size == 1)
            state.series.firstOrNull { it.id == id }?.chapterCount ?: 0
        else 0
        state.copy(targetSeriesId = id, chapters = state.chapters.mapIndexed { index, item ->
            val base = state.preview?.chapters?.getOrNull(index)?.number ?: item.chapter.number
            item.copy(chapter = item.chapter.copy(number = base + offset))
        })
    }
    fun setSeriesTitle(title: String) = mutable.update { it.copy(seriesTitle = title) }
    fun setChapterNumber(key: Int, value: String) = updateChapter(key) { chapter ->
        value.toDoubleOrNull()?.let { chapter.copy(number = it) } ?: chapter
    }
    fun setChapterTitle(key: Int, title: String) = updateChapter(key) { it.copy(title = title) }
    fun toggleChapter(key: Int) = mutable.update { state ->
        state.copy(chapters = state.chapters.map { if (it.key == key) it.copy(included = !it.included) else it })
    }
    fun moveChapter(key: Int, delta: Int) = mutable.update { state ->
        val from = state.chapters.indexOfFirst { it.key == key }
        val to = (from + delta).coerceIn(0, state.chapters.lastIndex)
        if (from < 0 || from == to) state else state.copy(chapters = state.chapters.toMutableList().apply {
            add(to, removeAt(from))
        })
    }
    fun chooseCover(index: Int) = mutable.update { it.copy(coverPageIndex = index) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    fun upload() {
        val state = mutable.value
        val scan = state.preview ?: return
        val chapters = state.chapters.filter { it.included }.map { it.chapter }
        if (chapters.isEmpty()) {
            mutable.update { it.copy(error = context.getString(R.string.select_chapter_error)) }
            return
        }
        viewModelScope.launch {
            var taskId: Long? = null
            try {
                val targetTitle = state.series.firstOrNull { it.id == state.targetSeriesId }?.title
                val task = UploadTask(
                    targetSeriesId = state.targetSeriesId,
                    seriesTitle = targetTitle ?: state.seriesTitle.ifBlank { scan.seriesTitle },
                    coverPageIndex = if (state.targetSeriesId == null) state.coverPageIndex else 0,
                    chapters = chapters,
                    permissionUris = scan.permissionUris,
                    skipped = scan.skipped
                )
                taskId = scheduler.create(task)
                val promoted = scanner.promote(scan, taskId)
                scheduler.update(task.copy(id = taskId, chapters = promoted))
                scheduler.enqueue(taskId)
                mutable.update { it.copy(preview = null, chapters = emptyList(), error = null) }
            } catch (e: Exception) {
                if (taskId == null) {
                    scanner.cleanup(scan)
                    scanner.releasePermissions(scan.permissionUris)
                } else scheduler.cancel(taskId)
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun cancelPreview() {
        val scan = mutable.value.preview ?: return
        scanner.cleanup(scan)
        scanner.releasePermissions(scan.permissionUris)
        mutable.update { it.copy(preview = null, chapters = emptyList(), error = null) }
    }

    fun cancelTask(id: Long) = scheduler.cancel(id)
    fun retry(id: Long) {
        val task = mutable.value.tasks.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            scheduler.update(task.copy(state = "queued", done = 0, error = null))
            scheduler.enqueue(id)
        }
    }
    fun removeTask(id: Long) {
        viewModelScope.launch {
            runCatching { scheduler.remove(id) }.onFailure {
                mutable.update { state -> state.copy(error = it.message) }
            }
        }
    }
    fun translateNow(task: UploadTask) {
        viewModelScope.launch {
            task.chapterIds.forEach { library.translate(it, "start") }
            mutable.update { it.copy(message = context.getString(R.string.translation_started)) }
        }
    }

    private fun updateChapter(key: Int, transform: (UploadChapter) -> UploadChapter) =
        mutable.update { state ->
            state.copy(chapters = state.chapters.map {
                if (it.key == key) it.copy(chapter = transform(it.chapter)) else it
            })
        }
}
