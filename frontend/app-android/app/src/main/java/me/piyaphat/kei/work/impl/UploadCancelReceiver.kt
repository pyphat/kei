package me.piyaphat.kei.work.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

class UploadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(UploadWorker.TASK_ID, -1)
        if (id >= 0) WorkManager.getInstance(context).cancelUniqueWork("upload-$id")
    }
}
