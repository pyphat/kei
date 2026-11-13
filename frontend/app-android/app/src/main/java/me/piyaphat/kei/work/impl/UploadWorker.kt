package me.piyaphat.kei.work.impl

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.piyaphat.kei.data.api.LibraryRepository
import me.piyaphat.kei.work.api.UploadHttpException
import me.piyaphat.kei.work.api.UploadNotifier
import me.piyaphat.kei.work.api.UploadTaskStore
import java.io.IOException

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: UploadEngine,
    private val store: UploadTaskStore,
    private val cleanup: AndroidUploadSource,
    private val notifier: UploadNotifier,
    private val library: LibraryRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(TASK_ID, -1)
        val task = store.get(id) ?: return Result.failure()
        return try {
            setForeground(notifier.foregroundInfo(id, task.seriesTitle, task.done, task.total))
            val complete = engine.run(id) { update ->
                setForeground(notifier.foregroundInfo(id, update.seriesTitle, update.done, update.total))
            }
            runCatching { library.refresh() }
            cleanup.finish(complete)
            notifier.notifyDone(id, complete.seriesTitle, requireNotNull(complete.seriesId))
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                store.get(id)?.let { cancelled ->
                    store.update(cancelled.copy(state = "cancelled"))
                    cleanup.finish(cancelled)
                }
            }
            throw e
        } catch (e: UploadHttpException) {
            if (e.status >= 500) {
                store.get(id)?.let { store.update(it.copy(state = "queued", error = null)) }
                Result.retry()
            } else {
                fail(id, task.seriesTitle, e.message ?: "Upload failed")
                Result.failure()
            }
        } catch (e: IOException) {
            store.get(id)?.let { store.update(it.copy(state = "queued", error = null)) }
            Result.retry()
        } catch (e: Exception) {
            fail(id, task.seriesTitle, e.message ?: "Upload failed")
            Result.failure()
        }
    }

    private suspend fun fail(id: Long, title: String, message: String) {
        store.get(id)?.let { store.update(it.copy(state = "failed", error = message)) }
        notifier.notifyFailed(id, title, message)
    }

    companion object {
        const val TASK_ID = "upload_task_id"
    }
}
