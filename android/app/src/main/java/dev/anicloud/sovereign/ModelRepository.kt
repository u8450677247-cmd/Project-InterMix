package dev.anicloud.sovereign.prototype

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val ModelPreferences = "anicloud_model_repository"
private const val ModelNameKey = "active_model_name"
private const val ModelPathKey = "active_model_path"
private const val ModelBytesKey = "active_model_bytes"
private const val ModelShaKey = "active_model_sha256"
private const val CopyBufferBytes = 1024 * 1024
private const val ProgressStrideBytes = 8L * 1024L * 1024L

/** Copies a user-selected model into app-private storage and fingerprints it. */
class ModelRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(ModelPreferences, Context.MODE_PRIVATE)
    private val modelDirectory = File(context.noBackupFilesDir, "models")

    suspend fun importModel(
        uri: Uri,
        role: ModelRole = ModelRole.Reasoning,
        onProgress: (copied: Long, total: Long?) -> Unit,
    ): ImportedModel = withContext(Dispatchers.IO) {
        modelDirectory.mkdirs()
        check(modelDirectory.isDirectory) { "App-private model directory is unavailable." }
        modelDirectory.listFiles { file -> file.name.endsWith(".partial") }
            ?.forEach { it.delete() }

        val metadata = queryMetadata(uri)
        require(metadata.name.lowercase().endsWith(".litertlm")) {
            "Choose a .litertlm model package."
        }
        metadata.size?.let { expectedBytes ->
            require(expectedBytes > 0) { "The selected model is empty." }
            require(modelDirectory.usableSpace > expectedBytes) {
                "Not enough app-private storage to copy this model."
            }
        }

        val partial = File.createTempFile("${role.name.lowercase()}-import-", ".partial", modelDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var nextProgress = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Android could not open the selected model.")
            input.use { source ->
                FileOutputStream(partial).use { target ->
                    val buffer = ByteArray(CopyBufferBytes)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        target.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        if (copied >= nextProgress) {
                            onProgress(copied, metadata.size)
                            nextProgress = copied + ProgressStrideBytes
                        }
                    }
                    target.fd.sync()
                }
            }

            require(copied > 0) { "The selected model is empty." }
            metadata.size?.let { expected ->
                require(copied == expected) {
                    "The model copy was incomplete: expected $expected bytes, copied $copied."
                }
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val installedFile = File(modelDirectory, "$sha256.litertlm")
            try {
                Files.move(
                    partial.toPath(),
                    installedFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    partial.toPath(),
                    installedFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            ImportedModel(
                displayName = metadata.name,
                absolutePath = installedFile.absolutePath,
                byteSize = copied,
                sha256 = sha256,
                role = role,
            ).also(::remember)
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }
    }

    fun installedModel(role: ModelRole = ModelRole.Reasoning): ImportedModel? {
        val prefix = role.name.lowercase()
        val legacy = role == ModelRole.Reasoning
        val path = preferences.getString("${prefix}_model_path", null)
            ?: (if (legacy) preferences.getString(ModelPathKey, null) else null)
            ?: return null
        val name = preferences.getString("${prefix}_model_name", null)
            ?: (if (legacy) preferences.getString(ModelNameKey, null) else null)
            ?: return null
        val sha = preferences.getString("${prefix}_model_sha256", null)
            ?: (if (legacy) preferences.getString(ModelShaKey, null) else null)
            ?: return null
        val roleBytes = preferences.getLong("${prefix}_model_bytes", -1L)
        val bytes = if (roleBytes > 0) roleBytes else if (legacy) {
            preferences.getLong(ModelBytesKey, -1L)
        } else {
            -1L
        }
        if (bytes <= 0) return null

        val file = File(path)
        val insidePrivateModelDirectory = runCatching {
            file.canonicalFile.parentFile == modelDirectory.canonicalFile
        }.getOrDefault(false)
        if (!insidePrivateModelDirectory || !file.isFile || file.length() != bytes) return null
        if (file.name != "$sha.litertlm") return null
        return ImportedModel(name, file.absolutePath, bytes, sha, role)
    }

    private fun remember(model: ImportedModel) {
        val prefix = model.role.name.lowercase()
        val editor = preferences.edit()
            .putString("${prefix}_model_name", model.displayName)
            .putString("${prefix}_model_path", model.absolutePath)
            .putLong("${prefix}_model_bytes", model.byteSize)
            .putString("${prefix}_model_sha256", model.sha256)
        if (model.role == ModelRole.Reasoning) {
            editor
                .putString(ModelNameKey, model.displayName)
                .putString(ModelPathKey, model.absolutePath)
                .putLong(ModelBytesKey, model.byteSize)
                .putString(ModelShaKey, model.sha256)
        }
        check(
            editor.commit(),
        ) { "Could not retain the imported model fingerprint." }
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var name: String? = null
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
        return SourceMetadata(displayName, size?.takeIf { it >= 0 })
    }

    private data class SourceMetadata(val name: String, val size: Long?)
}
