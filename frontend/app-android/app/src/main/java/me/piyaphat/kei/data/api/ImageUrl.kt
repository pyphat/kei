package me.piyaphat.kei.data.api

const val IMAGE_BASE_URL = "http://kei.invalid/"

fun stableImageUrl(path: String?, width: Int = 480): String? = path?.let {
    val url = if (it.startsWith("http://") || it.startsWith("https://")) it
        else IMAGE_BASE_URL + it.trimStart('/')
    url + (if ('?' in url) "&" else "?") + "w=$width"
}
