package dev.anicloud.sovereign.prototype

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

class ReleaseUpdateWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val store = UpdateStateStore(applicationContext)
        try {
            val config = ReleaseTrustConfig.configuredOrNull() ?: return Result.success()
            val client = ReleaseOriginClient(config)
            val (manifestBytes, signatureBytes) = client.fetchManifest()
            val manifest = ReleaseManifestVerifier.verifyAndParse(
                manifestBytes = manifestBytes,
                detachedSignatureBase64 = signatureBytes,
                publicKeyBase64 = config.manifestPublicKeyBase64,
                expectedPackageName = applicationContext.packageName,
            )
            val manifestSha = manifestBytes.sha256Hex()
            val currentVersion = installedVersionCode(applicationContext)
            var state = UpdateSnapshot(
                phase = UpdatePhase.Discovered,
                releaseId = manifest.releaseId,
                versionCode = manifest.versionCode,
                versionName = manifest.versionName,
                manifestSha256 = manifestSha,
                apkSha256 = manifest.apkSha256,
                signerSha256 = manifest.apkSignerSha256,
                expectedBytes = manifest.apkBytes,
            )
            store.record(state, "Authenticated signed release manifest.")
            if (!manifest.eligible(currentVersion, UpdateCohort.basisPoints(applicationContext), Instant.now())) {
                store.record(state, "Release is current, outside this cohort, or not active yet.")
                return Result.success()
            }

            val releaseDirectory = File(
                applicationContext.noBackupFilesDir,
                "verified-releases/${manifest.releaseId}",
            ).apply { mkdirs() }
            require(releaseDirectory.isDirectory) { "Verified release directory is unavailable." }
            writeDurable(File(releaseDirectory, "manifest.json"), manifestBytes)
            writeDurable(File(releaseDirectory, "manifest.json.sig"), signatureBytes)
            val partialApk = File(releaseDirectory, "intermix.apk.part")
            val finalApk = File(releaseDirectory, "intermix.apk")
            state = state.copy(
                phase = UpdatePhase.Eligible,
                apkPath = finalApk.absolutePath,
                downloadedBytes = partialApk.takeIf(File::isFile)?.length() ?: 0L,
            )
            store.record(state, "Device is eligible for this staged rollout.")

            if (!finalApk.isFile || finalApk.length() != manifest.apkBytes || sha256File(finalApk) != manifest.apkSha256) {
                if (finalApk.exists()) require(finalApk.delete()) { "Invalid cached release could not be reset." }
                state = state.copy(phase = UpdatePhase.Downloading)
                store.record(state, "Starting or resuming the immutable APK download.")
                setForeground(
                    UpdateNotifications.foreground(
                        applicationContext,
                        state.downloadedBytes,
                        manifest.apkBytes,
                    ),
                )
                var lastLedgerBytes = state.downloadedBytes
                client.downloadApk(manifest, partialApk) { downloaded ->
                    UpdateNotifications.publishProgress(applicationContext, downloaded, manifest.apkBytes)
                    if (downloaded == manifest.apkBytes || downloaded - lastLedgerBytes >= 4 * 1024 * 1024L) {
                        lastLedgerBytes = downloaded
                        state = state.copy(
                            phase = UpdatePhase.Downloading,
                            downloadedBytes = downloaded,
                        )
                        store.record(state, "Download progress checkpoint.")
                    }
                }
                if (finalApk.exists()) require(finalApk.delete()) {
                    "Existing release APK could not be replaced."
                }
                require(partialApk.renameTo(finalApk)) { "Verified release could not be finalized locally." }
            }

            state = state.copy(
                phase = UpdatePhase.Downloaded,
                downloadedBytes = finalApk.length(),
                apkPath = finalApk.absolutePath,
            )
            store.record(state, "APK download completed; verification started.")
            ReleaseApkVerifier(applicationContext).verify(finalApk, manifest, config.apkSignerSha256)
            state = state.copy(phase = UpdatePhase.Verified)
            store.record(state, "APK hash, package, version, and signing identity verified locally.")

            val checkpoint = UpdateCheckpointManager(applicationContext).create(manifest)
            state = state.copy(
                phase = UpdatePhase.Checkpointed,
                checkpointPath = checkpoint.directory.absolutePath,
            )
            store.record(state, "Durable Matrix and project-state checkpoint verified.")
            UpdateNotifications.ready(applicationContext, manifest)
            state = state.copy(phase = UpdatePhase.AwaitingConfirmation)
            store.record(state, "Waiting for Android installation confirmation.")
            return Result.success()
        } catch (network: IOException) {
            store.failure(retryable = true, detail = safeUpdateFailure(network))
            return Result.retry()
        } catch (integrity: SecurityException) {
            val current = store.read()
            store.record(
                current.copy(
                    phase = UpdatePhase.Quarantined,
                    failure = safeUpdateFailure(integrity),
                    retryCount = current.retryCount + 1,
                ),
                "Release quarantined after a trust check failed.",
            )
            UpdateNotifications.result(
                applicationContext,
                "Intermix update quarantined",
                "A release trust check failed. The installed app and local state were not changed.",
                warning = true,
            )
            return Result.failure()
        } catch (invalid: IllegalArgumentException) {
            val current = store.read()
            store.record(
                current.copy(
                    phase = UpdatePhase.Quarantined,
                    failure = safeUpdateFailure(invalid),
                    retryCount = current.retryCount + 1,
                ),
                "Release quarantined because signed metadata or content was invalid.",
            )
            return Result.failure()
        } catch (failure: Throwable) {
            val retryable = runAttemptCount < 4
            store.failure(retryable, safeUpdateFailure(failure))
            return if (retryable) Result.retry() else Result.failure()
        } finally {
            store.close()
        }
    }
}

class PostUpdateVerificationWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val store = UpdateStateStore(applicationContext)
        try {
            var state = store.read()
            if (state.phase !in setOf(UpdatePhase.Installing, UpdatePhase.Migrating, UpdatePhase.Verifying)) {
                return Result.success()
            }
            if (installedVersionCode(applicationContext) < state.versionCode) return Result.success()
            state = state.copy(phase = UpdatePhase.Migrating, failure = "")
            store.record(state, "Updated binary started; opening the Matrix runs transactional migrations.")
            UpdateCheckpointManager(applicationContext).verify(
                state.checkpointPath,
                state.releaseId,
                state.versionCode,
            )
            val matrix = MemoryMatrixRepository(applicationContext)
            val report = try {
                state = state.copy(phase = UpdatePhase.Verifying)
                store.record(state, "Running deterministic Librarian integrity checks.")
                matrix.librarianIntegrityCheck()
            } finally {
                matrix.close()
            }
            store.record(state.copy(phase = UpdatePhase.Complete), report)
            UpdateNotifications.result(
                applicationContext,
                "Intermix update complete",
                "Migrations and Librarian integrity checks passed. Local state is ready.",
            )
            return Result.success()
        } catch (failure: Throwable) {
            val current = store.read()
            store.record(
                current.copy(
                    phase = UpdatePhase.Quarantined,
                    failure = safeUpdateFailure(failure),
                    retryCount = current.retryCount + 1,
                ),
                "Post-install migration or Librarian verification failed; checkpoint retained.",
            )
            UpdateNotifications.result(
                applicationContext,
                "Intermix state verification failed",
                "The pre-update checkpoint is retained. Intermix will not mark this update complete.",
                warning = true,
            )
            return Result.failure()
        } finally {
            store.close()
        }
    }
}

@Suppress("DEPRECATION")
internal fun installedVersionCode(context: Context): Long = if (Build.VERSION.SDK_INT >= 33) {
    context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
    ).longVersionCode
} else {
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}

private fun writeDurable(file: File, bytes: ByteArray) {
    if (file.isFile && file.readBytes().contentEquals(bytes)) return
    val temporary = File(file.parentFile, ".${file.name}.tmp")
    if (temporary.exists()) require(temporary.delete()) { "Stale release metadata could not be reset." }
    FileOutputStream(temporary, false).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    if (file.exists()) require(file.delete()) { "Existing release metadata could not be replaced." }
    require(temporary.renameTo(file)) { "Release metadata could not be committed." }
}

internal fun safeUpdateFailure(failure: Throwable): String =
    (failure.message ?: failure::class.java.simpleName)
        .replace(Regex("https?://\\S+"), "[release origin]")
        .replace(Regex("/[^\\s]+"), "[private path]")
        .replace(Regex("\\s+"), " ")
        .take(320)
