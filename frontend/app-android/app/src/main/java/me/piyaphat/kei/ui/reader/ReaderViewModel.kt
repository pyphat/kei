package me.piyaphat.kei.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.piyaphat.kei.core.api.Progress
import me.piyaphat.kei.core.api.ProgressMerger
import me.piyaphat.kei.data.api.*
import javax.inject.Inject

data class ReaderState(
    val chapter: ReaderChapter? = null,
    val series: SeriesDetail? = null,
    val page: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val controlsVisible: Boolean = true,
    val searchHits: List<ReaderSearchHit> = emptyList(),
    val query: String = "",
    val remoteProgress: ReaderProgress? = null,
    val error: String? = null
)

data class ReaderRemoteJump(val progress: ReaderProgress, val deviceName: String?)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val reader: ReaderRepository,
    private val prefs: ReaderPrefs,
    private val library: LibraryRepository,
    private val devices: DeviceRepository,
    private val server: ServerRepository,
    private val events: EventStream,
    private val merger: ProgressMerger,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val mutable = MutableStateFlow(ReaderState())
    val state = mutable.asStateFlow()
    private val jumps = MutableSharedFlow<ReaderRemoteJump>(extraBufferCapacity = 1)
    val remoteJumps = jumps.asSharedFlow()
    private var progressJob: Job? = null
    private var focusJob: Job? = null
    private var progressEventsJob: Job? = null
    private var chapterEventsJob: Job? = null
    private var chapterId = -1L
    private var localProgress: Progress? = null
    private var seriesJob: Job? = null
    private var prefsJob: Job? = null

    fun open(id: Long, routePage: Int?) {
        if (chapterId == id && mutable.value.chapter != null) return
        chapterId = id
        chapterEventsJob?.cancel()
        chapterEventsJob = viewModelScope.launch {
            events.events.collect { event ->
                val changedChapter = when (event) {
                    is KeiEvent.Page -> event.chapterId
                    is KeiEvent.Chapter -> event.chapterId
                    else -> null
                }
                if (changedChapter == id) runCatching { reader.refresh(id) }
            }
        }
        viewModelScope.launch {
            try {
                reader.refresh(id)
                val chapter = reader.chapter(id).filterNotNull().first()
                val progress = reader.progress(chapter.seriesId)
                val page = routePage ?: saved.get<Int>("page")
                    ?: initialReaderPage(null, progress?.chapterId, id, progress?.page)
                    .coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
                saved["page"] = page
                val localDevice = server.connection.value?.deviceId?.toString().orEmpty()
                localProgress = Progress(chapter.seriesId, chapter.id, page,
                    readerProgressPercent(page, chapter.pages.size), localDevice,
                    System.currentTimeMillis() / 1000.0)
                mutable.update { it.copy(chapter = chapter, page = page, error = null,
                    controlsVisible = saved["controls"] ?: true) }
                seriesJob?.cancel()
                seriesJob = launch {
                    library.seriesDetail(chapter.seriesId).collect { detail ->
                        mutable.update { it.copy(series = detail) }
                    }
                }
                prefsJob?.cancel()
                prefsJob = launch {
                    prefs.settings(chapter.seriesId).collect { setting ->
                        mutable.update { it.copy(settings = setting) }
                    }
                }
                monitorProgress(chapter.seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Can't load chapter") }
            }
        }
        viewModelScope.launch {
            reader.chapter(id).filterNotNull().collect { updated ->
                mutable.update { it.copy(chapter = updated, page = it.page.coerceIn(0, (updated.pages.size - 1).coerceAtLeast(0))) }
            }
        }
    }

    fun setPage(page: Int, immediate: Boolean = false) {
        val chapter = mutable.value.chapter ?: return
        val bounded = page.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0))
        if (bounded == mutable.value.page) return
        mutable.update { it.copy(page = bounded) }
        saved["page"] = bounded
        val deviceId = server.connection.value?.deviceId?.toString().orEmpty()
        localProgress = Progress(chapter.seriesId, chapter.id, bounded,
            readerProgressPercent(bounded, chapter.pages.size), deviceId, System.currentTimeMillis() / 1000.0)
        focusJob?.cancel()
        focusJob = viewModelScope.launch { delay(300); runCatching { reader.focus(chapter.id, bounded) } }
        progressJob?.cancel()
        val save = suspend {
            val deviceId = server.connection.value?.deviceId?.toString()
            if (deviceId != null) {
                runCatching {
                    reader.saveProgress(chapter.seriesId, chapter.id, bounded,
                        readerProgressPercent(bounded, chapter.pages.size), deviceId)
                }
            }
        }
        if (immediate) viewModelScope.launch { save() }
        else progressJob = viewModelScope.launch { delay(1_000); save() }
    }

    fun saveNow() {
        val chapter = mutable.value.chapter ?: return
        val deviceId = server.connection.value?.deviceId?.toString() ?: return
        progressJob?.cancel()
        // Runs while the reader closes, when viewModelScope is being cancelled.
        viewModelScope.launch(NonCancellable) {
            runCatching {
                reader.saveProgress(chapter.seriesId, chapter.id, mutable.value.page,
                    readerProgressPercent(mutable.value.page, chapter.pages.size), deviceId)
            }
        }
    }
    fun toggleControls() = mutable.update {
        saved["controls"] = !it.controlsVisible
        it.copy(controlsVisible = !it.controlsVisible)
    }
    fun revealControls() = mutable.update { saved["controls"] = true; it.copy(controlsVisible = true) }
    fun hideControls() = mutable.update { saved["controls"] = false; it.copy(controlsVisible = false) }
    fun imageUrl(page: ReaderPage, view: String, width: Int): String =
        reader.pageUrl(chapterId, page.index, view, page.revision, width)
    fun setSettings(settings: ReaderSettings) {
        mutable.update { it.copy(settings = settings) }
        viewModelScope.launch { prefs.save(settings) }
    }
    fun setSeriesSettings(mode: String, rtl: Boolean) {
        val sid = mutable.value.chapter?.seriesId ?: return
        mutable.update { it.copy(settings = it.settings.copy(mode = mode, rtl = rtl)) }
        viewModelScope.launch { prefs.saveSeries(sid, mode, rtl) }
    }
    fun search(query: String) {
        mutable.update { it.copy(query = query) }
        viewModelScope.launch {
            runCatching { reader.search(chapterId, query) }.onSuccess { hits ->
                mutable.update { it.copy(searchHits = hits) }
            }
        }
    }
    fun reprocess() = action { reader.reprocess(chapterId, mutable.value.chapter?.pages?.getOrNull(mutable.value.page)?.index ?: 0) }
    fun translate(action: String) = action {
        library.translate(chapterId, action)
        reader.refresh(chapterId)
    }
    fun setCover() = action {
        val chapter = mutable.value.chapter ?: return@action
        reader.setCover(chapter.seriesId, chapter.id, mutable.value.chapter?.pages?.getOrNull(mutable.value.page)?.index ?: 0)
    }

    private fun monitorProgress(seriesId: Long) {
        progressEventsJob?.cancel()
        progressEventsJob = viewModelScope.launch {
            val deviceList = runCatching { devices.devices() }.getOrDefault(emptyList())
            val localDevice = deviceList.firstOrNull { it.current }?.id?.toString()
                ?: server.connection.value?.deviceId?.toString().orEmpty()
            val order = runCatching {
                library.seriesDetail(seriesId).filterNotNull().first().chapters.map { it.id }
            }.getOrDefault(emptyList())
            events.events.collect { event ->
                if (event is KeiEvent.Progress && event.seriesId == seriesId && event.deviceId != localDevice) {
                    val mine = localProgress?.takeIf { it.deviceId == localDevice }
                    val remote = Progress(event.seriesId, event.chapterId, event.page, event.percent,
                        event.deviceId, event.ts)
                    if (merger.shouldOfferJump(mine, remote, order)) {
                        val deviceName = deviceList.firstOrNull { it.id.toString() == event.deviceId }?.name
                        jumps.emit(ReaderRemoteJump(ReaderProgress(event.seriesId, event.chapterId, event.page,
                            event.percent, event.deviceId, event.ts), deviceName))
                    }
                }
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Action failed") } }
        }
    }
}
