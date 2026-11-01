package com.limo1800.realtime.outbox

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox when the app isn't around to do it (killed, backgrounded, offline when
 * the driver tapped). Needs a network; WorkManager backs off exponentially between tries.
 *
 * No Hilt here: the app hands the processor over once at startup with [install]. If the
 * process was recreated by WorkManager, the app's `Application.onCreate` has run first, so
 * the processor is set by the time [doWork] runs.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val processor = processor ?: return Result.retry()
        val result = processor.drain()
        return when {
            result.authFailed -> Result.success() // retried after re-login, not on a timer
            result.pending > 0 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "limo-rt-outbox"

        @Volatile
        private var processor: OutboxProcessor? = null

        fun install(processor: OutboxProcessor) {
            this.processor = processor
        }

        /** Make sure something will drain the outbox once there's a network. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
