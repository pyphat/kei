package me.piyaphat.kei.work.impl

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import me.piyaphat.kei.work.api.UploadCleanup
import me.piyaphat.kei.work.api.UploadFile
import me.piyaphat.kei.work.api.UploadSource
import me.piyaphat.kei.work.api.UploadTask
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidUploadSource @Inject constructor(@ApplicationContext private val context: Context) :
    UploadSource, UploadCleanup {
    override fun open(file: UploadFile): InputStream {
        val uri = Uri.parse(file.location)
        return if (uri.scheme == "file") FileInputStream(File(requireNotNull(uri.path)))
        else context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(file.path)
    }

    override fun finish(task: UploadTask) {
        task.permissionUris.forEach { value ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(value), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        File(context.cacheDir, "uploads/${task.id}").deleteRecursively()
    }
}
