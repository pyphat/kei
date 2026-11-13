package me.piyaphat.kei.data.impl.repo

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.piyaphat.kei.data.api.DeviceRepository
import me.piyaphat.kei.data.api.KeiException
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.ReaderBlock
import me.piyaphat.kei.data.api.ReaderChapter
import me.piyaphat.kei.data.api.ReaderDevice
import me.piyaphat.kei.data.api.ReaderPage
import me.piyaphat.kei.data.api.ReaderProgress
import me.piyaphat.kei.data.api.ReaderRepository
import me.piyaphat.kei.data.api.ReaderSearchHit
import me.piyaphat.kei.data.impl.network.ApiFactory
import me.piyaphat.kei.data.impl.network.ReaderFocusDto
import me.piyaphat.kei.data.impl.network.ReaderProgressWriteDto
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReaderRepositoryImpl @Inject constructor(
    private val api: ApiFactory
) : ReaderRepository, DeviceRepository {
    private val chapters = MutableStateFlow<Map<Long, ReaderChapter>>(emptyMap())

    override fun chapter(id: Long): Flow<ReaderChapter?> = chapters.map { it[id] }

    override suspend fun refresh(id: Long) = guarded {
        val result = api.service.readerChapter(id)
        chapters.update { current ->
            current + (id to ReaderChapter(result.id, result.series_id, result.number, result.title,
                result.state, result.pages.map { ReaderPage(it.idx, it.width, it.height, it.status, it.rev) }))
        }
    }

    override suspend fun focus(id: Long, page: Int) = guarded {
        api.service.focusChapter(id, ReaderFocusDto(page))
        Unit
    }

    override suspend fun saveProgress(
        seriesId: Long, chapterId: Long, page: Int, percent: Double, deviceId: String
    ) = guarded {
        api.service.saveReaderProgress(seriesId, ReaderProgressWriteDto(
            chapterId, page, percent, deviceId, System.currentTimeMillis() / 1000.0
        ))
        Unit
    }

    override suspend fun progress(seriesId: Long): ReaderProgress? = guarded {
        api.service.readerProgress(seriesId)?.let {
            ReaderProgress(it.series_id, it.chapter_id, it.page, it.percent, it.device_id, it.ts)
        }
    }

    override suspend fun search(chapterId: Long, query: String): List<ReaderSearchHit> = guarded {
        api.service.searchChapter(chapterId, query).map {
            ReaderSearchHit(it.idx, it.block_id, it.source, it.translation)
        }
    }

    override suspend fun blocks(chapterId: Long, page: Int): List<ReaderBlock> = guarded {
        api.service.pageBlocks(chapterId, page).blocks.map {
            ReaderBlock(it.id, it.source, it.translation)
        }
    }

    override suspend fun reprocess(chapterId: Long, page: Int) = guarded {
        api.service.reprocessPage(chapterId, page)
        Unit
    }

    override suspend fun setCover(seriesId: Long, chapterId: Long, page: Int) = guarded {
        api.service.coverFromPage(seriesId, me.piyaphat.kei.data.impl.network.CoverPageDto(chapterId, page))
        Unit
    }

    override fun pageUrl(chapterId: Long, page: Int, view: String, revision: Long, width: Int): String =
        "http://kei.invalid/api/chapters/$chapterId/pages/$page/image" +
            "?view=$view&v=$revision&w=${width.coerceIn(64, 2048)}"

    override suspend fun devices(): List<ReaderDevice> = guarded {
        api.service.devices().map { ReaderDevice(it.id, it.name, it.current) }
    }

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
    }
}
