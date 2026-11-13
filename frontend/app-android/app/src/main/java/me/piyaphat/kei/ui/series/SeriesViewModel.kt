package me.piyaphat.kei.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.piyaphat.kei.data.api.Job
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.KeiException
import me.piyaphat.kei.data.api.LibraryRepository
import me.piyaphat.kei.data.api.SeriesDetail
import javax.inject.Inject

data class SeriesState(val detail: SeriesDetail? = null, val jobs: List<Job> = emptyList(), val error: Boolean = false)

@HiltViewModel
class SeriesViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SeriesState())
    val state = mutable.asStateFlow()
    private val mutableEvents = MutableSharedFlow<Kind>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    fun load(id: Long) {
        viewModelScope.launch {
            launch {
                try {
                    repository.seriesDetail(id).collect { mutable.value = mutable.value.copy(detail = it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
                }
            }
            launch {
                try {
                    repository.activeJobs().collect { mutable.value = mutable.value.copy(jobs = it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
                }
            }
            try {
                repository.refreshSeries(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = true)
                mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
            }
        }
    }
    fun translate(id: Long) = action { repository.translate(id, "start") }
    fun translateAll(id: Long, pageCounts: List<Pair<Long, Pair<Int, Int>>>, done: (Int) -> Unit) {
        viewModelScope.launch {
            try {
                val todo = pageCounts.filter { it.second.second < it.second.first }
                todo.forEach { repository.translate(it.first, "start") }
                done(todo.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
            }
        }
    }
    fun renameChapter(id: Long, title: String, number: Double?) =
        action { repository.updateChapter(id, title, number) }
    fun deleteChapter(id: Long) = action { repository.deleteChapter(id) }
    fun metadata(id: Long, title: String?, author: String, tags: List<String>) =
        action { repository.updateSeries(id, title, author, tags) }
    fun coverFromPage(seriesId: Long, chapterId: Long, page: Int) =
        action { repository.setCoverFromPage(seriesId, chapterId, page) }
    fun uploadCover(seriesId: Long, bytes: ByteArray) =
        action { repository.uploadCover(seriesId, bytes) }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
            }
        }
    }
}
