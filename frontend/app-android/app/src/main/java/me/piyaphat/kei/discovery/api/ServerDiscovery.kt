package me.piyaphat.kei.discovery.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredServer(val name: String, val host: String, val port: Int) {
    val baseUrl get() = "http://$host:$port"
}

interface ServerDiscovery {
    fun start(listener: Listener)
    fun stop()
    fun interface Listener { fun onServers(servers: List<DiscoveredServer>) }
}

fun ServerDiscovery.servers(): Flow<List<DiscoveredServer>> = callbackFlow {
    val listener = ServerDiscovery.Listener { trySend(it) }
    start(listener)
    awaitClose { stop() }
}
