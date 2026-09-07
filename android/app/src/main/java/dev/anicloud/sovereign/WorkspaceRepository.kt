package dev.anicloud.sovereign.prototype

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

private const val WorkspacePreferences = "anicloud_workspace"
private const val WorkspaceRootKey = "root_uri"
private const val MaxEditableBytes = 2L * 1024L * 1024L

data class WorkspaceEntry(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long?,
    val isDirectory: Boolean,
)

data class WorkspaceSnapshot(
    val byteSize: Long,
    val snapshotName: String,
)

class WorkspaceRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(WorkspacePreferences, Context.MODE_PRIVATE)

    fun storedRoot(): Uri? = preferences.getString(WorkspaceRootKey, null)?.let(Uri::parse)

    fun attachRoot(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        check(preferences.edit().putString(WorkspaceRootKey, uri.toString()).commit()) {
            "Could not retain the workspace permission."
        }
    }

    suspend fun rootLabel(root: Uri): String = withContext(Dispatchers.IO) {
        queryDisplayName(root) ?: "Sovereign Workspace"
    }

    suspend fun listChildren(root: Uri, directory: Uri): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) {
            val documentId = if (DocumentsContract.isTreeUri(directory)) {
                DocumentsContract.getTreeDocumentId(directory)
            } else {
                DocumentsContract.getDocumentId(directory)
            }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val entries = mutableListOf<WorkspaceEntry>()
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    entries += WorkspaceEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(root, id).toString(),
                        displayName = cursor.getString(nameIndex) ?: "Untitled",
                        mimeType = mime,
                        byteSize = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex),
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
            entries.sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }
                .thenBy { it.displayName.lowercase() })
        }

    suspend fun readText(entry: WorkspaceEntry): String = withContext(Dispatchers.IO) {
        require(!entry.isDirectory) { "Choose a text file, not a directory." }
        entry.byteSize?.let { require(it <= MaxEditableBytes) { "Files above 2 MiB open read-only later." } }
        require(isEditableText(entry)) { "This file type is not enabled in the text editor." }
        val bytes = context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use(::readBoundedBytes)
            ?: error("Android could not open this file.")
        require(bytes.size <= MaxEditableBytes) { "Files above 2 MiB open read-only later." }
        bytes.toString(Charsets.UTF_8)
    }

    suspend fun writeText(entry: WorkspaceEntry, text: String): WorkspaceSnapshot =
        withContext(Dispatchers.IO) {
            require(!entry.isDirectory && isEditableText(entry)) { "Only reviewed text files can be written." }
            val uri = Uri.parse(entry.uri)
            val previous = context.contentResolver.openInputStream(uri)?.use(::readBoundedBytes)
                ?: error("Could not read the pre-write version.")
            require(previous.size <= MaxEditableBytes) { "File changed and is now above the 2 MiB write limit." }
            val snapshotDirectory = File(context.filesDir, "workspace_snapshots").apply { mkdirs() }
            check(snapshotDirectory.isDirectory) { "Snapshot directory is unavailable." }
            val identity = MessageDigest.getInstance("SHA-256")
                .digest(entry.uri.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(16)
            val snapshot = File(snapshotDirectory, "${identity}-${System.currentTimeMillis()}.snapshot")
            FileOutputStream(snapshot).use { output ->
                output.write(previous)
                output.fd.sync()
            }
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            } ?: error("The document provider denied write access.")
            WorkspaceSnapshot(previous.size.toLong(), snapshot.name)
        }

    private fun readBoundedBytes(source: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (output.size().toLong() <= MaxEditableBytes) {
            val read = source.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
    }

    private fun isEditableText(entry: WorkspaceEntry): Boolean {
        if (entry.mimeType.startsWith("text/")) return true
        if (entry.mimeType in setOf("application/json", "application/xml", "application/javascript")) return true
        val extension = entry.displayName.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "c", "cc", "cpp", "css", "go", "gradle", "h", "hpp", "html", "java", "js",
            "json", "kt", "kts", "lua", "md", "py", "rs", "sh", "sql", "toml", "ts",
            "tsx", "txt", "xml", "yaml", "yml",
        )
    }
}
