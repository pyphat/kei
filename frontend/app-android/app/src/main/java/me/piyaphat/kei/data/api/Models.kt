package me.piyaphat.kei.data.api

data class ServerInfo(val name: String, val hostname: String, val version: String, val api: Int, val auth: String)
data class Connection(val baseUrl: String, val deviceId: Long, val deviceName: String)
sealed interface PairCode {
    data class Pin(val value: String) : PairCode
    data class Secret(val value: String) : PairCode
}
data class PairTarget(val baseUrl: String, val secret: String)
data class SeriesProgress(val chapterId: Long, val page: Int, val percent: Double)
data class Series(
    val id: Long, val title: String, val author: String, val tags: List<String>,
    val coverUrl: String?, val chapterCount: Int, val pageCount: Int,
    val updated: Double, val progress: SeriesProgress?
)
data class ChapterSummary(
    val id: Long, val number: Double, val title: String, val pageCount: Int,
    val presentCount: Int, val processedCount: Int, val translatedCount: Int, val state: String
)
data class SeriesDetail(val series: Series, val chapters: List<ChapterSummary>)
data class ContinueItem(
    val seriesId: Long, val seriesTitle: String, val coverUrl: String?, val chapterId: Long,
    val chapterTitle: String, val chapterNumber: Double, val page: Int, val percent: Double, val ts: Double
)
data class Job(
    val chapterId: Long, val seriesId: Long, val seriesTitle: String, val chapterNumber: Double,
    val chapterTitle: String, val pageCount: Int, val presentCount: Int, val processedCount: Int,
    val translatedCount: Int, val state: String, val failed: List<Pair<Int, String>>,
    val current: Pair<Int, String?>?
)

sealed interface KeiEvent {
    data object Library : KeiEvent
    data class Page(val chapterId: Long, val page: Int, val status: String, val stage: String?) : KeiEvent
    data class Chapter(val chapterId: Long, val state: String) : KeiEvent
    data class Progress(
        val seriesId: Long, val chapterId: Long, val page: Int, val percent: Double,
        val deviceId: String, val ts: Double
    ) : KeiEvent
    data class Usage(val requestsToday: Int) : KeiEvent
    data class Error(val message: String) : KeiEvent
    data object Devices : KeiEvent
    data object Reconnected : KeiEvent
}

enum class Kind { Offline, Unauthorized, NotFound, Server, Unknown }
class KeiException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause)

interface ServerRepository {
    val connection: kotlinx.coroutines.flow.StateFlow<Connection?>
    suspend fun probe(url: String): ServerInfo
    suspend fun pair(url: String, code: PairCode, deviceName: String)
    suspend fun disconnect()
    fun parsePairUri(raw: String): PairTarget?
}

interface EventStream {
    val events: kotlinx.coroutines.flow.SharedFlow<KeiEvent>
}

interface LibraryRepository {
    fun series(): kotlinx.coroutines.flow.Flow<List<Series>>
    fun continueReading(): kotlinx.coroutines.flow.Flow<List<ContinueItem>>
    fun seriesDetail(id: Long): kotlinx.coroutines.flow.Flow<SeriesDetail?>
    fun activeJobs(): kotlinx.coroutines.flow.Flow<List<Job>>
    suspend fun refresh()
    suspend fun refreshSeries(id: Long)
    suspend fun updateSeries(id: Long, title: String?, author: String?, tags: List<String>?)
    suspend fun deleteSeries(id: Long)
    suspend fun updateChapter(id: Long, title: String?, number: Double?)
    suspend fun deleteChapter(id: Long)
    suspend fun setCoverFromPage(seriesId: Long, chapterId: Long, page: Int)
    suspend fun uploadCover(seriesId: Long, jpegBytes: ByteArray)
    suspend fun translate(chapterId: Long, action: String)
}
