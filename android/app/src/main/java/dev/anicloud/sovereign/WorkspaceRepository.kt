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
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException

private const val WorkspacePreferences = "anicloud_workspace"
private const val WorkspaceRootKey = "root_uri"
private const val WorkspaceTrashReceiptKey = "last_trash_receipt"
private const val MaxEditableBytes = 2L * 1024L * 1024L
private const val MaxModelReadCharacters = 16 * 1024
private const val StoryForgeFileName = "story.md"
private const val StoryForgeHeaderMarker = "<!-- ANICLOUD_STORY_FORGE_V1 -->"
private const val StoryForgeContextCharacters = 4_096
private const val MaxArtifactRecoveryEntries = 2_048
private const val MaxArtifactRecoveryDepth = 16
internal const val MaxMissionParentRepairs = 8

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
    val snapshotName: String? = null,
    val beforeSha256: String? = null,
    val afterSha256: String? = null,
    val artifactId: String? = null,
)

data class WorkspaceCompensationResult(
    val compensated: Boolean,
    val detail: String,
)

enum class WorkspaceCompensationPolicy {
    NoSideEffect,
    RestoreSnapshot,
    RecordUncompensated,
}

fun workspaceCompensationPolicy(
    proposal: WorkspaceActionProposal,
    result: WorkspaceActionResult,
): WorkspaceCompensationPolicy = when (proposal.kind) {
    WorkspaceActionKind.ListFiles,
    WorkspaceActionKind.ReadFile,
    -> WorkspaceCompensationPolicy.NoSideEffect
    WorkspaceActionKind.WriteFile -> if (
        result.snapshotName != null && result.beforeSha256 != null && result.afterSha256 != null
    ) {
        WorkspaceCompensationPolicy.RestoreSnapshot
    } else {
        WorkspaceCompensationPolicy.RecordUncompensated
    }
    WorkspaceActionKind.CreateFile,
    WorkspaceActionKind.CreateDirectory,
    -> WorkspaceCompensationPolicy.RecordUncompensated
}

data class WorkspaceActionReconciliation(
    val proposal: WorkspaceActionProposal,
    val detail: String = "",
    val prerequisiteDirectories: List<String> = emptyList(),
)

private data class ResolvedRegisteredArtifact(
    val identity: WorkspaceArtifactIdentity,
    val entry: WorkspaceEntry,
    val recoveredPath: Boolean,
)

private data class LocatedWorkspaceEntry(
    val canonicalPath: String,
    val entry: WorkspaceEntry,
)

/**
 * A scoped mission has already granted write authority. If the model asks to replace a target
 * that Android has verified does not exist, creation is the lossless, non-destructive repair.
 * Creation is never upgraded to replacement because that could overwrite existing user data.
 */
fun reconcileScopedWorkspaceAction(
    proposal: WorkspaceActionProposal,
    targetExists: Boolean,
): WorkspaceActionReconciliation = if (
    proposal.kind == WorkspaceActionKind.WriteFile && !targetExists
) {
    WorkspaceActionReconciliation(
        proposal = proposal.copy(kind = WorkspaceActionKind.CreateFile),
        detail = "Android verified the target was absent and reconciled write_file to create_file.",
    )
} else {
    WorkspaceActionReconciliation(proposal)
}

/**
 * Returns every directory between an immutable mission root and one proposed target. The root and
 * target are excluded. This is ordering data only; the repository still verifies each path against
 * the Storage Access Framework immediately before performing a repair.
 */
fun orderedMissionParentPaths(rawTargetPath: String, rawMissionRootPath: String): List<String> {
    val targetPath = normalizeWorkspacePath(rawTargetPath)
    val missionRootPath = normalizeWorkspacePath(rawMissionRootPath)
    require(
        targetPath == missionRootPath || targetPath.startsWith("$missionRootPath/"),
    ) { "The proposed target is outside the authorized mission root." }
    if (targetPath == missionRootPath) return emptyList()

    val targetSegments = targetPath.split('/')
    val rootSegments = missionRootPath.split('/')
    return (rootSegments.size + 1 until targetSegments.size).map { segmentCount ->
        targetSegments.take(segmentCount).joinToString("/")
    }
}

/** Validates that a recovery plan is bounded, unique, and in exact ancestor order. */
fun validateMissionParentRepairPlan(
    rawTargetPath: String,
    rawMissionRootPath: String,
    rawRepairPaths: List<String>,
): List<String> {
    val candidates = orderedMissionParentPaths(rawTargetPath, rawMissionRootPath)
    val repairs = rawRepairPaths.map { normalizeWorkspacePath(it) }
    require(repairs.size <= MaxMissionParentRepairs) {
        "A mission action may repair at most $MaxMissionParentRepairs missing parent directories."
    }
    require(repairs.distinct().size == repairs.size) {
        "A mission parent-repair plan cannot repeat a directory."
    }
    require(repairs == candidates.takeLast(repairs.size)) {
        "Mission parent repairs must be the exact missing ancestor suffix in deterministic order."
    }
    return repairs
}

