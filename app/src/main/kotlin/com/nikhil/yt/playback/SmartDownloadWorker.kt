/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nikhil.yt.constants.SmartDownloadsKey
import com.nikhil.yt.constants.SmartDownloadsLimitKey
import com.nikhil.yt.utils.dataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Smart offline mix: on Wi-Fi + charging, keeps your top predicted songs downloaded. */
class SmartDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun downloadUtil(): DownloadUtil
    }

    override suspend fun doWork(): Result = runCatching {
        val prefs = applicationContext.dataStore.data.first()
        if (prefs[SmartDownloadsKey] != true) return Result.success()
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
            .downloadUtil()
            .runSmartDownloads(prefs[SmartDownloadsLimitKey] ?: DEFAULT_LIMIT)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val DEFAULT_LIMIT = 30
        private const val WORK_NAME = "smart_downloads"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                ).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
