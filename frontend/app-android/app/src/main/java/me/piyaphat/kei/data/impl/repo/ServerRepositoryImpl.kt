package me.piyaphat.kei.data.impl.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.StateFlow
import me.piyaphat.kei.data.api.Connection
import me.piyaphat.kei.data.api.KeiException
import me.piyaphat.kei.data.api.Kind
import me.piyaphat.kei.data.api.PairCode
import me.piyaphat.kei.data.api.PairTarget
import me.piyaphat.kei.data.api.ServerInfo
import me.piyaphat.kei.data.api.ServerRepository
import me.piyaphat.kei.data.api.parsePairUri as decodePairUri
import me.piyaphat.kei.data.impl.network.ApiFactory
import me.piyaphat.kei.data.impl.network.PairRequest
import me.piyaphat.kei.data.impl.network.SessionState
import coil3.ImageLoader
import me.piyaphat.kei.data.impl.db.KeiDatabase
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val api: ApiFactory,
    private val session: SessionState,
    private val imageLoader: ImageLoader,
    private val db: KeiDatabase
) : ServerRepository {
    override val connection: StateFlow<Connection?> = session.connectionFlow

    override suspend fun probe(url: String): ServerInfo = safely {
        session.baseUrl = url.trimEnd('/')
        api.service.server().let { ServerInfo(it.name, it.hostname, it.version, it.api, it.auth) }
    }

    override suspend fun pair(url: String, code: PairCode, deviceName: String) = safely {
        session.baseUrl = url.trimEnd('/')
        val body = when (code) {
            is PairCode.Pin -> PairRequest(deviceName, pin = code.value)
            is PairCode.Secret -> PairRequest(deviceName, secret = code.value)
        }
        val response = api.service.pair(body)
        session.save(Connection(url.trimEnd('/'), response.device_id, response.name), response.token)
    }

    override suspend fun disconnect() = safely {
        session.clear()
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        db.withTransaction {
            db.series().clear()
            db.chapters().deleteAll()
            db.continueItems().clear()
            db.jobs().clear()
        }
    }

    override fun parsePairUri(raw: String): PairTarget? = decodePairUri(raw)

    private suspend fun <T> safely(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val kind = when (e.code()) {
            401 -> Kind.Unauthorized
            404 -> Kind.NotFound
            in 500..599 -> Kind.Server
            else -> Kind.Unknown
        }
        throw KeiException(kind,
            if (e.code() == 403) "invalid or expired code" else e.message(), e)
    } catch (e: IOException) {
        throw KeiException(Kind.Offline, e.message ?: "offline", e)
    } catch (e: Exception) {
        if (e is KeiException) throw e
        throw KeiException(Kind.Unknown, e.message ?: "unknown error", e)
    }
}
