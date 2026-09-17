package dev.anicloud.sovereign.prototype

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val ReleaseCheckWork = "intermix-release-check-v1"
    private const val PostInstallWork = "intermix-post-install-verification-v1"

    fun scheduleReleaseChecks(context: Context) {
        val staggerMinutes = (UpdateCohort.basisPoints(context) % 360).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<ReleaseUpdateWorker>(
            24,
            TimeUnit.HOURS,
            6,
            TimeUnit.HOURS,
        )
            .setInitialDelay(staggerMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReleaseCheckWork,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePostInstallVerification(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            PostInstallWork,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PostUpdateVerificationWorker>().build(),
        )
    }
}
