package me.piyaphat.kei.data.impl.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.piyaphat.kei.core.api.SseEventParser
import me.piyaphat.kei.data.api.EventStream
import me.piyaphat.kei.data.api.KeiEvent
import okhttp3.Request
import okhttp3.Call
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventStreamImpl private constructor(
    private val api: ApiFactory,
    private val session: SessionState,
    private val parser: SseEventParser,
    lifecycle: Lifecycle?,
    private val initialBackoffMs: Long
) : EventStream, DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableSharedFlow<KeiEvent>(extraBufferCapacity = 64)
    override val events = mutable.asSharedFlow()
    private var job: Job? = null
    @Volatile private var activeCall: Call? = null

    @Inject constructor(api: ApiFactory, session: SessionState, parser: SseEventParser) :
        this(api, session, parser, ProcessLifecycleOwner.get().lifecycle, 1_000L)

    internal constructor(api: ApiFactory, session: SessionState, parser: SseEventParser,
                         initialBackoffMs: Long) :
        this(api, session, parser, null, initialBackoffMs)

    init {
        lifecycle?.addObserver(this)
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            onStart(ProcessLifecycleOwner.get())
        }
    }
    override fun onStart(owner: LifecycleOwner) {
        job = scope.launch {
            session.connectionFlow.collectLatest {
                if (it != null) connect() else activeCall?.cancel()
            }
        }
    }
    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
        activeCall?.cancel()
    }
    internal fun startForTest() {
        job = scope.launch { if (session.connection != null) connect() }
    }
    internal fun stopForTest() {
        job?.cancel()
        activeCall?.cancel()
    }

    private suspend fun connect() {
        var wait = initialBackoffMs
        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) {
                    val call = api.eventsClient.newCall(
                        Request.Builder().url("${API_BASE_URL}api/events").build())
                    activeCall = call
                    val response = call.execute()
                    if (!response.isSuccessful) {
                        response.close()
                        throw IOException("events HTTP ${response.code}")
                    }
                    mutable.emit(KeiEvent.Reconnected)
                    parser.reset()
                    response.body?.source()?.use { source ->
                        while (currentCoroutineContext().isActive) {
                            val line = source.readUtf8Line() ?: break
                            parser.onLine(line) { event -> decodeKeiEvent(event.data)?.let { mutable.tryEmit(it) } }
                        }
                    }
                    activeCall = null
                    throw IOException("event stream closed")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(wait)
                wait = (wait * 2).coerceAtMost(30_000L)
            } finally {
                activeCall?.cancel()
                activeCall = null
            }
        }
    }

}

internal fun decodeKeiEvent(data: String): KeiEvent? = runCatching {
    val o = Json.parseToJsonElement(data).jsonObject
    fun long(key: String) = o.getValue(key).jsonPrimitive.content.toLong()
    fun int(key: String) = o.getValue(key).jsonPrimitive.content.toInt()
    fun text(key: String) = o.getValue(key).jsonPrimitive.content
    when (text("type")) {
        "library" -> KeiEvent.Library
        "devices" -> KeiEvent.Devices
        "page" -> KeiEvent.Page(long("chapter_id"), int("page"), text("status"),
            o["stage"]?.jsonPrimitive?.content)
        "chapter" -> KeiEvent.Chapter(long("chapter_id"), text("state"))
        "usage" -> KeiEvent.Usage(int("requests_today"))
        "error" -> KeiEvent.Error(text("message"))
        "progress" -> KeiEvent.Progress(long("series_id"), long("chapter_id"), int("page"),
            text("percent").toDouble(), text("device_id"), text("ts").toDouble())
        else -> null
    }
}.getOrNull()
