package me.piyaphat.kei.data.impl.network

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.piyaphat.kei.work.api.UploadManifestRequest
import me.piyaphat.kei.work.api.UploadManifestResponse
import me.piyaphat.kei.work.api.UploadHttpException
import me.piyaphat.kei.work.api.UploadFile
import me.piyaphat.kei.work.api.UploadRemote
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetrofitUploadRemote @Inject constructor(private val service: KeiService) : UploadRemote {
    override suspend fun manifest(request: UploadManifestRequest): UploadManifestResponse =
        call { service.uploadManifest(request) }

    override suspend fun putPage(chapterId: Long, index: Int, file: UploadFile, open: () -> InputStream) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                open().use { it.copyTo(sink.outputStream()) }
            }
        }
        val name = file.path.substringAfterLast('/').ifBlank { "page.${file.ext}" }
        call { service.uploadPage(chapterId, index, MultipartBody.Part.createFormData("file", name, body)) }
    }

    override suspend fun setCover(seriesId: Long, chapterId: Long, page: Int) {
        call { service.coverFromPage(seriesId, CoverPageDto(chapterId, page)) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val detail = runCatching {
            Json.parseToJsonElement(e.response()?.errorBody()?.string().orEmpty())
                .jsonObject["detail"]?.jsonPrimitive?.content
        }.getOrNull()
        throw UploadHttpException(e.code(), detail ?: e.message())
    }
}
