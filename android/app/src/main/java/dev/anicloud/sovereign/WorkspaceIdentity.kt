package dev.anicloud.sovereign.prototype

private val WorkspaceArtifactIdPattern = Regex("^WA-[0-9A-F]{32}$")

/**
 * Controller-owned identity for one document inside a persisted SAF tree.
 *
 * The canonical path is display and recovery metadata. The artifact id and the registered
 * document URI are the authority used when a model later misspells or re-cases that path.
 */
data class WorkspaceArtifactIdentity(
    val artifactId: String,
    val rootUri: String,
    val documentUri: String,
    val canonicalPath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentSha256: String? = null,
    val active: Boolean = true,
    val updatedAt: String = "",
) {
    init {
        require(isValidWorkspaceArtifactId(artifactId)) { "Invalid workspace artifact id." }
        require(rootUri.isNotBlank() && documentUri.isNotBlank()) {
            "Workspace artifact identity requires its registered SAF authority."
        }
        normalizeWorkspacePath(canonicalPath, allowRoot = true)
        require(displayName.isNotBlank() && displayName.length <= 255) {
            "Workspace artifact display name is invalid."
        }
        require(contentSha256 == null || Regex("^[0-9a-f]{64}$").matches(contentSha256)) {
            "Workspace artifact content hash is invalid."
        }
    }
}

enum class ArtifactStatus {
    Draft,
    Verified,
    Approved,
    Superseded,
}

enum class ArtifactRelation {
    BELONGS_TO,
    DERIVED_FROM,
    SUPPORTS,
    REFERENCES,
    REPLACES,
    STORED_AS,
}

data class ArtifactVersion(
    val artifactId: String,
    val version: Int,
    val storageReference: String,
    val contentSha256: String? = null,
    val status: ArtifactStatus = ArtifactStatus.Draft,
    val createdByRunId: String = "",
    val verifiedCheckpointId: String = "",
    val supersedesVersion: Int? = null,
    val createdAt: String = "",
) {
    init {
        require(isValidWorkspaceArtifactId(artifactId)) { "Artifact version identity is invalid." }
        require(version > 0) { "Artifact versions are one-based." }
        require(storageReference.isNotBlank()) { "Artifact version storage reference is required." }
        require(contentSha256 == null || Regex("^[0-9a-f]{64}$").matches(contentSha256)) {
            "Artifact version content hash is invalid."
        }
        require(createdByRunId.isBlank() || Regex("^RUN-[0-9]{8}$").matches(createdByRunId)) {
            "Artifact version run provenance is invalid."
        }
        require(verifiedCheckpointId.isBlank() || Regex("^CP-[0-9]{6}$").matches(verifiedCheckpointId)) {
            "Artifact version checkpoint provenance is invalid."
        }
        require(supersedesVersion == null || supersedesVersion in 1 until version) {
            "Artifact version supersession is invalid."
        }
    }
}

data class ArtifactNode(
    val artifactId: String,
    val taskId: String = "",
    val logicalType: String,
    val displayName: String,
    val status: ArtifactStatus,
    val currentVersion: Int,
    val contentSha256: String? = null,
    val storageReference: String,
    val createdByRunId: String = "",
    val verifiedCheckpointId: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    init {
        require(isValidWorkspaceArtifactId(artifactId)) { "Artifact graph identity is invalid." }
        require(taskId.isBlank() || Regex("^TASK-[0-9]{3}$").matches(taskId)) {
            "Artifact graph task identity is invalid."
        }
        require(Regex("^[a-z][a-z0-9_-]{0,63}$").matches(logicalType)) {
            "Artifact graph logical type is invalid."
        }
        require(displayName.isNotBlank() && displayName.length <= 255) {
            "Artifact graph display name is invalid."
        }
        require(currentVersion > 0) { "Artifact graph versions are one-based." }
        require(storageReference.isNotBlank()) { "Artifact graph storage reference is required." }
        require(contentSha256 == null || Regex("^[0-9a-f]{64}$").matches(contentSha256)) {
            "Artifact graph content hash is invalid."
        }
        require(createdByRunId.isBlank() || Regex("^RUN-[0-9]{8}$").matches(createdByRunId)) {
            "Artifact graph run provenance is invalid."
        }
        require(verifiedCheckpointId.isBlank() || Regex("^CP-[0-9]{6}$").matches(verifiedCheckpointId)) {
            "Artifact graph checkpoint provenance is invalid."
        }
    }
}

