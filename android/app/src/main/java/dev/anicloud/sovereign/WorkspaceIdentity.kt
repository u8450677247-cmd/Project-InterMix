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
