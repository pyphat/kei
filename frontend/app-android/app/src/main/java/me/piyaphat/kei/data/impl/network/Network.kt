package me.piyaphat.kei.data.impl.network

import kotlinx.serialization.Serializable
import me.piyaphat.kei.work.api.UploadManifestRequest
import me.piyaphat.kei.work.api.UploadManifestResponse
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.PATCH
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

const val API_BASE_URL = "http://kei.invalid/"

class HostRewriteInterceptor(private val session: SessionState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.url.host != "kei.invalid") return chain.proceed(original)
        val base = session.baseUrl ?: throw IOException("not connected")
        val root = base.toHttpUrl()
        val rewritten = original.newBuilder().url(original.url.newBuilder()
            .scheme(root.scheme).host(root.host).port(root.port).build()).build()
        return chain.proceed(rewritten)
    }
}

class AuthInterceptor(private val session: SessionState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val pair = request.url.encodedPath == "/api/devices/pair"
        val token = session.token
        val authenticated = if (token != null && !pair) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else request
        val response = chain.proceed(authenticated)
        if (response.code == 401 && !pair) session.clear()
        return response
    }
}

@Serializable data class ServerDto(val name: String, val hostname: String, val version: String, val api: Int, val auth: String)
@Serializable data class PairRequest(val name: String, val pin: String? = null, val secret: String? = null)
@Serializable data class PairResponse(val device_id: Long, val token: String, val name: String)
@Serializable data class SeriesDto(
    val id: Long, val title: String, val author: String, val tags: List<String>,
    val cover_url: String? = null, val chapter_count: Int, val page_count: Int,
    val updated: Double, val progress: ProgressDto? = null,
    val chapters: List<ChapterDto> = emptyList()
)
@Serializable data class ProgressDto(val chapter_id: Long, val page: Int, val percent: Double)
@Serializable data class ChapterDto(
    val id: Long, val number: Double, val title: String, val page_count: Int,
    val present_count: Int, val processed_count: Int, val translated_count: Int, val state: String
)
@Serializable data class JobDto(
    val chapter_id: Long, val series_id: Long, val series_title: String,
    val chapter_number: Double, val chapter_title: String, val page_count: Int,
    val present_count: Int, val processed_count: Int, val translated_count: Int,
    val state: String, val failed: List<FailedDto> = emptyList(), val current: CurrentDto? = null
)
@Serializable data class FailedDto(val page: Int, val error: String)
@Serializable data class CurrentDto(val page: Int, val stage: String? = null)
@Serializable data class OkDto(val ok: Boolean = true)
@Serializable data class NameDto(val title: String? = null, val author: String? = null, val tags: List<String>? = null)
@Serializable data class ChapterPatchDto(val title: String? = null, val number: Double? = null)
@Serializable data class ActionDto(val action: String)
@Serializable data class StateDto(val state: String)
@Serializable data class CoverDto(val cover_url: String)
@Serializable data class ContinueDto(
    val series_id: Long, val series_title: String, val cover_url: String?,
    val chapter_id: Long, val chapter_title: String, val chapter_number: Double,
    val page: Int, val percent: Double, val ts: Double
)
@Serializable data class CoverPageDto(val chapter_id: Long, val page: Int)
@Serializable data class ReaderPageDto(val idx: Int, val width: Int, val height: Int, val status: String, val rev: Long)
@Serializable data class ReaderChapterDto(
    val id: Long, val series_id: Long, val number: Double, val title: String,
    val state: String, val pages: List<ReaderPageDto>
)
@Serializable data class ReaderProgressDto(
    val series_id: Long, val chapter_id: Long, val page: Int, val percent: Double,
    val device_id: String, val ts: Double
)
@Serializable data class ReaderProgressWriteDto(
    val chapter_id: Long, val page: Int, val percent: Double, val device_id: String, val ts: Double
)
@Serializable data class ReaderDeviceDto(
    val id: Long, val name: String, val created: Double, val last_seen: Double, val current: Boolean
)
@Serializable data class ReaderBlocksDto(
    val width: Int, val height: Int, val blocks: List<ReaderBlockDto>
)
@Serializable data class ReaderBlockDto(
    val id: String, val bbox: List<Int>, val source: String, val translation: String
)
@Serializable data class ReaderSearchHitDto(
    val idx: Int, val block_id: String, val source: String, val translation: String
)
@Serializable data class ReaderFocusDto(val page: Int)
@Serializable data class ReaderReprocessResultDto(val ok: Boolean = true)

