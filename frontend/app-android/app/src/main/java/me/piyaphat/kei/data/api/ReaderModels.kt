package me.piyaphat.kei.data.api

import kotlinx.coroutines.flow.Flow

data class ReaderPage(val index: Int, val width: Int, val height: Int, val status: String, val revision: Long)
data class ReaderChapter(
    val id: Long, val seriesId: Long, val number: Double, val title: String,
    val state: String, val pages: List<ReaderPage>
)
data class ReaderBlock(val id: String, val source: String, val translation: String)
data class ReaderSearchHit(val index: Int, val blockId: String, val source: String, val translation: String)
data class ReaderProgress(
    val seriesId: Long, val chapterId: Long, val page: Int, val percent: Double,
    val deviceId: String, val timestamp: Double
)
data class ReaderDevice(val id: Long, val name: String, val current: Boolean)

data class ReaderSettings(
    val mode: String = "paged",
    val rtl: Boolean = true,
    val view: String = "translated",
    val fit: String = "smart",
    val background: String = "black",
    val keepScreenOn: Boolean = false,
    val brightness: Float? = null,
    val invertTapZones: Boolean = false,
    val showPageNumber: Boolean = false
)

interface ReaderRepository {
    fun chapter(id: Long): Flow<ReaderChapter?>
    suspend fun refresh(id: Long)
    suspend fun focus(id: Long, page: Int)
    suspend fun saveProgress(seriesId: Long, chapterId: Long, page: Int, percent: Double, deviceId: String)
    suspend fun progress(seriesId: Long): ReaderProgress?
    suspend fun search(chapterId: Long, query: String): List<ReaderSearchHit>
    suspend fun blocks(chapterId: Long, page: Int): List<ReaderBlock>
    suspend fun reprocess(chapterId: Long, page: Int)
    suspend fun setCover(seriesId: Long, chapterId: Long, page: Int)
    fun pageUrl(chapterId: Long, page: Int, view: String, revision: Long, width: Int): String
}

interface DeviceRepository {
    suspend fun devices(): List<ReaderDevice>
}

interface ReaderPrefs {
    fun settings(seriesId: Long): Flow<ReaderSettings>
    suspend fun save(settings: ReaderSettings)
    suspend fun saveSeries(seriesId: Long, mode: String, rtl: Boolean)
}
