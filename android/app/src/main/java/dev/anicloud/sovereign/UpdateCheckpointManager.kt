package dev.anicloud.sovereign.prototype

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant

data class UpdateCheckpoint(
    val directory: File,
    val matrixFile: File,
    val matrixSha256: String,
    val metadataFile: File,
)

class UpdateCheckpointManager(private val context: Context) {
    fun create(manifest: ReleaseManifest): UpdateCheckpoint {
        val root = File(context.noBackupFilesDir, "update-checkpoints").apply { mkdirs() }
        require(root.isDirectory) { "Update checkpoint root is unavailable." }
        val destination = File(root, manifest.releaseId)
        if (destination.exists()) {
            val existing = runCatching {
                inspectExisting(destination, manifest.releaseId, manifest.versionCode)
            }
            if (existing.isSuccess) return existing.getOrThrow()

            // Preserve a broken/incomplete checkpoint for diagnosis instead of deleting recovery evidence.
            val quarantined = File(
                root,
                ".${manifest.releaseId}.invalid-${System.nanoTime()}",
            )
            require(destination.renameTo(quarantined)) {
                "Invalid update checkpoint could not be quarantined."
            }
        }

        val staging = File(root, ".${manifest.releaseId}.staging-${System.nanoTime()}")
        require(staging.mkdir()) { "Update checkpoint staging directory is unavailable." }
        try {
            val matrixFile = File(staging, "sovereign_memory_v1.db")
            val matrix = MemoryMatrixRepository(context)
            val snapshot = try {
                matrix.createUpdateCheckpoint(matrixFile)
                matrix.snapshot()
            } finally {
                matrix.close()
            }
            val matrixSha = sha256File(matrixFile)
            val currentPackage = currentPackageInfo()
            val metadata = JSONObject()
                .put("schema", 1)
                .put("checkpoint_id", manifest.releaseId)
                .put("created_at", Instant.now().toString())
                .put("from_version_code", currentPackage.longVersionCode)
                .put("to_version_code", manifest.versionCode)
                .put("to_version_name", manifest.versionName)
                .put("source_commit", manifest.sourceCommit)
                .put("matrix_sha256", matrixSha)
                .put("matrix_bytes", matrixFile.length())
                .put("message_count", snapshot.messageCount)
                .put("memory_count", snapshot.memoryCount)
                .put("pending_action_count", snapshot.pendingActionCount)
                .put("active_session_populated", snapshot.activeSessionCheckpoint.populated)
                .put("active_mission_present", matrixMissionPresent())
            val metadataFile = File(staging, "checkpoint.json")
            FileOutputStream(metadataFile, false).use { output ->
                output.write(metadata.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            require(staging.renameTo(destination)) { "Update checkpoint could not be finalized." }
            return inspectExisting(destination, manifest.releaseId, manifest.versionCode)
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    fun verify(
        path: String,
        expectedReleaseId: String,
        expectedVersionCode: Long,
    ): UpdateCheckpoint {
        val directory = File(path).canonicalFile
        val root = File(context.noBackupFilesDir, "update-checkpoints").canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "Checkpoint escaped private storage." }
        return inspectExisting(directory, expectedReleaseId, expectedVersionCode)
    }

    private fun inspectExisting(
        directory: File,
        expectedReleaseId: String,
        expectedVersionCode: Long,
    ): UpdateCheckpoint {
        val matrix = File(directory, "sovereign_memory_v1.db")
        val metadata = File(directory, "checkpoint.json")
        require(matrix.isFile && metadata.isFile) { "Update checkpoint is incomplete." }
        val payload = JSONObject(metadata.readText(Charsets.UTF_8))
        require(
            payload.getInt("schema") == 1 &&
                payload.getString("checkpoint_id") == expectedReleaseId &&
                payload.getLong("to_version_code") == expectedVersionCode,
        ) { "Update checkpoint identity does not match the release." }
        val expected = payload.getString("matrix_sha256")
        val actual = sha256File(matrix)
        require(expected == actual) { "Update checkpoint hash does not match." }
        return UpdateCheckpoint(directory, matrix, actual, metadata)
    }

    private fun matrixMissionPresent(): Boolean {
        val matrix = MemoryMatrixRepository(context)
        return try {
            matrix.activeAgentMission() != null
        } finally {
            matrix.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentPackageInfo() = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
}

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
