package me.piyaphat.kei.ui.common

import me.piyaphat.kei.R
import me.piyaphat.kei.data.api.Kind

fun Kind.messageResource(): Int = when (this) {
    Kind.Offline -> R.string.offline_message
    Kind.Unauthorized -> R.string.unauthorized_message
    Kind.NotFound -> R.string.error_not_found
    Kind.Server -> R.string.error_server
    Kind.Unknown -> R.string.error_unknown
}

fun formatChapterNumber(number: Double): String =
    if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
