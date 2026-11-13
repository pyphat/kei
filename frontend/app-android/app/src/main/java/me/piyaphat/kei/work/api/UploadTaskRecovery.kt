package me.piyaphat.kei.work.api

enum class UploadWorkStatus { Enqueued, Running, Blocked, Succeeded, Failed, Cancelled, Missing }

fun reconcileUploadTask(
    task: UploadTask,
    workStatus: UploadWorkStatus,
    networkConnected: Boolean
): UploadTask {
    if (task.state == "done" || task.state == "failed" || task.state == "cancelled") return task
    return when (workStatus) {
        UploadWorkStatus.Enqueued -> task.copy(
            state = if (networkConnected) "queued" else "waiting",
            error = null
        )
        UploadWorkStatus.Running, UploadWorkStatus.Blocked -> task
        else -> task.copy(state = "failed", error = "Upload stopped. Tap Retry to continue.")
    }
}