interface KeiService {
    @GET("api/server") suspend fun server(): ServerDto
    @POST("api/devices/pair") suspend fun pair(@Body body: PairRequest): PairResponse
    @GET("api/series") suspend fun series(): List<SeriesDto>
    @GET("api/series/{id}") suspend fun series(@Path("id") id: Long): SeriesDto
    @GET("api/jobs") suspend fun jobs(): List<JobDto>
    @GET("api/continue") suspend fun continueReading(): List<ContinueDto>
    @PATCH("api/series/{id}") suspend fun patchSeries(@Path("id") id: Long, @Body body: NameDto): SeriesDto
    @DELETE("api/series/{id}") suspend fun deleteSeries(@Path("id") id: Long): OkDto
    @PATCH("api/chapters/{id}") suspend fun patchChapter(@Path("id") id: Long, @Body body: ChapterPatchDto): OkDto
    @DELETE("api/chapters/{id}") suspend fun deleteChapter(@Path("id") id: Long): OkDto
    @POST("api/chapters/{id}/translate") suspend fun translate(@Path("id") id: Long, @Body body: ActionDto): StateDto
    @POST("api/series/{id}/cover/from-page") suspend fun coverFromPage(@Path("id") id: Long, @Body body: CoverPageDto): CoverDto
    @Multipart @POST("api/series/{id}/cover")
    suspend fun uploadCover(@Path("id") id: Long, @Part file: MultipartBody.Part): CoverDto
    @POST("api/uploads") suspend fun uploadManifest(@Body body: UploadManifestRequest): UploadManifestResponse
    @Multipart @PUT("api/chapters/{cid}/pages/{idx}/file")
    suspend fun uploadPage(@Path("cid") chapterId: Long, @Path("idx") index: Int,
        @Part file: MultipartBody.Part): OkDto
    @GET("api/chapters/{id}") suspend fun readerChapter(@Path("id") id: Long): ReaderChapterDto
    @POST("api/chapters/{id}/focus") suspend fun focusChapter(@Path("id") id: Long, @Body body: ReaderFocusDto): OkDto
    @GET("api/progress/{id}") suspend fun readerProgress(@Path("id") seriesId: Long): ReaderProgressDto?
    @PUT("api/progress/{id}") suspend fun saveReaderProgress(
        @Path("id") seriesId: Long, @Body body: ReaderProgressWriteDto
    ): ReaderProgressDto
    @GET("api/devices") suspend fun devices(): List<ReaderDeviceDto>
    @GET("api/chapters/{id}/search") suspend fun searchChapter(
        @Path("id") chapterId: Long, @Query("q") query: String
    ): List<ReaderSearchHitDto>
    @GET("api/chapters/{id}/pages/{page}/blocks") suspend fun pageBlocks(
        @Path("id") chapterId: Long, @Path("page") page: Int
    ): ReaderBlocksDto
    @POST("api/chapters/{id}/pages/{page}/reprocess") suspend fun reprocessPage(
        @Path("id") chapterId: Long, @Path("page") page: Int
    ): ReaderReprocessResultDto
}


@Singleton
class ApiFactory @Inject constructor(private val session: SessionState) {
    private val json = Json { ignoreUnknownKeys = true }
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HostRewriteInterceptor(session))
        .addInterceptor(AuthInterceptor(session)).build()
    val eventsClient: OkHttpClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    val service: KeiService = Retrofit.Builder().baseUrl(API_BASE_URL).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(KeiService::class.java)
}
