package me.piyaphat.kei.data.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairLinkRoute(val target: PairTarget, val requiresConfirmation: Boolean)

fun resolvePairLink(raw: String?, connected: Boolean): PairLinkRoute? =
    raw?.let(::parsePairUri)?.let { PairLinkRoute(it, connected) }

fun parsePairUri(raw: String): PairTarget? = runCatching {
    val uri = URI(raw)
    if (uri.scheme != "kei" || uri.host != "pair") return null
    val params = uri.rawQuery.orEmpty().split('&').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size != 2) null else URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
            URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
    }.toMap()
    val base = URI(params["u"] ?: return null)
    val secret = params["s"]?.takeIf(String::isNotBlank) ?: return null
    if (base.scheme !in setOf("http", "https") || base.host.isNullOrBlank()) return null
    PairTarget(base.toString().trimEnd('/'), secret)
}.getOrNull()
