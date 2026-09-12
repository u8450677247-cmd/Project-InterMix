package dev.anicloud.sovereign.prototype

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

private const val WorkspacePreferences = "anicloud_workspace"
private const val WorkspaceRootKey = "root_uri"
private const val WorkspaceTrashReceiptKey = "last_trash_receipt"
private const val MaxEditableBytes = 2L * 1024L * 1024L
private const val MaxModelReadCharacters = 16 * 1024
private const val StoryForgeFileName = "story.md"
private const val StoryForgeHeaderMarker = "<!-- ANICLOUD_STORY_FORGE_V1 -->"
private const val StoryForgeContextCharacters = 4_096

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

data class WorkspaceTrashReceipt(
    val rootUri: String,
    val movedUri: String,
    val trashParentUri: String,
    val originalParentUri: String,
    val displayName: String,
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

    fun lastTrashReceipt(root: Uri? = storedRoot()): WorkspaceTrashReceipt? {
        val expectedRoot = root?.toString() ?: return null
        val raw = preferences.getString(WorkspaceTrashReceiptKey, null) ?: return null
        return runCatching {
            val payload = JSONObject(raw)
            WorkspaceTrashReceipt(
                rootUri = payload.getString("root_uri"),
                movedUri = payload.getString("moved_uri"),
                trashParentUri = payload.getString("trash_parent_uri"),
                originalParentUri = payload.getString("original_parent_uri"),
                displayName = payload.getString("display_name"),
            )
        }.getOrNull()?.takeIf { it.rootUri == expectedRoot }
    }

    fun clearTrashReceipt() {
        preferences.edit().remove(WorkspaceTrashReceiptKey).apply()
    }

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

    /** Explicit user creation inside the folder currently open in Workspace Lens. */
    suspend fun createUserDirectory(
        root: Uri,
        parent: Uri,
        rawName: String,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val name = normalizeWorkspaceLeafName(rawName)
        require(name != ".anicloud-trash") { "The recoverable trash name is reserved." }
        require(
            listChildrenNow(root, parent).none { it.displayName.equals(name, ignoreCase = true) },
        ) { "An entry named $name already exists in this folder." }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUriForQuery(parent),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("The document provider refused to create folder $name.")
        WorkspaceEntry(
            uri = created.toString(),
            displayName = name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            byteSize = null,
            isDirectory = true,
        )
    }

    /** Creates or safely reopens the one-file Story Forge benchmark workspace. */
    suspend fun prepareStoryForge(
        rawRootPath: String,
        rawPremise: String,
        allowCommittedChapters: Boolean = false,
    ): WorkspaceActionResult =
        withContext(Dispatchers.IO) {
            val root = storedRoot() ?: error("Connect a Workspace project before starting Story Forge.")
            val rootPath = normalizeWorkspacePath(rawRootPath)
            val premise = rawPremise.replace("\u0000", "").trim().take(16 * 1024)
            require(premise.isNotBlank()) { "Story Forge needs a premise." }
            require("ANICLOUD_CHAPTER:" !in premise.uppercase()) {
                "The premise cannot contain a controller chapter marker."
            }

            if (resolveEntry(root, rootPath) == null) createDirectory(root, rootPath)
            val storyRoot = resolveEntry(root, rootPath)
                ?: error("Story Forge could not open $rootPath after creating it.")
            require(storyRoot.isDirectory) { "Story Forge root $rootPath is not a directory." }
            val children = listChildrenNow(root, Uri.parse(storyRoot.uri))
            val existing = children.firstOrNull { it.displayName == StoryForgeFileName }
            if (existing == null) {
                require(children.isEmpty()) {
                    "Story Forge will not claim a non-empty folder without its own story.md marker."
                }
                val header = buildString {
                    appendLine(StoryForgeHeaderMarker)
                    appendLine("# AniCloudAI Story Forge")
                    appendLine()
                    appendLine("## Premise")
                    appendLine()
                    appendLine(premise)
                    appendLine()
                    append("---")
                }
                val created = createFile(root, "$rootPath/$StoryForgeFileName", header)
                return@withContext created.copy(
                    detail = "Prepared $rootPath/$StoryForgeFileName for controller-owned chapter appends.",
                )
            }
            require(!existing.isDirectory) { "$StoryForgeFileName is a directory, not a story file." }
            val existingText = readTextNow(existing)
            require(existingText.startsWith(StoryForgeHeaderMarker)) {
                "Existing $rootPath/$StoryForgeFileName is not an AniCloudAI Story Forge file."
            }
            require("## Premise\n\n$premise\n\n---" in existingText) {
                "Existing Story Forge premise does not match this durable mission checkpoint."
            }
            require(allowCommittedChapters || "<!-- ANICLOUD_CHAPTER:" !in existingText) {
                "Story Forge found committed chapters in this folder; choose a new benchmark folder."
            }
            WorkspaceActionResult(
                detail = "Recovered the marked Story Forge file at $rootPath/$StoryForgeFileName.",
                afterSha256 = sha256(existingText.toByteArray(Charsets.UTF_8)),
            )
        }

    /**
     * Appends one controller-numbered chapter with a hidden idempotency marker. If Android died
     * after the file sync but before the Matrix checkpoint, replay recovers that exact append.
     */
    suspend fun appendStoryChapter(
        rawRootPath: String,
        ordinal: Int,
        rawProposal: StoryChapterProposal,
    ): StoryChapterCommit = withContext(Dispatchers.IO) {
        val root = storedRoot() ?: error("The connected Workspace project is unavailable.")
        val rootPath = normalizeWorkspacePath(rawRootPath)
        val proposal = validateStoryChapterProposal(rawProposal)
        val storyPath = "$rootPath/$StoryForgeFileName"
        val entry = resolveEntry(root, storyPath) ?: error("Story Forge file is missing: $storyPath")
        val current = readTextNow(entry)
        require(current.startsWith(StoryForgeHeaderMarker)) {
            "Story Forge stopped because its file identity marker changed."
        }
        val marker = storyChapterMarker(ordinal)
        val markerPattern = Regex("<!-- ANICLOUD_CHAPTER:(\\d{3}) -->")
        val committedMarkers = markerPattern.findAll(current).toList()
        val committedOrdinals = committedMarkers.map { it.groupValues[1].toInt() }
        val existingStart = current.indexOf(marker)
        if (existingStart >= 0) {
            require(committedOrdinals == (1..ordinal).toList()) {
                "Story Forge stopped because its committed marker sequence is not canonical."
            }
            val nextStart = current.indexOf("<!-- ANICLOUD_CHAPTER:", existingStart + marker.length)
                .takeIf { it >= 0 } ?: current.length
            val existingSectionStart = (existingStart - 2).takeIf {
                it >= 0 && current.substring(it, existingStart) == "\n\n"
            } ?: existingStart
            val existingSection = current.substring(existingStart, nextStart).trim()
            val existingLines = existingSection.lines()
            val existingTitle = existingLines.getOrNull(1)
                ?.substringAfter(" · ", "Recovered chapter")
                ?.trim()
                .orEmpty()
            val continuityLine = existingLines.getOrNull(2).orEmpty()
            val encodedContinuity = continuityLine
                .removePrefix("<!-- ANICLOUD_CONTINUITY:")
                .removeSuffix(" -->")
            val existingContinuity = runCatching {
                Base64.getUrlDecoder().decode(encodedContinuity).toString(Charsets.UTF_8)
            }.getOrDefault("Continue from the recovered prose and preserve every established fact.")
            val existingBody = existingLines.drop(3).joinToString("\n").trim()
            val existingBytes = current.substring(existingSectionStart, nextStart)
                .toByteArray(Charsets.UTF_8).size.toLong()
            return@withContext StoryChapterCommit(
                ordinal = ordinal,
                chapterBytes = existingBytes,
                totalFileBytes = current.toByteArray(Charsets.UTF_8).size.toLong(),
                alreadyCommitted = true,
                committedTitle = existingTitle,
                committedBody = existingBody,
                committedContinuity = existingContinuity,
                detail = "Recovered the already-synced chapter marker for $storyPath.",
            )
        }
        require(committedOrdinals == (1 until ordinal).toList()) {
            "Story Forge expected a canonical sequence through the preceding chapter marker."
        }
        val displayOrdinal = ordinal.toString().padStart(3, '0')
        val encodedContinuity = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(proposal.continuity.toByteArray(Charsets.UTF_8))
        val section = buildString {
            appendLine()
            appendLine()
            appendLine(marker)
            appendLine("## Chapter $displayOrdinal · ${proposal.title}")
            appendLine("<!-- ANICLOUD_CONTINUITY:$encodedContinuity -->")
            appendLine()
            append(proposal.body)
            appendLine()
        }
        val next = current + section
        require(next.toByteArray(Charsets.UTF_8).size <= MaxEditableBytes) {
            "Story Forge reached the 2 MiB workspace file ceiling."
        }
        writeTextNow(entry, next)
        StoryChapterCommit(
            ordinal = ordinal,
            chapterBytes = section.toByteArray(Charsets.UTF_8).size.toLong(),
            totalFileBytes = next.toByteArray(Charsets.UTF_8).size.toLong(),
            alreadyCommitted = false,
            committedTitle = proposal.title,
            committedBody = proposal.body,
            committedContinuity = proposal.continuity,
            detail = "Synced chapter $displayOrdinal to $storyPath with a pre-write snapshot.",
        )
    }

    /** Latest prose only: controller markers and chapter numbers never enter the model prompt. */
    suspend fun storyForgeTail(rawRootPath: String): String = withContext(Dispatchers.IO) {
        val root = storedRoot() ?: return@withContext ""
        val rootPath = normalizeWorkspacePath(rawRootPath)
        val entry = resolveEntry(root, "$rootPath/$StoryForgeFileName") ?: return@withContext ""
        val text = readTextNow(entry)
        val markerStart = text.lastIndexOf("<!-- ANICLOUD_CHAPTER:")
        if (markerStart < 0) return@withContext ""
        val chapterSection = text.substring(markerStart).lineSequence().drop(3).joinToString("\n").trim()
        chapterSection.takeLast(StoryForgeContextCharacters)
    }

    suspend fun moveToTrash(
        root: Uri,
        parent: Uri,
        entry: WorkspaceEntry,
    ): WorkspaceTrashReceipt = withContext(Dispatchers.IO) {
        moveToTrashNow(root, parent, entry)
    }

    suspend fun restoreFromTrash(root: Uri, receipt: WorkspaceTrashReceipt) =
        withContext(Dispatchers.IO) { restoreFromTrashNow(root, receipt) }

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
        require(content.isNotBlank()) {
            "Refused an empty write for $path. File mutations require complete non-empty content."
        }
        val entry = resolveEntry(root, path) ?: error("Workspace file not found: $path")
        require(!entry.isDirectory) { "Cannot replace a directory with text." }
        val snapshot = writeTextNow(entry, content)
        val bytes = content.toByteArray(Charsets.UTF_8)
        return WorkspaceActionResult(
            detail = "Wrote and verified $path (${bytes.size} bytes); " +
                "pre-write snapshot ${snapshot.snapshotName} retained.",
            beforeSha256 = snapshot.beforeSha256,
            afterSha256 = snapshot.afterSha256,
        )
    }

    private fun createFile(root: Uri, path: String, content: String): WorkspaceActionResult {
        require(content.isNotBlank()) {
            "Refused an empty file at $path. Create the file with its complete non-empty content."
        }
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
        val persisted = readBytes(created)
        check(persisted.contentEquals(bytes)) {
            "The document provider created $path but did not persist its exact content; " +
                "the incomplete entry may remain for user review."
        }
        return WorkspaceActionResult(
            detail = "Created and verified $path (${bytes.size} bytes).",
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

    /**
     * User-driven removal is a reversible move inside the granted tree. The controller still
     * has no delete tool, and provider refusal leaves the source in place.
     */
    private fun moveToTrashNow(
        root: Uri,
        parent: Uri,
        entry: WorkspaceEntry,
    ): WorkspaceTrashReceipt {
        require(entry.displayName != ".anicloud-trash") { "The recoverable trash folder is protected." }
        require(listChildrenNow(root, parent).any { it.uri == entry.uri }) {
            "The selected entry is no longer inside the open folder. Refresh and review it again."
        }
        val trash = ensureTrashDirectory(root)
        require(
            listChildrenNow(root, Uri.parse(trash.uri)).none { it.displayName == entry.displayName },
        ) {
            "Trash already contains ${entry.displayName}; restore or rename it before trying again."
        }
        val sourceParent = documentUriForQuery(parent)
        val trashParent = documentUriForQuery(Uri.parse(trash.uri))
        val moved = DocumentsContract.moveDocument(
            context.contentResolver,
            Uri.parse(entry.uri),
            sourceParent,
            trashParent,
        ) ?: error("The document provider does not support recoverable moves for this entry.")
        val receipt = WorkspaceTrashReceipt(
            rootUri = root.toString(),
            movedUri = moved.toString(),
            trashParentUri = trashParent.toString(),
            originalParentUri = sourceParent.toString(),
            displayName = entry.displayName,
        )
        persistTrashReceipt(receipt)
        return receipt
    }

    private fun restoreFromTrashNow(root: Uri, receipt: WorkspaceTrashReceipt) {
        require(receipt.rootUri == root.toString()) { "This undo receipt belongs to a different project tree." }
        val trashParent = Uri.parse(receipt.trashParentUri)
        val originalParent = Uri.parse(receipt.originalParentUri)
        require(
            listChildrenNow(root, originalParent).none { it.displayName == receipt.displayName },
        ) {
            "Restore stopped because ${receipt.displayName} now exists in the original folder."
        }
        require(listChildrenNow(root, trashParent).any { it.uri == receipt.movedUri }) {
            "The trashed entry is no longer available to restore."
        }
        DocumentsContract.moveDocument(
            context.contentResolver,
            Uri.parse(receipt.movedUri),
            trashParent,
            originalParent,
        ) ?: error("The document provider refused to restore ${receipt.displayName}.")
        clearTrashReceipt()
    }

    private fun ensureTrashDirectory(root: Uri): WorkspaceEntry {
        listChildrenNow(root, root).firstOrNull { entry ->
            entry.isDirectory && entry.displayName == ".anicloud-trash"
        }?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUriForQuery(root),
            DocumentsContract.Document.MIME_TYPE_DIR,
            ".anicloud-trash",
        ) ?: error("The document provider refused to create recoverable project trash.")
        return WorkspaceEntry(
            uri = created.toString(),
            displayName = ".anicloud-trash",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            byteSize = null,
            isDirectory = true,
        )
    }

    private fun persistTrashReceipt(receipt: WorkspaceTrashReceipt) {
        val payload = JSONObject()
            .put("root_uri", receipt.rootUri)
            .put("moved_uri", receipt.movedUri)
            .put("trash_parent_uri", receipt.trashParentUri)
            .put("original_parent_uri", receipt.originalParentUri)
            .put("display_name", receipt.displayName)
        preferences.edit().putString(WorkspaceTrashReceiptKey, payload.toString()).apply()
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
        val persisted = readBytes(uri)
        if (!persisted.contentEquals(next)) {
            val restored = runCatching {
                writeBytes(uri, previous)
                readBytes(uri).contentEquals(previous)
            }.getOrDefault(false)
            error(
                if (restored) {
                    "The document provider did not persist the exact generated bytes; " +
                        "the pre-write content was restored."
                } else {
                    "The document provider did not persist the exact generated bytes and rollback " +
                        "could not be verified. Review the file before continuing."
                },
            )
        }
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

    private fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use(::readBoundedBytes)
            ?: error("Android could not verify the persisted file bytes.")

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

/**
 * Anchors a model-proposed path exactly once beneath a mission root. Small local models often
 * restate the root with dash/underscore drift; collapse only that immediate alias instead of
 * allowing recursive `root/root_alias/file` paths to consume the bounded action budget.
 */
fun scopeWorkspaceMissionPath(rawPath: String, rawRootPath: String): String {
    val root = normalizeWorkspacePath(rawRootPath)
    val proposed = normalizeWorkspacePath(rawPath, allowRoot = true)
    if (proposed.isBlank() || proposed == root) return root

    val rootSegments = root.split('/')
    val proposedSegments = proposed.split('/')
    var relative = if (proposedSegments.take(rootSegments.size) == rootSegments) {
        proposedSegments.drop(rootSegments.size)
    } else {
        proposedSegments
    }
    val canonicalRootLeaf = rootSegments.last().filter { it.isLetterOrDigit() }.lowercase()
    if (
        relative.size > 1 &&
        relative.first().filter { it.isLetterOrDigit() }.lowercase() == canonicalRootLeaf
    ) {
        relative = relative.drop(1)
    }
    return normalizeWorkspacePath((rootSegments + relative).joinToString("/"))
}

/** A user-entered folder name is one leaf, never a path or controller instruction. */
fun normalizeWorkspaceLeafName(raw: String): String {
    val name = raw.replace("\u0000", "").trim()
    require(name.isNotBlank()) { "Enter a folder name." }
    require(name !in setOf(".", "..")) { "Choose a normal folder name." }
    require('/' !in name && '\\' !in name) { "Folder names cannot contain path separators." }
    require(name.length <= 120) { "Folder names are limited to 120 characters." }
    require(name.none { it.code < 0x20 || it.code == 0x7f }) {
        "Folder names cannot contain control characters."
    }
    return name
}
