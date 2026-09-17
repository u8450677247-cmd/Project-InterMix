package dev.anicloud.sovereign.prototype

import android.app.Application

class IntermixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateNotifications.createChannel(this)
        UpdateScheduler.schedulePostInstallVerification(this)
        runCatching { ReleaseTrustConfig.configuredOrNull() }
            .onSuccess { config -> if (config != null) UpdateScheduler.scheduleReleaseChecks(this) }
            .onFailure { failure ->
                UpdateStateStore(this).use {
                    it.failure(retryable = false, detail = failure.message ?: "Updater trust configuration failed.")
                }
            }
    }
}
