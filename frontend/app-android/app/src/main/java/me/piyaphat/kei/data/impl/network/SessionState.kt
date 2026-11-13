package me.piyaphat.kei.data.impl.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import me.piyaphat.kei.data.impl.prefs.TokenCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore by preferencesDataStore("kei-session")

@Singleton
class SessionState private constructor(private val context: Context?, testMode: Boolean) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, false)
    constructor() : this(null, true)

    private val cipher = TokenCipher()
    private val urlKey = stringPreferencesKey("base_url")
    private val idKey = stringPreferencesKey("device_id")
    private val nameKey = stringPreferencesKey("device_name")
    private val tokenKey = stringPreferencesKey("token")
    @Volatile private var saved: Map<String, String> = context?.let {
        runBlocking { it.sessionStore.data.first().asMap().mapKeys { key -> key.key.name }.mapValues { key -> key.value.toString() } }
    } ?: emptyMap()
    @Volatile private var ephemeralToken: String? = null
    private val _connection = MutableStateFlow(connection)
    val connectionFlow = _connection.asStateFlow()

    @Volatile private var pendingUrl: String? = null
    var baseUrl: String?
        get() = pendingUrl ?: saved["base_url"]
        set(value) { pendingUrl = value }
    var token: String?
        get() = ephemeralToken ?: saved["token"]?.let(cipher::decrypt)?.ifBlank { null }
        set(value) { if (context == null) ephemeralToken = value }
    val connection get() = saved["base_url"]?.let {
        me.piyaphat.kei.data.api.Connection(it, saved["device_id"]?.toLongOrNull() ?: 0L, saved["device_name"].orEmpty())
    }

    suspend fun save(connection: me.piyaphat.kei.data.api.Connection, token: String) {
        if (context == null) {
            saved = mapOf("base_url" to connection.baseUrl, "device_id" to connection.deviceId.toString(),
                "device_name" to connection.deviceName)
            ephemeralToken = token
        } else {
            context.sessionStore.edit {
                it[urlKey] = connection.baseUrl
                it[idKey] = connection.deviceId.toString()
                it[nameKey] = connection.deviceName
                it[tokenKey] = cipher.encrypt(token)
            }
            reload()
        }
        _connection.value = connection
    }

    fun clear() = runBlocking {
        context?.sessionStore?.edit { it.clear() }
        saved = emptyMap()
        ephemeralToken = null
        pendingUrl = null
        _connection.value = null
    }

    private suspend fun reload() {
        saved = context!!.sessionStore.data.first().asMap()
            .mapKeys { it.key.name }.mapValues { it.value.toString() }
    }
}
