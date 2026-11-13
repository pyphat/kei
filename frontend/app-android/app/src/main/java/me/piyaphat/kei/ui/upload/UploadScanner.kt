package me.piyaphat.kei.ui.upload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.piyaphat.kei.core.api.DetectedLibrary
import me.piyaphat.kei.core.api.FolderStructureDetector
import me.piyaphat.kei.core.api.ImagePaths
import me.piyaphat.kei.core.api.NaturalOrderComparator
import me.piyaphat.kei.core.api.PageArchiveReader
import me.piyaphat.kei.work.api.SkippedUploadFile
import me.piyaphat.kei.work.api.UploadChapter
import me.piyaphat.kei.work.api.UploadFile
import javax.inject.Inject

data class ScanResult(
    val seriesTitle: String,
    val chapters: List<UploadChapter>,
    val permissionUris: List<String>,
    val skipped: List<SkippedUploadFile>,
    val draftDirectory: File? = null
)

class UploadScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: FolderStructureDetector,
    private val archives: PageArchiveReader
) {
    suspend fun folder(uri: Uri, onCount: (Int) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, rootId)
        val rootName = displayName(rootUri) ?: context.getString(me.piyaphat.kei.R.string.folder_source)
        val pathToFile = linkedMapOf<String, UploadFile>()
        val unsupported = mutableListOf<SkippedUploadFile>()
        var scanned = 0
        fun walk(documentId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val relative = if (path.isEmpty()) name else "$path/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, relative)
                        continue
                    }
                    scanned++
                    if (scanned % 20 == 0) onCount(scanned)
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                    val ext = normalizedExt(name)
                    if (ext == null) {
                        unsupported += SkippedUploadFile(relative, context.getString(me.piyaphat.kei.R.string.unsupported_extension))
                        continue
                    }
                    val knownSize = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    val size = if (knownSize >= 0) knownSize else measure(documentUri)
                    if (size > MAX_PAGE_BYTES) {
                        unsupported += SkippedUploadFile(relative, context.getString(me.piyaphat.kei.R.string.file_too_large))
                        continue
                    }
                    pathToFile["$rootName/$relative"] =
                        UploadFile("$rootName/$relative", documentUri.toString(), ext, size)
                }
            }
        }
        walk(rootId, "")
        onCount(scanned)
        val detected = detector.detect(pathToFile.keys.toList())
            ?: throw IOException(context.getString(me.piyaphat.kei.R.string.no_supported_images))
        ScanResult(
            detected.seriesTitle?.takeIf(String::isNotBlank) ?: rootName,
            detected.chapters.mapToFiles(pathToFile),
            listOf(uri.toString()),
            unsupported
        )
    }

    suspend fun archive(uri: Uri, onCount: (Int) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: context.getString(me.piyaphat.kei.R.string.archive_source)
        val draft = File(context.cacheDir, "uploads/draft-${UUID.randomUUID()}")
        val extracted = try {
            context.contentResolver.openInputStream(uri)?.use {
                archives.extract(it, draft, MAX_PAGE_BYTES)
            } ?: throw IOException(context.getString(me.piyaphat.kei.R.string.cannot_read_source))
        } catch (e: Exception) {
            draft.deleteRecursively()
            throw e
        }
        val pathToFile = extracted.associate { image ->
            val path = ImagePaths.normalize(image.name)
            path to UploadFile(path, image.file.toURI().toString(),
                normalizedExt(path) ?: ImagePaths.extension(path), image.size)
        }
        onCount(pathToFile.size)
        val detected = detector.detect(pathToFile.keys.toList())
            ?: throw IOException(context.getString(me.piyaphat.kei.R.string.no_supported_images))
        ScanResult(
            detected.seriesTitle?.takeIf(String::isNotBlank) ?: name.substringBeforeLast('.', name),
            detected.chapters.mapToFiles(pathToFile),
            listOf(uri.toString()),
            emptyList(),
            draft
        )
    }

    suspend fun images(uris: List<Uri>, onCount: (Int) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<SkippedUploadFile>()
        val rows = uris.mapNotNull { uri ->
            val name = displayName(uri) ?: return@mapNotNull null
            val ext = normalizedExt(name) ?: run {
                skipped += SkippedUploadFile(name, context.getString(me.piyaphat.kei.R.string.unsupported_extension))
                return@mapNotNull null
            }
            val size = measure(uri)
            if (size > MAX_PAGE_BYTES) {
                skipped += SkippedUploadFile(name, context.getString(me.piyaphat.kei.R.string.file_too_large))
                return@mapNotNull null
            }
            UploadFile(name, uri.toString(), ext, size)
        }.sortedWith { a, b -> NaturalOrderComparator.INSTANCE.compare(a.path, b.path) }
        onCount(uris.size)
        if (rows.isEmpty()) throw IOException(context.getString(me.piyaphat.kei.R.string.no_supported_images))
        ScanResult(context.getString(me.piyaphat.kei.R.string.images_series_title),
            listOf(UploadChapter(1.0, "", rows)), uris.map(Uri::toString), skipped)
    }

    fun promote(result: ScanResult, taskId: Long): List<UploadChapter> {
        val draft = result.draftDirectory ?: return result.chapters
        val destination = File(context.cacheDir, "uploads/$taskId")
        destination.parentFile?.mkdirs()
        if (!draft.renameTo(destination)) throw IOException("Could not prepare archive files")
        return result.chapters.map { chapter ->
            chapter.copy(files = chapter.files.map { file ->
                file.copy(location = file.location.replace(draft.absolutePath, destination.absolutePath))
            })
        }
    }

    fun cleanup(result: ScanResult) {
        result.draftDirectory?.deleteRecursively()
    }

    fun releasePermissions(uris: List<String>) {
        uris.forEach { value ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(value), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun measure(uri: Uri): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.getString(me.piyaphat.kei.R.string.cannot_read_source))
        return input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_PAGE_BYTES) break
            }
            total
        }
    }

    private fun normalizedExt(path: String): String? =
        ImagePaths.extension(path).takeIf { it in SUPPORTED_EXTENSIONS }
            ?.let { if (it == "jpeg") "jpg" else it }

    private companion object {
        const val MAX_PAGE_BYTES = 50L * 1024 * 1024
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

private fun List<me.piyaphat.kei.core.api.DetectedChapter>.mapToFiles(
    files: Map<String, UploadFile>
): List<UploadChapter> = UploadPreviewMapper.map(this, files)
