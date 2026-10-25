package com.swissdark.launcher.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Trending, new titles, where to watch: once a day on Wi-Fi (unmetered). */
class DailyRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = DataGraph.instance ?: return Result.retry()
        return try {
            g.titles.expire()
            g.search.trending(daily = false)
            g.search.trending(daily = true)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // TMDB down or no route: a couple of retries, then wait for tomorrow's run instead
            // of backing off for hours (each retry is a wakeup with the network up).
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        /**
         * Called from startBackground on the main thread: WorkManager's first getInstance
         * opens its database and schedulers, so do it on a background thread.
         */
        fun schedule(context: Context) {
            val app = context.applicationContext
            kotlin.concurrent.thread(name = "work-schedule", isDaemon = true) { runCatching { enqueue(app) } }
        }

        private fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<DailyRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