data class ArtifactEdge(
    val sourceArtifactId: String,
    val relation: ArtifactRelation,
    val targetArtifactId: String = "",
    val targetReference: String = "",
    val createdByRunId: String = "",
    val createdAt: String = "",
) {
    init {
        require(isValidWorkspaceArtifactId(sourceArtifactId)) { "Artifact edge source is invalid." }
        require((targetArtifactId.isNotBlank()) xor (targetReference.isNotBlank())) {
            "Artifact edges require exactly one target identity or reference."
        }
        require(targetArtifactId.isBlank() || isValidWorkspaceArtifactId(targetArtifactId)) {
            "Artifact edge target is invalid."
        }
        require(createdByRunId.isBlank() || Regex("^RUN-[0-9]{8}$").matches(createdByRunId)) {
            "Artifact edge run provenance is invalid."
        }
    }
}

data class ArtifactGraphRecord(
    val node: ArtifactNode,
    val versions: List<ArtifactVersion>,
    val edges: List<ArtifactEdge>,
)

data class ArtifactVersionDecision(
    val version: Int,
    val createsVersion: Boolean,
)

fun decideArtifactVersion(
    currentVersion: Int,
    currentContentSha256: String?,
    observedContentSha256: String?,
): ArtifactVersionDecision {
    require(currentVersion > 0) { "Artifact versions are one-based." }
    listOfNotNull(currentContentSha256, observedContentSha256).forEach { hash ->
        require(Regex("^[0-9a-f]{64}$").matches(hash)) { "Artifact content hash is invalid." }
    }
    val createsVersion = currentContentSha256 != null && observedContentSha256 != null &&
        currentContentSha256 != observedContentSha256
    return ArtifactVersionDecision(
        version = if (createsVersion) currentVersion + 1 else currentVersion,
        createsVersion = createsVersion,
    )
}

/** SQLite-backed by Memory Matrix in the controller process. */
interface WorkspaceArtifactRegistry {
    fun registerWorkspaceArtifact(
        rootUri: String,
        documentUri: String,
        canonicalPath: String,
        displayName: String,
        isDirectory: Boolean,
        contentSha256: String? = null,
    ): WorkspaceArtifactIdentity

    fun workspaceArtifactById(
        rootUri: String,
        artifactId: String,
    ): WorkspaceArtifactIdentity?

    fun listWorkspaceArtifacts(
        rootUri: String,
        scopePath: String = "",
        limit: Int = 48,
    ): List<WorkspaceArtifactIdentity>

    fun retireWorkspaceArtifact(rootUri: String, artifactId: String)

    fun bindWorkspaceArtifactProvenance(
        artifactId: String,
        taskId: String,
        logicalType: String,
        displayName: String = "",
        createdByRunId: String = "",
    ): ArtifactNode

    fun verifyWorkspaceArtifacts(
        artifactIds: List<String>,
        checkpointId: String,
    )

    fun artifactGraphRecord(artifactId: String): ArtifactGraphRecord?

    fun addArtifactEdge(edge: ArtifactEdge)
}

fun isValidWorkspaceArtifactId(raw: String): Boolean = WorkspaceArtifactIdPattern.matches(raw)

fun normalizeWorkspaceArtifactId(raw: String): String {
    val artifactId = raw.trim()
    require(isValidWorkspaceArtifactId(artifactId)) {
        "Artifact ids are exact controller strings in the form WA- followed by 32 uppercase hex digits."
    }
    return artifactId
}

/**
 * Returns review candidates only. It never chooses or rewrites a path on the model's behalf.
 */
fun workspaceNameCandidates(
    requestedName: String,
    availableNames: Collection<String>,
    limit: Int = 6,
): List<String> {
    val requested = requestedName.trim()
    if (requested.isBlank()) return emptyList()
    val requestedFolded = requested.lowercase()
    val requestedExtension = requested.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return availableNames
        .asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .map { candidate ->
            val folded = candidate.lowercase()
            val distance = levenshteinDistance(requestedFolded, folded)
            val sameExtension = requestedExtension.isNotBlank() &&
                candidate.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase() == requestedExtension
            Triple(candidate, distance, sameExtension)
        }
        .filter { (_, distance, sameExtension) ->
            distance <= maxOf(2, requested.length / 3) || sameExtension
        }
        .sortedWith(compareBy<Triple<String, Int, Boolean>> { it.second }.thenBy { it.first.lowercase() })
        .take(limit.coerceIn(1, 12))
        .map { it.first }
        .toList()
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftCharacter ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightCharacter ->
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}
