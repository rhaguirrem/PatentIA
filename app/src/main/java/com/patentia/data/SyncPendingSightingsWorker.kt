package com.patentia.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.patentia.BuildConfig
import com.patentia.data.remote.FirebaseRemoteSyncDataSource
import com.patentia.data.remote.NoOpRemoteSightingSyncDataSource
import java.util.concurrent.TimeUnit

class SyncPendingSightingsWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        val database = PatentIADatabase.getInstance(applicationContext)
        val remoteSyncDataSource = if (FirebaseRemoteSyncDataSource.isFirebaseConfigured(applicationContext)) {
            FirebaseRemoteSyncDataSource(applicationContext)
        } else {
            NoOpRemoteSightingSyncDataSource(defaultGroupId = BuildConfig.DEFAULT_FIRESTORE_GROUP_ID)
        }
        val repository = SightingRepository(
            dao = database.plateSightingDao(),
            remoteSyncDataSource = remoteSyncDataSource,
        )

        return runCatching {
            repository.syncPendingSightings()
            val syncDiagnostics = repository.observeSyncDiagnostics().value
            when {
                !syncDiagnostics.isConfigured -> Result.success()
                syncDiagnostics.pendingUploadCount == 0 -> Result.success()
                else -> Result.retry()
            }
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sync-pending-sightings"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncPendingSightingsWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}