/** A persisted Storage Access Framework tree is the complete authority boundary. */
class WorkspaceRepository(
    private val context: Context,
    private val artifactRegistry: WorkspaceArtifactRegistry? = null,
) {
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

    /** Prepares the exact directory covered by one Work Session grant before inference begins. */
    suspend fun prepareWorkspaceMission(rawRootPath: String): WorkspaceActionResult =
        withContext(Dispatchers.IO) {
            val root = storedRoot() ?: error("Connect a Workspace project before starting a Work Session.")
            val rootPath = normalizeWorkspacePath(rawRootPath)
            var currentPath = ""
            var createdCount = 0
            rootPath.split('/').forEach { segment ->
                currentPath = listOf(currentPath, segment).filter(String::isNotBlank).joinToString("/")
                val existing = resolveEntry(root, currentPath)
                if (existing == null) {
                    createDirectory(root, currentPath)
                    val created = resolveEntry(root, currentPath)
                        ?: error("The document provider did not expose the new mission folder $currentPath.")
                    check(created.isDirectory) {
                        "The document provider did not persist $currentPath as a directory."
                    }
                    registerArtifact(root, currentPath, created)
                    createdCount++
                } else {
                    require(existing.isDirectory) {
                        "Work Session root crosses a file at $currentPath. Choose a different mission folder."
                    }
                    registerArtifact(root, currentPath, existing)
                }
            }
            WorkspaceActionResult(
                detail = if (createdCount == 0) {
                    "Verified existing Work Session root $rootPath."
                } else {
                    "Created and verified Work Session root $rootPath ($createdCount directories)."
                },
            )
        }

    /** Resolves only the safe missing-write ambiguity inside an already active mission grant. */
    suspend fun reconcileMissionAction(
        proposal: WorkspaceActionProposal,
        rawMissionRootPath: String,
    ): WorkspaceActionReconciliation = withContext(Dispatchers.IO) {
        val root = storedRoot() ?: error("No workspace is connected.")
        val path = normalizeWorkspacePath(proposal.path)
        val missionRootPath = normalizeWorkspacePath(rawMissionRootPath)
        require(path == missionRootPath || path.startsWith("$missionRootPath/")) {
            "The proposed target is outside the authorized mission root."
        }
        val missionRoot = resolveEntry(root, missionRootPath)
            ?: error("The authorized mission root is no longer available at $missionRootPath.")
        require(missionRoot.isDirectory) { "The authorized mission root is no longer a directory." }

        val reconciled = reconcileScopedWorkspaceAction(
            proposal = proposal.copy(path = path),
            targetExists = resolveEntry(root, path) != null,
        )
        if (
            reconciled.proposal.kind !in setOf(
                WorkspaceActionKind.CreateFile,
                WorkspaceActionKind.CreateDirectory,
            )
        ) {
            return@withContext reconciled
        }

        val missingParents = orderedMissionParentPaths(path, missionRootPath).filter { parentPath ->
            val parent = resolveEntry(root, parentPath)
            if (parent != null) {
                require(parent.isDirectory) {
                    "Mission parent recovery crosses a file at $parentPath."
                }
            }
            parent == null
        }
        val repairPlan = validateMissionParentRepairPlan(path, missionRootPath, missingParents)
        reconciled.copy(
            detail = listOf(
                reconciled.detail,
                repairPlan.takeIf { it.isNotEmpty() }?.let {
                    "Planned ${it.size} missing parent ${if (it.size == 1) "directory" else "directories"} " +
                        "in deterministic order: ${it.joinToString(" -> ")}."
                }.orEmpty(),
            ).filter(String::isNotBlank).joinToString(" "),
            prerequisiteDirectories = repairPlan,
        )
    }

    /**
     * Resolves an exact controller-owned handle before scope validation or approval queuing.
     * A model-supplied path never overrides the registered identity.
     */
    suspend fun resolveActionIdentity(
        proposal: WorkspaceActionProposal,
    ): WorkspaceActionReconciliation = withContext(Dispatchers.IO) {
        if (proposal.artifactId.isBlank()) return@withContext WorkspaceActionReconciliation(proposal)
        require(
            proposal.kind in setOf(
                WorkspaceActionKind.ListFiles,
                WorkspaceActionKind.ReadFile,
                WorkspaceActionKind.WriteFile,
            ),
        ) { "Artifact handles address existing files or directories only." }
        val root = storedRoot() ?: error("No workspace is connected.")
        val artifactId = normalizeWorkspaceArtifactId(proposal.artifactId)
        val resolved = resolveRegisteredArtifact(root, artifactId)
        val suppliedPath = proposal.path.takeIf(String::isNotBlank)?.let {
            normalizeWorkspacePath(it, allowRoot = proposal.kind == WorkspaceActionKind.ListFiles)
        }
        WorkspaceActionReconciliation(
            proposal = proposal.copy(
                path = resolved.identity.canonicalPath,
                artifactId = artifactId,
            ),
            detail = buildString {
                append("Resolved the controller-owned artifact to canonical path ")
                append(displayPath(resolved.identity.canonicalPath))
                append('.')
                if (resolved.recoveredPath) append(" The persisted handle survived a moved path.")
                if (suppliedPath != null && suppliedPath != resolved.identity.canonicalPath) {
                    append(" Ignored the non-authoritative supplied path ")
                    append(displayPath(suppliedPath))
                    append('.')
                }
            },
        )
    }

    /** Explicit user creation inside the folder currently open in Workspace Lens. */
    suspend fun createUserDirectory(
        root: Uri,
        parent: Uri,
        rawName: String,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val name = normalizeWorkspaceLeafName(rawName)
        require(!name.equals(".anicloud-trash", ignoreCase = true)) {
            "The recoverable trash name is reserved."
        }
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

    /** Explicit user creation may start empty; model-authored file actions remain non-empty. */
    suspend fun createUserTextFile(
        root: Uri,
        parent: Uri,
        rawName: String,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val name = normalizeWorkspaceLeafName(rawName)
        require(!name.equals(".anicloud-trash", ignoreCase = true)) {
            "The recoverable trash name is reserved."
        }
        require(
            listChildrenNow(root, parent).none { it.displayName.equals(name, ignoreCase = true) },
        ) { "An entry named $name already exists in this folder." }
        val mimeType = mimeForName(name)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUriForQuery(parent),
            mimeType,
            name,
        ) ?: error("The document provider refused to create file $name.")
        val emptyContent = ByteArray(0)
        writeBytes(created, emptyContent)
        val persisted = readBytes(created)
        check(persisted.contentEquals(emptyContent)) {
            "The document provider created $name but did not persist an exact empty text file; " +
                "the incomplete entry may remain for user review."
        }
        val persistedName = queryDisplayName(created)
            ?: error("The document provider created the file but did not expose its display name.")
        check(persistedName == name) {
            "The document provider changed $name to $persistedName; review the created entry before retrying."
        }
        WorkspaceEntry(
            uri = created.toString(),
            displayName = persistedName,
            mimeType = mimeType,
            byteSize = 0L,
            isDirectory = false,
        )
    }

    /** Renames one reviewed direct child and verifies the provider's exact resulting identity. */
    suspend fun renameUserEntry(
        root: Uri,
        parent: Uri,
        entry: WorkspaceEntry,
        rawName: String,
    ): WorkspaceEntry = withContext(Dispatchers.IO) {
        val name = normalizeWorkspaceLeafName(rawName)
        require(!entry.displayName.equals(".anicloud-trash", ignoreCase = true)) {
            "The recoverable trash folder is protected."
        }
        require(!name.equals(".anicloud-trash", ignoreCase = true)) {
            "The recoverable trash name is reserved."
        }
        require(name != entry.displayName) { "Enter a different name before renaming." }
        val siblings = listChildrenNow(root, parent)
        val reviewedMatches = siblings.filter { it.uri == entry.uri }
        require(reviewedMatches.size == 1) {
            "The reviewed entry is no longer in the open folder. Refresh before renaming."
        }
        val reviewed = reviewedMatches.single()
        require(
            reviewed.displayName == entry.displayName && reviewed.isDirectory == entry.isDirectory,
        ) { "The reviewed entry changed before rename. Refresh and review it again." }
        require(
            siblings.none {
                it.uri != entry.uri && it.displayName.equals(name, ignoreCase = true)
            },
        ) { "An entry named $name already exists in this folder." }

        val renamedUri = DocumentsContract.renameDocument(
            context.contentResolver,
            Uri.parse(entry.uri),
            name,
        ) ?: error("The document provider refused to rename ${entry.displayName}.")
        val exactMatches = listChildrenNow(root, parent).filter { it.displayName == name }
        check(exactMatches.size == 1) {
            "The document provider did not expose exactly one entry named $name after renaming."
        }
        val renamed = exactMatches.single()
        val listedUri = Uri.parse(renamed.uri)
        check(
            listedUri.authority == renamedUri.authority &&
                documentIdFor(listedUri) == documentIdFor(renamedUri),
        ) {
            "The document provider returned a different document identity after renaming."
        }
        check(renamed.isDirectory == entry.isDirectory) {
            "The document provider changed the entry type while renaming."
        }
        renamed
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
    suspend fun controllerContext(scopePath: String = ""): String = withContext(Dispatchers.IO) {
        val root = storedRoot()
        if (root == null) {
            "[WORKSPACE]\nDisconnected. Ask the user to open Workspace and CONNECT PROJECT."
        } else {
            val label = runCatching { queryDisplayName(root) }.getOrNull() ?: fallbackRootLabel(root)
            val normalizedScope = normalizeWorkspacePath(scopePath, allowRoot = true)
            val knownArtifacts = artifactRegistry
                ?.listWorkspaceArtifacts(root.toString(), normalizedScope, limit = 48)
                .orEmpty()
            buildString {
                appendLine("[WORKSPACE]")
                appendLine("Connected root: $label")
                append(
                    "Tools: list_files and read_file run inside this root. " +
                        "In ordinary Chat, create_file, write_file, and create_directory require visible " +
                        "approval. A controller-owned active Work Session may separately grant those " +
                        "actions inside one exact scoped root. " +
                        "Deletion and access outside this root are unavailable.",
                )
                if (knownArtifacts.isNotEmpty()) {
                    appendLine()
                    appendLine("[CONTROLLER ARTIFACT INDEX · EXACT IDS]")
                    knownArtifacts.forEach { artifact ->
                        append(if (artifact.isDirectory) "DIR  " else "FILE ")
                        append(artifact.artifactId)
                        append("  ")
                        appendLine(displayPath(artifact.canonicalPath))
                    }
                    append("Use an exact artifact_id for an existing entry; never respell or fuzzy-match it.")
                }
            }
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
            executeApprovedNow(root, proposal.copy(path = normalized))
        }

    /**
     * Executes one already-authorized mission mutation plus its bounded mechanical prerequisites.
     * Parent creation is ordered, re-verified at execution time, and reported as one consolidated
     * result. Ordinary approval-queue writes continue to use [executeApproved] without this repair.
     */
    suspend fun executeMissionApproved(
        reconciliation: WorkspaceActionReconciliation,
        rawMissionRootPath: String,
    ): WorkspaceActionResult = withContext(Dispatchers.IO) {
        val proposal = reconciliation.proposal
        require(proposal.kind.requiresApproval) { "This mission action does not mutate the workspace." }
        val root = storedRoot() ?: error("No workspace is connected.")
        val missionRootPath = normalizeWorkspacePath(rawMissionRootPath)
        val normalized = proposal.copy(path = normalizeWorkspacePath(proposal.path))
        val plannedParents = validateMissionParentRepairPlan(
            rawTargetPath = normalized.path,
            rawMissionRootPath = missionRootPath,
            rawRepairPaths = reconciliation.prerequisiteDirectories,
        )
        val allParents = orderedMissionParentPaths(normalized.path, missionRootPath)
        val createdParents = mutableListOf<String>()
        val reusedParents = mutableListOf<String>()
        val parentResults = mutableListOf<WorkspaceActionResult>()

        try {
            plannedParents.forEach { parentPath ->
                val existing = resolveEntry(root, parentPath)
                if (existing == null) {
                    parentResults += createDirectory(root, parentPath)
                    val created = resolveEntry(root, parentPath)
                        ?: error("The document provider did not expose repaired parent $parentPath.")
                    check(created.isDirectory) {
                        "The document provider did not persist repaired parent $parentPath as a directory."
                    }
                    createdParents += parentPath
                } else {
                    require(existing.isDirectory) {
                        "Mission parent recovery found a file at $parentPath."
                    }
                    registerArtifact(root, parentPath, existing)
                    reusedParents += parentPath
                }
            }
            allParents.forEach { parentPath ->
                val parent = resolveEntry(root, parentPath)
                    ?: error("Mission parent $parentPath changed before the target action.")
                require(parent.isDirectory) { "Mission parent $parentPath is not a directory." }
            }

            val result = executeApprovedNow(root, normalized)
            if (plannedParents.isEmpty()) return@withContext result
            result.copy(
                detail = buildString {
                    append("Executed an ordered mission bundle: ")
                    if (createdParents.isNotEmpty()) {
                        append("created ${createdParents.joinToString(" -> ")}; ")
                    }
                    if (reusedParents.isNotEmpty()) {
                        append("reused ${reusedParents.joinToString(" -> ")}; ")
                    }
                    append("then ${result.detail.replaceFirstChar { it.lowercaseChar() }}")
                },
                toolContent = buildString {
                    parentResults.forEach { parentResult ->
                        appendLine(parentResult.detail)
                        if (parentResult.toolContent.isNotBlank()) appendLine(parentResult.toolContent)
                    }
                    append(result.toolContent)
                }.trim(),
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val progress = buildList {
                if (createdParents.isNotEmpty()) add("created ${createdParents.joinToString(" -> ")}")
                if (reusedParents.isNotEmpty()) add("reused ${reusedParents.joinToString(" -> ")}")
            }.joinToString("; ").ifBlank { "completed no prerequisite directories" }
            throw IllegalStateException(
                "Mission bundle stopped after it $progress. " +
                    (failure.message ?: failure::class.java.simpleName),
                failure,
            )
        }
    }

    /**
     * Best-effort semantic rollback for a previously verified transaction operation. Reads have no
     * side effect. A replacement can be restored only while the exact post-write hash is still
     * present. Creations are deliberately retained and reported unresolved because silently deleting
     * user-visible provider entries would widen controller authority.
     */
    suspend fun compensateMissionAction(
        proposal: WorkspaceActionProposal,
        result: WorkspaceActionResult,
        rawMissionRootPath: String,
    ): WorkspaceCompensationResult = withContext(Dispatchers.IO) {
        val missionRootPath = normalizeWorkspacePath(rawMissionRootPath)
        val path = normalizeWorkspacePath(proposal.path, allowRoot = proposal.kind == WorkspaceActionKind.ListFiles)
        require(path == missionRootPath || path.startsWith("$missionRootPath/")) {
            "Transaction compensation cannot leave its authorized mission root."
        }
        when (workspaceCompensationPolicy(proposal, result)) {
            WorkspaceCompensationPolicy.NoSideEffect -> WorkspaceCompensationResult(
                compensated = true,
                detail = "No compensation was needed for read-only operation ${proposal.kind.wireName} $path.",
            )
            WorkspaceCompensationPolicy.RestoreSnapshot -> restoreMissionSnapshot(path, result)
            WorkspaceCompensationPolicy.RecordUncompensated -> WorkspaceCompensationResult(
                compensated = false,
                detail = "The verified ${proposal.kind.wireName} side effect at $path remains for review; " +
                    "automatic deletion is outside controller authority.",
            )
        }
    }

    private fun executeApprovedNow(
        root: Uri,
        proposal: WorkspaceActionProposal,
    ): WorkspaceActionResult = when (proposal.kind) {
        WorkspaceActionKind.CreateFile -> createFile(root, proposal.path, proposal.content)
        WorkspaceActionKind.WriteFile -> writePath(root, proposal.path, proposal.content)
        WorkspaceActionKind.CreateDirectory -> createDirectory(root, proposal.path)
        else -> error("Read-only actions execute without an approval record.")
    }

    private fun listPath(root: Uri, rawPath: String): WorkspaceActionResult {
        val path = normalizeWorkspacePath(rawPath, allowRoot = true)
        val directory = resolveEntry(root, path)
            ?: error(missingPathDetail(root, path))
        require(directory.isDirectory) { "The requested list path is not a directory." }
        val entries = listChildrenNow(root, Uri.parse(directory.uri)).take(200)
        val directoryIdentity = registerArtifact(root, path, directory)
        val registeredEntries = entries.map { entry ->
            val childPath = listOf(path, entry.displayName)
                .filter(String::isNotBlank)
                .joinToString("/")
            entry to registerArtifact(root, childPath, entry)
        }
        val body = buildString {
            appendLine("Directory: ${displayPath(path)}")
            directoryIdentity?.let { appendLine("Directory artifact_id: ${it.artifactId}") }
            registeredEntries.forEach { (entry, identity) ->
                append(if (entry.isDirectory) "DIR  " else "FILE ")
                append(entry.displayName)
                entry.byteSize?.takeIf { !entry.isDirectory }?.let { append("  ($it bytes)") }
                identity?.let { append("  [artifact_id=${it.artifactId}]") }
                appendLine()
            }
        }.take(24 * 1024)
        return WorkspaceActionResult(
            detail = "Listed ${entries.size} exact entries in ${displayPath(path)}" +
                if (artifactRegistry == null) "." else "; stable artifact ids registered.",
            toolContent = body,
            artifactId = directoryIdentity?.artifactId,
        )
    }

    private fun readPath(root: Uri, rawPath: String): WorkspaceActionResult {
        val path = normalizeWorkspacePath(rawPath)
        val entry = resolveEntry(root, path) ?: error(missingPathDetail(root, path))
        val full = readTextNow(entry)
        val contentHash = sha256(full.toByteArray(Charsets.UTF_8))
        val identity = registerArtifact(root, path, entry, contentHash)
        val clipped = full.take(MaxModelReadCharacters)
        val suffix = if (full.length > MaxModelReadCharacters) {
            "\n[TRUNCATED: ${full.length - MaxModelReadCharacters} more characters were not sent to the model]"
        } else {
            ""
        }
        return WorkspaceActionResult(
            detail = "Read $path (${full.length} characters).",
            toolContent = buildString {
                appendLine("File: $path")
                identity?.let { appendLine("Artifact ID: ${it.artifactId}") }
                appendLine("SHA-256: $contentHash")
                appendLine("---")
                append(clipped)
                append(suffix)
            },
            afterSha256 = contentHash,
            artifactId = identity?.artifactId,
        )
    }

    private fun writePath(root: Uri, path: String, content: String): WorkspaceActionResult {
        require(content.isNotBlank()) {
            "Refused an empty write for $path. File mutations require complete non-empty content."
        }
        val entry = resolveEntry(root, path) ?: error(missingPathDetail(root, path))
        require(!entry.isDirectory) { "Cannot replace a directory with text." }
        val snapshot = writeTextNow(entry, content)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val identity = registerArtifact(root, path, entry, snapshot.afterSha256)
        return WorkspaceActionResult(
            detail = "Wrote and verified $path (${bytes.size} bytes); " +
                "pre-write snapshot ${snapshot.snapshotName} retained.",
            toolContent = identity?.let { "Artifact ID: ${it.artifactId}" }.orEmpty(),
            snapshotName = snapshot.snapshotName,
            beforeSha256 = snapshot.beforeSha256,
            afterSha256 = snapshot.afterSha256,
            artifactId = identity?.artifactId,
        )
    }

    private fun createFile(root: Uri, path: String, content: String): WorkspaceActionResult {
        require(content.isNotBlank()) {
            "Refused an empty file at $path. Create the file with its complete non-empty content."
        }
        val (parentPath, name) = splitParent(path)
        val parent = resolveEntry(root, parentPath)
            ?: error(missingPathDetail(root, parentPath))
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
        val entry = WorkspaceEntry(
            uri = created.toString(),
            displayName = name,
            mimeType = mimeForName(name),
            byteSize = bytes.size.toLong(),
            isDirectory = false,
        )
        val contentHash = sha256(bytes)
        val identity = registerArtifact(root, path, entry, contentHash)
        return WorkspaceActionResult(
            detail = "Created and verified $path (${bytes.size} bytes).",
            toolContent = identity?.let { "Artifact ID: ${it.artifactId}" }.orEmpty(),
            afterSha256 = contentHash,
            artifactId = identity?.artifactId,
        )
    }

    private fun createDirectory(root: Uri, path: String): WorkspaceActionResult {
        val (parentPath, name) = splitParent(path)
        val parent = resolveEntry(root, parentPath)
            ?: error(missingPathDetail(root, parentPath))
        require(parent.isDirectory) { "The parent path is not a directory." }
        require(resolveEntry(root, path) == null) { "A workspace entry already exists at $path." }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            Uri.parse(parent.uri),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("The document provider refused to create $path.")
        val entry = WorkspaceEntry(
            uri = created.toString(),
            displayName = name,
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            byteSize = null,
            isDirectory = true,
        )
        val identity = registerArtifact(root, path, entry)
        return WorkspaceActionResult(
            detail = "Created directory $path.",
            toolContent = identity?.let { "Artifact ID: ${it.artifactId}" }.orEmpty(),
            artifactId = identity?.artifactId,
        )
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

    private fun registerArtifact(
        root: Uri,
        canonicalPath: String,
        entry: WorkspaceEntry,
        contentSha256: String? = null,
    ): WorkspaceArtifactIdentity? = artifactRegistry?.registerWorkspaceArtifact(
        rootUri = root.toString(),
        documentUri = entry.uri,
        canonicalPath = normalizeWorkspacePath(canonicalPath, allowRoot = true),
        displayName = entry.displayName,
        isDirectory = entry.isDirectory,
        contentSha256 = contentSha256,
    )

    private fun resolveRegisteredArtifact(
        root: Uri,
        artifactId: String,
    ): ResolvedRegisteredArtifact {
        val registry = artifactRegistry
            ?: error("Artifact handles are unavailable until Memory Matrix is connected.")
        val identity = registry.workspaceArtifactById(root.toString(), artifactId)
            ?: error("Unknown or retired artifact id $artifactId. Re-list the exact directory.")
        val exact = resolveEntry(root, identity.canonicalPath)
        if (exact?.uri == identity.documentUri) {
            require(exact.isDirectory == identity.isDirectory) {
                "Artifact $artifactId changed type; re-list before continuing."
            }
            return ResolvedRegisteredArtifact(identity, exact, recoveredPath = false)
        }

        val recovered = locateDocumentUri(root, identity.documentUri)
        if (recovered != null) {
            require(recovered.entry.isDirectory == identity.isDirectory) {
                "Artifact $artifactId changed type while moving; re-list before continuing."
            }
            val updated = registerArtifact(
                root = root,
                canonicalPath = recovered.canonicalPath,
                entry = recovered.entry,
                contentSha256 = identity.contentSha256,
            ) ?: error("Artifact registry became unavailable during recovery.")
            require(updated.artifactId == identity.artifactId) {
                "Artifact identity changed during bounded move recovery."
            }
            return ResolvedRegisteredArtifact(updated, recovered.entry, recoveredPath = true)
        }

        registry.retireWorkspaceArtifact(root.toString(), artifactId)
        error(
            "Artifact $artifactId is no longer reachable inside the granted workspace. " +
                "Its last canonical path was ${displayPath(identity.canonicalPath)}; re-list before continuing.",
        )
    }

    /** Bounded and invoked only when an exact registered path no longer resolves to its document. */
    private fun locateDocumentUri(root: Uri, targetDocumentUri: String): LocatedWorkspaceEntry? {
        val rootEntry = resolveEntry(root, "") ?: return null
        val queue = ArrayDeque<Pair<LocatedWorkspaceEntry, Int>>()
        queue.add(LocatedWorkspaceEntry("", rootEntry) to 0)
        var observed = 0
        while (queue.isNotEmpty() && observed < MaxArtifactRecoveryEntries) {
            val (located, depth) = queue.removeFirst()
            observed++
            if (located.entry.uri == targetDocumentUri) return located
            if (!located.entry.isDirectory || depth >= MaxArtifactRecoveryDepth) continue
            val remaining = MaxArtifactRecoveryEntries - observed
            if (remaining <= 0) break
            listChildrenNow(root, Uri.parse(located.entry.uri)).take(remaining).forEach { child ->
                val childPath = listOf(located.canonicalPath, child.displayName)
                    .filter(String::isNotBlank)
                    .joinToString("/")
                val childLocation = LocatedWorkspaceEntry(childPath, child)
                if (child.uri == targetDocumentUri) return childLocation
                if (child.isDirectory) queue.add(childLocation to depth + 1)
            }
        }
        return null
    }

    private fun missingPathDetail(root: Uri, path: String): String {
        val normalized = normalizeWorkspacePath(path, allowRoot = true)
        if (normalized.isBlank()) return "The connected workspace root is unavailable."
        var parentPath = ""
        var parent = resolveEntry(root, parentPath)
            ?: return "The connected workspace root is unavailable."
        for (segment in normalized.split('/')) {
            if (!parent.isDirectory) {
                return "Workspace path crosses the file ${displayPath(parentPath)}; no candidate was selected."
            }
            val children = listChildrenNow(root, Uri.parse(parent.uri))
            val exact = children.firstOrNull { it.displayName == segment }
            if (exact == null) {
                val candidates = workspaceNameCandidates(segment, children.map(WorkspaceEntry::displayName))
                return buildString {
                    append("Workspace path not found exactly: ")
                    append(displayPath(normalized))
                    append(". No candidate was selected")
                    if (candidates.isNotEmpty()) {
                        append("; candidates inside ")
                        append(displayPath(parentPath))
                        append(": ")
                        append(candidates.joinToString())
                    }
                    append(". Re-list the directory and reuse its exact artifact_id.")
                }
            }
            parentPath = listOf(parentPath, segment).filter(String::isNotBlank).joinToString("/")
            parent = exact
        }
        return "Workspace path is unavailable: ${displayPath(normalized)}."
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
        require(isEditableWorkspaceText(entry)) { "This file type is not enabled for text access." }
        val bytes = context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use(::readBoundedBytes)
            ?: error("Android could not open this file.")
        require(bytes.size <= MaxEditableBytes) { "Files above 2 MiB are not enabled." }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeTextNow(entry: WorkspaceEntry, text: String): WorkspaceSnapshot {
        require(!entry.isDirectory && isEditableWorkspaceText(entry)) {
            "Only reviewed text files can be written."
        }
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

    private fun restoreMissionSnapshot(
        path: String,
        result: WorkspaceActionResult,
    ): WorkspaceCompensationResult {
        val snapshotName = result.snapshotName
            ?: return WorkspaceCompensationResult(false, "No pre-write snapshot was recorded for $path.")
        val beforeSha256 = result.beforeSha256
            ?: return WorkspaceCompensationResult(false, "No pre-write hash was recorded for $path.")
        val afterSha256 = result.afterSha256
            ?: return WorkspaceCompensationResult(false, "No post-write hash was recorded for $path.")
        require(Regex("^[a-f0-9]{16}-[0-9]+\\.snapshot$").matches(snapshotName)) {
            "Snapshot identity is not controller-generated."
        }
        val root = storedRoot()
            ?: return WorkspaceCompensationResult(false, "Workspace authority is unavailable for rollback.")
        val entry = resolveEntry(root, path)
            ?: return WorkspaceCompensationResult(false, "The written target moved before rollback: $path.")
        if (entry.isDirectory) {
            return WorkspaceCompensationResult(false, "The written target changed type before rollback: $path.")
        }
        val current = readBytes(Uri.parse(entry.uri))
        if (sha256(current) != afterSha256) {
            return WorkspaceCompensationResult(
                false,
                "Rollback stopped because $path changed after the transaction write.",
            )
        }
        val snapshotDirectory = File(context.filesDir, "workspace_snapshots")
        val snapshot = File(snapshotDirectory, snapshotName)
        val canonicalDirectory = snapshotDirectory.canonicalFile
        val canonicalSnapshot = snapshot.canonicalFile
        require(canonicalSnapshot.parentFile == canonicalDirectory) {
            "Snapshot identity escaped controller storage."
        }
        if (!canonicalSnapshot.isFile || canonicalSnapshot.length() > MaxEditableBytes) {
            return WorkspaceCompensationResult(false, "The bounded pre-write snapshot is unavailable.")
        }
        val previous = canonicalSnapshot.readBytes()
        if (sha256(previous) != beforeSha256) {
            return WorkspaceCompensationResult(false, "The pre-write snapshot failed integrity verification.")
        }
        writeBytes(Uri.parse(entry.uri), previous)
        val restored = readBytes(Uri.parse(entry.uri))
        if (!restored.contentEquals(previous)) {
            return WorkspaceCompensationResult(false, "The document provider did not verify rollback for $path.")
        }
        registerArtifact(root, path, entry, beforeSha256)
        return WorkspaceCompensationResult(
            true,
            "Restored $path to verified pre-write SHA-256 $beforeSha256.",
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

/** Shared by provider I/O and UI transitions so renamed binary files never remain editable. */
fun isEditableWorkspaceText(entry: WorkspaceEntry): Boolean {
    if (entry.isDirectory) return false
    if (entry.mimeType.startsWith("text/")) return true
    if (entry.mimeType in setOf("application/json", "application/xml", "application/javascript")) return true
    val extension = entry.displayName.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "c", "cc", "cpp", "css", "go", "gradle", "h", "hpp", "html", "java", "js",
        "json", "kt", "kts", "lua", "md", "py", "rs", "sh", "sql", "toml", "ts",
        "tsx", "txt", "xml", "yaml", "yml",
    )
}

/** Pure validation shared by the visible editor and the agent controller. */
fun normalizeWorkspacePath(raw: String, allowRoot: Boolean = false): String {
    val candidate = Normalizer.normalize(raw.trim().replace('\\', '/'), Normalizer.Form.NFC)
    require(!candidate.startsWith('/')) { "Absolute paths are outside the connected workspace." }
    require(!Regex("^[A-Za-z]:").containsMatchIn(candidate)) {
        "Drive-qualified paths are outside the connected workspace."
    }
    val segments = candidate.split('/').filter { it.isNotBlank() && it != "." }
    require(segments.none { it == ".." }) { "Parent traversal is outside the connected workspace." }
    require(segments.none { segment ->
        segment.any { it == '\u0000' || it.code < 0x20 || it.code == 0x7f } ||
            segment.toByteArray(Charsets.UTF_8).size > 255
    }) { "The workspace path contains a control character or oversized Unicode segment." }
    val normalized = segments.joinToString("/")
    require(normalized.toByteArray(Charsets.UTF_8).size <= 4_096) { "The workspace path is too long." }
    require(allowRoot || normalized.isNotBlank()) { "A workspace-relative path is required." }
    return normalized
}

/**
 * Anchors a model-proposed path beneath the immutable mission root. Only exact root repetitions
 * collapse. Similar-looking case, punctuation, or spelling variants fail closed so controller
 * identity never depends on a generative model's fuzzy string repair.
 */
fun scopeWorkspaceMissionPath(rawPath: String, rawRootPath: String): String {
    val root = normalizeWorkspacePath(rawRootPath)
    val proposed = normalizeWorkspacePath(rawPath, allowRoot = true)
    if (proposed.isBlank() || proposed == root) return root

    val rootSegments = root.split('/')
    val proposedSegments = proposed.split('/')
    var relative = proposedSegments
    while (relative.take(rootSegments.size) == rootSegments) {
        relative = relative.drop(rootSegments.size)
    }
    val canonicalRootLeaf = rootSegments.last().filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    if (
        relative.isNotEmpty() &&
        proposedSegments.take(rootSegments.size) != rootSegments &&
        relative.first().filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT) == canonicalRootLeaf
    ) {
        error(
            "Mission path repeats or resembles the immutable root without its exact canonical " +
                "prefix. Use a path relative to $root or repeat that exact root.",
        )
    }
    return normalizeWorkspacePath((rootSegments + relative).joinToString("/"))
}

/** A user-entered file or folder name is one leaf, never a path or controller instruction. */
fun normalizeWorkspaceLeafName(raw: String): String {
    val name = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
    require(name.isNotBlank()) { "Enter a file or folder name." }
    require(name !in setOf(".", "..")) { "Choose a normal file or folder name." }
    require('/' !in name && '\\' !in name) { "Names cannot contain path separators." }
    require(name.toByteArray(Charsets.UTF_8).size <= 255) { "Names are limited to 255 UTF-8 bytes." }
    require(name.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }) {
        "Names cannot contain control characters."
    }
    return name
}
