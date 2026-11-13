package me.piyaphat.kei.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.piyaphat.kei.data.api.ContinueItem
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.KeiException
import me.piyaphat.kei.data.api.LibraryRepository
import me.piyaphat.kei.data.api.Series
import javax.inject.Inject

enum class LibrarySort { Recent, Title, Progress }
data class LibraryState(
    val series: List<Series> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val translating: Set<Long> = emptySet(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.Recent,
    val error: Boolean = false,
    val refreshing: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    savedState: SavedStateHandle
) : ViewModel() {
    private val query = MutableStateFlow(savedState["query"] ?: "")
    private val sort = MutableStateFlow(savedState["sort"] ?: LibrarySort.Recent.name)
    private val savedState = savedState
    private val hidden = MutableStateFlow(emptySet<Long>())
    private val error = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val mutableEvents = MutableSharedFlow<Kind>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    private val base = combine(repository.series(), repository.continueReading(), repository.activeJobs(),
        query, sort) { series, cont, jobs, q, s ->
        val search = q.trim().lowercase()
        val visible = series.filter { search.isEmpty()
            || it.title.lowercase().contains(search) || it.author.lowercase().contains(search)
            || it.tags.any { tag -> tag.lowercase().contains(search) } }
        val order = when (s) {
            LibrarySort.Title.name -> visible.sortedBy { it.title.lowercase() }
            LibrarySort.Progress.name -> visible.sortedByDescending { it.progress?.percent ?: 0.0 }
            else -> visible.sortedByDescending { it.updated }
        }
        LibraryState(order, cont.take(5), jobs.map { it.seriesId }.toSet(), q,
            runCatching { LibrarySort.valueOf(s) }.getOrDefault(LibrarySort.Recent))
    }
    val state = combine(base, hidden, error, refreshing) { value, hiddenIds, failed, busy ->
        value.copy(series = value.series.filterNot { it.id in hiddenIds }, error = failed, refreshing = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    init { refresh() }
    fun search(value: String) { query.value = value; savedState["query"] = value }
    fun sort(value: LibrarySort) { sort.value = value.name; savedState["sort"] = value.name }
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refresh()
                error.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = true
                mutableEvents.tryEmit((e as? KeiException)?.kind ?: Kind.Unknown)
            } finally {
                refreshing.value = false
            }
        }
    }
    suspend fun deleteWithUndo(id: Long, waitForUndo: suspend () -> Boolean) {
        hidden.update { it + id }
        val undo = try {
            waitForUndo()
        } catch (e: CancellationException) {
            hidden.update { it - id }
            throw e
        }
        if (undo) hidden.update { it - id }
        else try {
            repository.deleteSeries(id)
        } catch (e: CancellationException) {
            hidden.update { it - id }
            throw e
        } catch (e: Exception) {
            hidden.update { it - id }
            mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
        }
    }
    fun rename(id: Long, title: String) {
        viewModelScope.launch {
            try {
                repository.updateSeries(id, title, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableEvents.emit((e as? KeiException)?.kind ?: Kind.Unknown)
            }
        }
    }
}
