package me.piyaphat.kei.work.impl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.ExecutionException;

import me.piyaphat.kei.MainActivity;
import me.piyaphat.kei.R;
import me.piyaphat.kei.work.api.UploadNotifier;

public final class UploadNotificationHelper implements UploadNotifier {
    private static final String PROGRESS_CHANNEL = "kei_uploads";
    private static final String RESULT_CHANNEL = "kei_upload_results";
    private final Context context;
    private final WorkManager workManager;
    private final NotificationManager notifications;

    public UploadNotificationHelper(Context context, WorkManager workManager) {
        this.context = context;
        this.workManager = workManager;
        this.notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                PROGRESS_CHANNEL, context.getString(R.string.upload_channel),
                NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(
                RESULT_CHANNEL, context.getString(R.string.upload_result_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @NonNull
    @Override
    public ForegroundInfo foregroundInfo(long taskId, @NonNull String title, int done, int total) {
        Notification.Builder builder = new Notification.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.uploading_title, title))
                .setContentText(context.getString(R.string.uploading_count, done, total))
                .setProgress(Math.max(total, 1), Math.min(done, total), total == 0)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        context.getString(R.string.cancel),
                        cancelIntent(taskId)).build());
        int notificationId = notificationId(taskId);
        Notification notification = builder.build();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? new ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                : new ForegroundInfo(notificationId, notification);
    }

    @Override
    public void notifyDone(long taskId, @NonNull String title, long seriesId) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("kei://series/" + seriesId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(context, notificationId(taskId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notifications.notify(notificationId(taskId), new Notification.Builder(context, RESULT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.upload_done, title))
                .setContentText(context.getString(R.string.upload_open))
                .setContentIntent(content).setAutoCancel(true).build());
    }

    @Override
    public void notifyFailed(long taskId, @NonNull String title, @NonNull String message) {
        notifications.notify(notificationId(taskId), new Notification.Builder(context, RESULT_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.upload_failed, title))
                .setContentText(message).setAutoCancel(true).build());
    }

    private PendingIntent cancelIntent(long taskId) {
        try {
            List<WorkInfo> work = workManager.getWorkInfosForUniqueWork("upload-" + taskId).get();
            if (work != null && !work.isEmpty()) return workManager.createCancelPendingIntent(work.get(0).getId());
        } catch (ExecutionException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        return PendingIntent.getBroadcast(context, notificationId(taskId),
                new Intent(context, UploadCancelReceiver.class).putExtra(UploadWorker.TASK_ID, taskId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int notificationId(long id) {
        return (int) (id & 0x7fffffff);
    }
}
