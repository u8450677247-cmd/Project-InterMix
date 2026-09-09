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
private const val MaxModelReadCharacters = 16 * 1024

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
    val beforeSha256: String,
    val afterSha256: String,
)

data class WorkspaceActionResult(
    val detail: String,
    val toolContent: String = "",
    val beforeSha256: String? = null,
    val afterSha256: String? = null,
)

/** A persisted Storage Access Framework tree is the complete authority boundary. */
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
        runCatching { queryDisplayName(root) }.getOrNull() ?: fallbackRootLabel(root)
    }

    suspend fun listChildren(root: Uri, directory: Uri): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) { listChildrenNow(root, directory) }

    suspend fun readText(entry: WorkspaceEntry): String = withContext(Dispatchers.IO) {
        readTextNow(entry)
    }

    suspend fun writeText(entry: WorkspaceEntry, text: String): WorkspaceSnapshot =
        withContext(Dispatchers.IO) { writeTextNow(entry, text) }

    /** Controller truth supplied to the model on every turn. */
    suspend fun controllerContext(): String = withContext(Dispatchers.IO) {
        val root = storedRoot()
        if (root == null) {
            "[WORKSPACE]\nDisconnected. Ask the user to open Workspace and CONNECT PROJECT."
        } else {
            val label = runCatching { queryDisplayName(root) }.getOrNull() ?: fallbackRootLabel(root)
            "[WORKSPACE]\nConnected root: $label\n" +
                "Tools: list_files and read_file run inside this root. " +
                "In ordinary Chat, create_file, write_file, and create_directory require visible " +
                "approval. A controller-owned active Work Session may separately grant those " +
                "actions inside one exact scoped root. " +
                "Deletion and access outside this root are unavailable."
        }
    }

    suspend fun executeReadOnly(proposal: WorkspaceActionProposal): WorkspaceActionResult =
        withContext(Dispatchers.IO) {
            val root = storedRoot() ?: error("No workspace is connected.")
            when (proposal.kind) {
                WorkspaceActionKind.ListFiles -> listPath(root, proposal.path)
                WorkspaceActionKind.ReadFile -> readPath(root, proposal.path)
                else -> error("${proposal.kind.wireName} requires approval before execution.")
            }
        }

    suspend fun executeApproved(proposal: WorkspaceActionProposal): WorkspaceActionResult =
        withContext(Dispatchers.IO) {
            require(proposal.kind.requiresApproval) { "This action does not require approval." }
            val root = storedRoot() ?: error("No workspace is connected.")
            val normalized = normalizeWorkspacePath(proposal.path)
            when (proposal.kind) {
                WorkspaceActionKind.CreateFile -> createFile(root, normalized, proposal.content)
                WorkspaceActionKind.WriteFile -> writePath(root, normalized, proposal.content)
                WorkspaceActionKind.CreateDirectory -> createDirectory(root, normalized)
                else -> error("Read-only actions execute without an approval record.")
            }
        }

    private fun listPath(root: Uri, rawPath: String): WorkspaceActionResult {
        val path = normalizeWorkspacePath(rawPath, allowRoot = true)
        val directory = resolveEntry(root, path)
            ?: error("Workspace path not found: ${displayPath(path)}")
        require(directory.isDirectory) { "The requested list path is not a directory." }
        val entries = listChildrenNow(root, Uri.parse(directory.uri)).take(200)
        val body = buildString {
            appendLine("Directory: ${displayPath(path)}")
            entries.forEach { entry ->
                append(if (entry.isDirectory) "DIR  " else "FILE ")
                append(entry.displayName)
                entry.byteSize?.takeIf { !entry.isDirectory }?.let { append("  ($it bytes)") }
                appendLine()
            }
        }.take(24 * 1024)
        return WorkspaceActionResult(
            detail = "Listed ${entries.size} entries in ${displayPath(path)}.",
            toolContent = body,
        )
    }

    private fun readPath(root: Uri, rawPath: String): WorkspaceActionResult {
        val path = normalizeWorkspacePath(rawPath)
        val entry = resolveEntry(root, path) ?: error("Workspace file not found: $path")
        val full = readTextNow(entry)
        val clipped = full.take(MaxModelReadCharacters)
        val suffix = if (full.length > MaxModelReadCharacters) {
            "\n[TRUNCATED: ${full.length - MaxModelReadCharacters} more characters were not sent to the model]"
        } else {
            ""
        }
        return WorkspaceActionResult(
            detail = "Read $path (${full.length} characters).",
            toolContent = "File: $path\n---\n$clipped$suffix",
            afterSha256 = sha256(full.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun writePath(root: Uri, path: String, content: String): WorkspaceActionResult {
        val entry = resolveEntry(root, path) ?: error("Workspace file not found: $path")
        require(!entry.isDirectory) { "Cannot replace a directory with text." }
        val snapshot = writeTextNow(entry, content)
        return WorkspaceActionResult(
            detail = "Wrote $path; pre-write snapshot ${snapshot.snapshotName} retained.",
            beforeSha256 = snapshot.beforeSha256,
            afterSha256 = snapshot.afterSha256,
        )
    }

    private fun createFile(root: Uri, path: String, content: String): WorkspaceActionResult {
        val (parentPath, name) = splitParent(path)
        val parent = resolveEntry(root, parentPath)
            ?: error("Parent directory not found: ${displayPath(parentPath)}")
        require(parent.isDirectory) { "The parent path is not a directory." }
        require(resolveEntry(root, path) == null) { "A workspace entry already exists at $path." }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MaxEditableBytes) { "Generated files are limited to 2 MiB." }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            Uri.parse(parent.uri),
            mimeForName(name),
            name,
        ) ?: error("The document provider refused to create $path.")
        writeBytes(created, bytes)
        return WorkspaceActionResult(
            detail = "Created $path (${bytes.size} bytes).",
            afterSha256 = sha256(bytes),
        )
    }

    private fun createDirectory(root: Uri, path: String): WorkspaceActionResult {
        val (parentPath, name) = splitParent(path)
        val parent = resolveEntry(root, parentPath)
            ?: error("Parent directory not found: ${displayPath(parentPath)}")
        require(parent.isDirectory) { "The parent path is not a directory." }
        require(resolveEntry(root, path) == null) { "A workspace entry already exists at $path." }
        DocumentsContract.createDocument(
            context.contentResolver,
            Uri.parse(parent.uri),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("The document provider refused to create $path.")
        return WorkspaceActionResult(detail = "Created directory $path.")
    }

    private fun listChildrenNow(root: Uri, directory: Uri): List<WorkspaceEntry> {
        val documentId = documentIdFor(directory)
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
        return entries.sortedWith(
            compareByDescending<WorkspaceEntry> { it.isDirectory }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private fun resolveEntry(root: Uri, path: String): WorkspaceEntry? {
        val rootId = DocumentsContract.getTreeDocumentId(root)
        var current = WorkspaceEntry(
            uri = DocumentsContract.buildDocumentUriUsingTree(root, rootId).toString(),
            displayName = queryDisplayName(root) ?: "Sovereign Workspace",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            byteSize = null,
            isDirectory = true,
        )
        if (path.isBlank()) return current
        for (segment in path.split('/')) {
            if (!current.isDirectory) return null
            current = listChildrenNow(root, Uri.parse(current.uri))
                .firstOrNull { it.displayName == segment } ?: return null
        }
        return current
    }

    private fun readTextNow(entry: WorkspaceEntry): String {
        require(!entry.isDirectory) { "Choose a text file, not a directory." }
        entry.byteSize?.let { require(it <= MaxEditableBytes) { "Files above 2 MiB are not enabled." } }
        require(isEditableText(entry)) { "This file type is not enabled for text access." }
        val bytes = context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use(::readBoundedBytes)
            ?: error("Android could not open this file.")
        require(bytes.size <= MaxEditableBytes) { "Files above 2 MiB are not enabled." }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeTextNow(entry: WorkspaceEntry, text: String): WorkspaceSnapshot {
        require(!entry.isDirectory && isEditableText(entry)) { "Only reviewed text files can be written." }
        val uri = Uri.parse(entry.uri)
        val previous = context.contentResolver.openInputStream(uri)?.use(::readBoundedBytes)
            ?: error("Could not read the pre-write version.")
        require(previous.size <= MaxEditableBytes) { "File changed and is now above the 2 MiB write limit." }
        val next = text.toByteArray(Charsets.UTF_8)
        require(next.size <= MaxEditableBytes) { "Generated files are limited to 2 MiB." }
        val snapshotDirectory = File(context.filesDir, "workspace_snapshots").apply { mkdirs() }
        check(snapshotDirectory.isDirectory) { "Snapshot directory is unavailable." }
        val identity = sha256(entry.uri.toByteArray()).take(16)
        val snapshot = File(snapshotDirectory, "${identity}-${System.currentTimeMillis()}.snapshot")
        FileOutputStream(snapshot).use { output ->
            output.write(previous)
            output.fd.sync()
        }
        writeBytes(uri, next)
        return WorkspaceSnapshot(
            byteSize = previous.size.toLong(),
            snapshotName = snapshot.name,
            beforeSha256 = sha256(previous),
            afterSha256 = sha256(next),
        )
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        } ?: error("The document provider denied write access.")
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
        // OpenDocumentTree returns a tree URI. ExternalStorageProvider accepts
        // document queries, not a query against the bare tree URI itself.
        val queryUri = documentUriForQuery(uri)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(queryUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
    }

    private fun documentUriForQuery(uri: Uri): Uri = if (DocumentsContract.isTreeUri(uri)) {
        DocumentsContract.buildDocumentUriUsingTree(uri, documentIdFor(uri))
    } else {
        uri
    }

    private fun documentIdFor(uri: Uri): String =
        runCatching { DocumentsContract.getDocumentId(uri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(uri) }

    private fun fallbackRootLabel(root: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(root)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { "Sovereign Workspace" }
    }.getOrDefault("Sovereign Workspace")

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

    private fun splitParent(path: String): Pair<String, String> {
        val name = path.substringAfterLast('/')
        require(name.isNotBlank()) { "A file or directory name is required." }
        return path.substringBeforeLast('/', "") to name
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun displayPath(path: String): String = if (path.isBlank()) "." else path
}

/** Pure validation shared by the visible editor and the agent controller. */
fun normalizeWorkspacePath(raw: String, allowRoot: Boolean = false): String {
    val candidate = raw.trim().replace('\\', '/')
    require(!candidate.startsWith('/')) { "Absolute paths are outside the connected workspace." }
    require(!Regex("^[A-Za-z]:").containsMatchIn(candidate)) {
        "Drive-qualified paths are outside the connected workspace."
    }
    val segments = candidate.split('/').filter { it.isNotBlank() && it != "." }
    require(segments.none { it == ".." }) { "Parent traversal is outside the connected workspace." }
    require(segments.none { '\u0000' in it || it.length > 255 }) { "The workspace path is invalid." }
    val normalized = segments.joinToString("/")
    require(allowRoot || normalized.isNotBlank()) { "A workspace-relative path is required." }
    return normalized
}
