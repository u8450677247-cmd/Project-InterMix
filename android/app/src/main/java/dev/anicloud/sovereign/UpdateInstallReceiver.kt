package dev.anicloud.sovereign.prototype

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ActionBeginInstall -> {
                val pending = goAsync()
                installerExecutor.execute {
                    try {
                        beginInstall(context.applicationContext, intent.getStringExtra(ExtraReleaseId).orEmpty())
                    } finally {
                        pending.finish()
                    }
                }
            }
            ActionInstallStatus -> handleStatus(context.applicationContext, intent)
        }
    }

    private fun beginInstall(context: Context, releaseId: String) {
        val store = UpdateStateStore(context)
        try {
            val state = store.read()
            require(
                state.phase in setOf(
                    UpdatePhase.AwaitingConfirmation,
                    UpdatePhase.FailedRetryable,
                    UpdatePhase.FailedBlocked,
                ),
            ) {
                "No verified update is awaiting installation."
            }
            require(releaseId == state.releaseId) { "Install request does not match the verified release." }
            UpdateCheckpointManager(context).verify(
                state.checkpointPath,
                state.releaseId,
                state.versionCode,
            )
            val config = requireNotNull(ReleaseTrustConfig.configuredOrNull()) {
                "Release trust configuration is unavailable."
            }
            val releaseDirectory = File(state.apkPath).parentFile
                ?: throw IllegalStateException("Verified release path is invalid.")
            val manifestBytes = File(releaseDirectory, "manifest.json").readBytes()
            val signatureBytes = File(releaseDirectory, "manifest.json.sig").readBytes()
            val manifest = ReleaseManifestVerifier.verifyAndParse(
                manifestBytes = manifestBytes,
                detachedSignatureBase64 = signatureBytes,
                publicKeyBase64 = config.manifestPublicKeyBase64,
                expectedPackageName = context.packageName,
                enforceFreshness = false,
            )
            require(manifest.releaseId == state.releaseId && manifestBytes.sha256Hex() == state.manifestSha256) {
                "Saved release manifest does not match the durable ledger."
            }
            val apk = File(state.apkPath)
            ReleaseApkVerifier(context).verify(apk, manifest, config.apkSignerSha256)

            if (!context.packageManager.canRequestPackageInstalls()) {
                store.record(
                    state.copy(phase = UpdatePhase.AwaitingConfirmation, failure = "Install-source approval required."),
                    "Android requires the user to approve Intermix as an install source.",
                )
                UpdateNotifications.installSourceApproval(
                    context,
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ),
                    state,
                )
                return
            }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val installer = context.packageManager.packageInstaller
            val sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("intermix.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, 128 * 1024)
                            session.fsync(output)
                        }
                    }
                    val status = PendingIntent.getBroadcast(
                        context,
                        state.versionCode.toInt(),
                        Intent(context, UpdateInstallReceiver::class.java).setAction(ActionInstallStatus),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    store.record(state.copy(phase = UpdatePhase.Installing, failure = ""), "APK staged in PackageInstaller.")
                    session.commit(status.intentSender)
                }
            } catch (failure: Throwable) {
                runCatching { installer.abandonSession(sessionId) }
                throw failure
            }
        } catch (failure: Throwable) {
            val current = store.read()
            store.record(
                current.copy(
                    phase = if (failure is SecurityException || failure is IllegalArgumentException) {
                        UpdatePhase.Quarantined
                    } else {
                        UpdatePhase.FailedRetryable
                    },
                    failure = safeUpdateFailure(failure),
                    retryCount = current.retryCount + 1,
                ),
                "PackageInstaller staging failed; installed state is unchanged.",
            )
            if (failure is SecurityException || failure is IllegalArgumentException) {
                UpdateNotifications.result(
                    context,
                    "Intermix update quarantined",
                    "A local trust check failed. The installed app and checkpoint were not changed.",
                    warning = true,
                )
            } else {
                UpdateNotifications.retry(
                    context,
                    current,
                    "The verified APK and pre-update checkpoint are retained for a safe retry.",
                )
            }
        } finally {
            store.close()
        }
    }

    private fun handleStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val store = UpdateStateStore(context)
        try {
            val state = store.read()
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val action = installConfirmationIntent(intent)
                    requireNotNull(action) { "Android did not provide its install confirmation action." }
                    store.record(
                        state.copy(phase = UpdatePhase.AwaitingConfirmation),
                        "Android installation confirmation is required.",
                    )
                    UpdateNotifications.installationAction(
                        context,
                        action,
                        "Android needs one final confirmation; local verification already passed.",
                    )
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    store.record(state.copy(phase = UpdatePhase.Migrating, failure = ""), "Android installed the update.")
                    UpdateScheduler.schedulePostInstallVerification(context)
                }
                PackageInstaller.STATUS_FAILURE_INVALID -> {
                    quarantine(store, state, detail)
                    UpdateNotifications.result(
                        context,
                        "Intermix update quarantined",
                        "Android rejected the APK as invalid. The installed version was retained.",
                        warning = true,
                    )
                }
                PackageInstaller.STATUS_FAILURE_STORAGE,
                PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                    store.failure(true, detail.ifBlank { "Android installation failed." })
                    UpdateNotifications.retry(
                        context,
                        state,
                        "Android could not finish the install. Free space if needed, then retry.",
                    )
                }
                else -> {
                    store.failure(false, detail.ifBlank { "Android installation was blocked or cancelled." })
                    UpdateNotifications.retry(
                        context,
                        state,
                        "Installation was cancelled or blocked. The verified update is still available.",
                    )
                }
            }
        } catch (failure: Throwable) {
            store.failure(false, safeUpdateFailure(failure))
            UpdateNotifications.retry(
                context,
                store.read(),
                "Android confirmation could not be resumed. The current app remains installed.",
            )
        } finally {
            store.close()
        }
    }

    private fun quarantine(store: UpdateStateStore, state: UpdateSnapshot, detail: String) {
        store.record(
            state.copy(
                phase = UpdatePhase.Quarantined,
                failure = detail.ifBlank { "Android rejected the APK as invalid." },
                retryCount = state.retryCount + 1,
            ),
            "PackageInstaller rejected the locally verified APK; release retained for inspection.",
        )
    }

    @Suppress("DEPRECATION")
    private fun installConfirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

    companion object {
        private val installerExecutor = Executors.newSingleThreadExecutor()
        const val ActionBeginInstall = "dev.anicloud.sovereign.action.BEGIN_VERIFIED_UPDATE"
        const val ActionInstallStatus = "dev.anicloud.sovereign.action.UPDATE_INSTALL_STATUS"
        const val ExtraReleaseId = "release_id"
    }
}
