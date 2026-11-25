/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nikhil.yt.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
// Never foreground: Android 12+ throws ForegroundServiceStartNotAllowedException when a download update
// promotes the service while the app is in the background, which crashed the app repeatedly.
class ExoDownloadService : DownloadService(FOREGROUND_NOTIFICATION_ID_NONE) {
    @Inject
    lateinit var downloadUtil: DownloadUtil

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == REMOVE_ALL_PENDING_DOWNLOADS) {
            downloadManager.currentDownloads.forEach { download ->
                downloadManager.removeDownload(download.request.id)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }


    override fun onCreate() {
        // The base class only creates the channel when it has a foreground notification; failure alerts still use it.
        NotificationUtil.createNotificationChannel(
            this, CHANNEL_ID, R.string.downloading, 0, NotificationUtil.IMPORTANCE_LOW
        )
        super.onCreate()
    }

    override fun getDownloadManager() = downloadUtil.downloadManager

    // No scheduler: its restarts also try to go foreground from the background. DownloadManager resumes on next app start.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification =
        Notification.Builder.recoverBuilder(
            this, downloadUtil.downloadNotificationHelper.buildProgressNotification(
                this,
                R.drawable.download,
                null,
                if (downloads.size == 1) Util.fromUtf8Bytes(downloads[0].request.data)
                else resources.getQuantityString(R.plurals.n_song, downloads.size, downloads.size),
                downloads,
                notMetRequirements
            )
        ).addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.close),
                getString(android.R.string.cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ExoDownloadService::class.java).setAction(
                        REMOVE_ALL_PENDING_DOWNLOADS
                    ),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        ).build()


    /**
     * This helper will outlive the lifespan of a single instance of [ExoDownloadService]
     */
    class TerminalStateNotificationHelper(
        private val context: Context,
        private val notificationHelper: DownloadNotificationHelper,
        private var nextNotificationId: Int,
    ) : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            if (download.state == Download.STATE_FAILED) {
                val notification = notificationHelper.buildDownloadFailedNotification(
                    context,
                    R.drawable.error,
                    null,
                    Util.fromUtf8Bytes(download.request.data)
                )
                NotificationUtil.setNotification(context, nextNotificationId++, notification)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val REMOVE_ALL_PENDING_DOWNLOADS = "REMOVE_ALL_PENDING_DOWNLOADS"

        /** The one way to queue a song download; [title] is shown in download notifications. */
        fun download(context: Context, songId: String, title: String) {
            val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(songId, android.net.Uri.parse(songId))
                .setCustomCacheKey(songId)
                .setData(title.toByteArray())
                .build()
            sendAddDownload(context, ExoDownloadService::class.java, request, false)
        }
    }
}
