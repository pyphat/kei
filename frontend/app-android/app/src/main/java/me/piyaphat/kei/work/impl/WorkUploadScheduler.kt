package me.piyaphat.kei.work.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.piyaphat.kei.work.api.UploadCleanup
import me.piyaphat.kei.work.api.UploadScheduler
import me.piyaphat.kei.work.api.UploadTask
import me.piyaphat.kei.work.api.UploadTaskRepository
import me.piyaphat.kei.work.api.UploadWorkStatus
import me.piyaphat.kei.work.api.reconcileUploadTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: UploadTaskRepository,
    private val cleanup: UploadCleanup
) : UploadScheduler {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    override suspend fun create(task: UploadTask): Long = store.create(task)

    override suspend fun update(task: UploadTask) = store.update(task)

    override fun enqueue(taskId: Long) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.TASK_ID to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("upload-$taskId", ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(taskId: Long) {
        workManager.cancelUniqueWork("upload-$taskId")
        scope.launch {
            store.get(taskId)?.let { task ->
                store.update(task.copy(state = "cancelled"))
                cleanup.finish(task)
            }
        }
    }

    override suspend fun remove(taskId: Long) {
        withContext(Dispatchers.IO) {
            workManager.cancelUniqueWork("upload-$taskId").result.get()
            store.get(taskId)?.let { task ->
                cleanup.finish(task)
                store.delete(taskId)
            }
        }
    }

    override suspend fun reconcile() = withContext(Dispatchers.IO) {
        val connected = context.getSystemService(ConnectivityManager::class.java)
            ?.let { manager ->
                manager.activeNetwork?.let(manager::getNetworkCapabilities)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } == true
        store.all().forEach { task ->
            if (task.state in setOf("done", "failed", "cancelled")) return@forEach
            val infos = try {
                workManager.getWorkInfosForUniqueWork("upload-${task.id}").get()
            } catch (_: ExecutionException) {
                emptyList()
            }
            val status = when (infos.lastOrNull()?.state) {
                WorkInfo.State.ENQUEUED -> UploadWorkStatus.Enqueued
                WorkInfo.State.RUNNING -> UploadWorkStatus.Running
                WorkInfo.State.BLOCKED -> UploadWorkStatus.Blocked
                WorkInfo.State.SUCCEEDED -> UploadWorkStatus.Succeeded
                WorkInfo.State.FAILED -> UploadWorkStatus.Failed
                WorkInfo.State.CANCELLED -> UploadWorkStatus.Cancelled
                else -> UploadWorkStatus.Missing
            }
            val recovered = reconcileUploadTask(task, status, connected)
            if (recovered != task) store.update(recovered)
        }
    }

    override fun observe(): Flow<List<UploadTask>> =
        store.observe()
}
