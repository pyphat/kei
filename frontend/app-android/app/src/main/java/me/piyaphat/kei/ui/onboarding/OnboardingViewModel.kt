package me.piyaphat.kei.ui.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.piyaphat.kei.data.api.*
import me.piyaphat.kei.discovery.api.ServerDiscovery
import me.piyaphat.kei.discovery.api.servers
import java.net.URI
import javax.inject.Inject

enum class OnboardingPage { Welcome, Find, Pair, Scan }
data class OnboardingState(
    val page: OnboardingPage = OnboardingPage.Welcome,
    val servers: List<me.piyaphat.kei.discovery.api.DiscoveredServer> = emptyList(),
    val searching: Boolean = false,
    val showHint: Boolean = false,
    val manual: Boolean = false,
    val address: String = "",
    val url: String = "",
    val info: ServerInfo? = null,
    val pin: String = "",
    val deviceName: String = Build.MODEL.orEmpty(),
    val error: Kind? = null,
    val pairError: Boolean = false,
    val connected: Boolean = false
)
sealed interface OnboardingEvent {
    data class Failure(val kind: Kind) : OnboardingEvent
    data object BadCode : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val servers: ServerDiscovery,
    private val repository: ServerRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingState())
    val state = mutable.asStateFlow()
    private val mutableEvents = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    private var discoveryJob: Job? = null

    fun find() {
        discoveryJob?.cancel()
        mutable.value = mutable.value.copy(page = OnboardingPage.Find, searching = true, showHint = false)
        discoveryJob = viewModelScope.launch {
            launch {
                delay(8_000)
                if (mutable.value.servers.isEmpty()) mutable.value = mutable.value.copy(showHint = true)
            }
            try {
                servers.servers().collect {
                    mutable.value = mutable.value.copy(servers = it,
                        showHint = if (it.isEmpty()) mutable.value.showHint else false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableEvents.tryEmit(OnboardingEvent.Failure((e as? KeiException)?.kind ?: Kind.Unknown))
            }
        }
    }

    fun manual() { mutable.value = mutable.value.copy(manual = true, error = null) }
    fun address(value: String) { mutable.value = mutable.value.copy(address = value, error = null) }
    fun name(value: String) { mutable.value = mutable.value.copy(deviceName = value) }

    fun connect(raw: String = mutable.value.address) {
        discoveryJob?.cancel()
        val url = normalizeUrl(raw) ?: run {
            mutable.value = mutable.value.copy(error = Kind.Unknown)
            mutableEvents.tryEmit(OnboardingEvent.Failure(Kind.Unknown))
            return
        }
        viewModelScope.launch {
            mutable.value = mutable.value.copy(error = null)
            try {
                val info = repository.probe(url)
                mutable.value = mutable.value.copy(page = OnboardingPage.Pair, url = url, info = info, pin = "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = (e as? KeiException)?.kind ?: Kind.Offline
                mutable.value = mutable.value.copy(error = kind)
                mutableEvents.tryEmit(OnboardingEvent.Failure(kind))
            }
        }
    }

    fun pin(value: String) {
        val pin = value.filter(Char::isDigit).take(6)
        mutable.value = mutable.value.copy(pin = pin, pairError = false)
        if (pin.length == 6) pair(PairCode.Pin(pin))
    }

    fun pairSecret(raw: String) {
        discoveryJob?.cancel()
        val target = repository.parsePairUri(raw) ?: run {
            mutable.value = mutable.value.copy(error = Kind.Unknown, page = OnboardingPage.Scan)
            mutableEvents.tryEmit(OnboardingEvent.Failure(Kind.Unknown))
            return
        }
        viewModelScope.launch {
            mutable.value = mutable.value.copy(url = target.baseUrl, error = null)
            try {
                val info = repository.probe(target.baseUrl)
                mutable.value = mutable.value.copy(info = info, page = OnboardingPage.Pair)
                pair(PairCode.Secret(target.secret))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(error = (e as? KeiException)?.kind ?: Kind.Offline)
                mutableEvents.tryEmit(OnboardingEvent.Failure(mutable.value.error ?: Kind.Offline))
            }
        }
    }

    private fun pair(code: PairCode) {
        val current = mutable.value
        viewModelScope.launch {
            try {
                repository.pair(current.url, code, current.deviceName)
                mutable.value = mutable.value.copy(connected = true, pairError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = (e as? KeiException)?.kind ?: Kind.Unknown
                val badCode = (e as? KeiException)?.message?.contains("invalid or expired code") == true
                mutable.value = mutable.value.copy(error = kind, pairError = badCode)
                mutableEvents.tryEmit(if (badCode) OnboardingEvent.BadCode else OnboardingEvent.Failure(kind))
            }
        }
    }

    private fun normalizeUrl(value: String): String? = runCatching {
        val raw = value.trim().let { if ("://" in it) it else "http://$it" }
        val uri = URI(raw)
        val host = uri.host?.takeIf(String::isNotBlank) ?: return null
        val port = if (uri.port < 0) 8000 else uri.port
        "http://$host:$port"
    }.getOrNull()

    fun scan() { discoveryJob?.cancel(); mutable.value = mutable.value.copy(page = OnboardingPage.Scan, error = null) }
    fun back() {
        if (mutable.value.url.isBlank()) find()
        else mutable.value = mutable.value.copy(page = OnboardingPage.Pair, error = null)
    }
}

@HiltViewModel
class ConnectionViewModel @Inject constructor(private val repository: ServerRepository) : ViewModel() {
    val connection = repository.connection
    suspend fun disconnect() = repository.disconnect()
}
