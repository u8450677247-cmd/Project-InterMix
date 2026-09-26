package dev.anicloud.sovereign.prototype

import java.security.MessageDigest
import java.util.Locale

enum class ControllerErrorCode {
    MISSING_TARGET,
    ALREADY_EXISTS,
    MISSING_PARENT,
    STALE_ARTIFACT,
    PROVIDER_TRANSIENT,
    PATH_INVALID,
    OUTSIDE_AUTHORITY,
    TOOL_SCHEMA_INVALID,
    CONTEXT_CAPACITY,
    MODEL_RUNTIME_FAILURE,
    EXECUTION_FAILED,
    BUILD_FAILED,
    TEST_FAILED,
    SECURITY_VIOLATION,
    AUTHORITY_REQUIRED,
    SEMANTIC_AMBIGUITY,
}

data class ControllerFailure(
    val code: ControllerErrorCode,
    val detail: String,
    val evidence: String = "",
    val rejectedProposal: String = "",
) {
    init {
        require(detail.isNotBlank()) { "A controller failure requires exact evidence." }
        require(detail.length <= 2_000) { "Controller failure detail is too large." }
        require(evidence.length <= 4_000) { "Controller failure evidence is too large." }
        require(rejectedProposal.length <= 1_000) { "Rejected proposal evidence is too large." }
    }

    val signature: String
        get() = sha256(
            listOf(
                code.name,
                normalizeFailureText(rejectedProposal),
                normalizeFailureText(detail),
                normalizeFailureText(evidence),
            ).joinToString("\u001f"),
        ).take(16)

    fun withRejectedProposal(proposal: String): ControllerFailure = copy(
        rejectedProposal = proposal.replace("\u0000", "").trim().take(1_000),
    )

    companion object {
        fun fromThrowable(
            failure: Throwable,
            rejectedProposal: String = "",
        ): ControllerFailure {
            var current: Throwable? = failure
            while (current != null) {
                if (current is ControllerFailureCarrier) {
                    return current.controllerFailure.withRejectedProposal(
                        rejectedProposal.ifBlank { current.controllerFailure.rejectedProposal },
                    )
                }
                current = current.cause.takeIf { it !== current }
            }
            val detail = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                .mapNotNull(Throwable::message)
                .firstOrNull(String::isNotBlank)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(2_000)
                ?: failure::class.java.simpleName
            return classifyLegacyDetail(detail, rejectedProposal)
        }

        fun classifyLegacyDetail(
            rawDetail: String,
            rejectedProposal: String = "",
        ): ControllerFailure {
            val detail = rawDetail.replace("\u0000", "").replace(Regex("\\s+"), " ")
                .trim().take(2_000).ifBlank { "Unknown controller failure." }
            val folded = detail.lowercase(Locale.ROOT)
            val code = when {
                listOf("parent traversal", "outside the authorized", "outside its authorized", "absolute path")
                    .any(folded::contains) -> ControllerErrorCode.OUTSIDE_AUTHORITY
                listOf("symlink", "credential", "privilege escalation", "integrity violation", "corrupt")
                    .any(folded::contains) -> ControllerErrorCode.SECURITY_VIOLATION
                listOf("requires approval", "authority expansion", "not authorized", "permission needed")
                    .any(folded::contains) -> ControllerErrorCode.AUTHORITY_REQUIRED
                "access denied" in folded || "permission denied" in folded ->
                    ControllerErrorCode.AUTHORITY_REQUIRED
                "already exists" in folded -> ControllerErrorCode.ALREADY_EXISTS
                "target was absent" in folded || "missing target" in folded || "no longer exists" in folded ->
                    ControllerErrorCode.MISSING_TARGET
                "missing parent" in folded || "parent path" in folded && "missing" in folded ->
                    ControllerErrorCode.MISSING_PARENT
                "stale artifact" in folded || "unknown or retired artifact" in folded ||
                    "re-list" in folded && "artifact" in folded -> ControllerErrorCode.STALE_ARTIFACT
                "malformed" in folded || "schema" in folded || "unknown tool" in folded ->
                    ControllerErrorCode.TOOL_SCHEMA_INVALID
                "context" in folded && listOf("capacity", "limit", "too large").any(folded::contains) ->
                    ControllerErrorCode.CONTEXT_CAPACITY
                listOf("provider race", "temporarily", "transient", "timeout", "timed out")
                    .any(folded::contains) -> ControllerErrorCode.PROVIDER_TRANSIENT
                "build" in folded || "compiler" in folded || "compilation" in folded ->
                    ControllerErrorCode.BUILD_FAILED
                "test" in folded && listOf("failed", "failure", "error").any(folded::contains) ->
                    ControllerErrorCode.TEST_FAILED
                "execution" in folded || "exit code" in folded -> ControllerErrorCode.EXECUTION_FAILED
                "ambiguous" in folded || "consequential decision" in folded ->
                    ControllerErrorCode.SEMANTIC_AMBIGUITY
                listOf("invalid path", "not a directory", "unicode").any(folded::contains) ->
                    ControllerErrorCode.PATH_INVALID
                else -> ControllerErrorCode.MODEL_RUNTIME_FAILURE
            }
            return ControllerFailure(code, detail, rejectedProposal = rejectedProposal.take(1_000))
        }
    }
}

interface ControllerFailureCarrier {
    val controllerFailure: ControllerFailure
}

class ControllerException(
    override val controllerFailure: ControllerFailure,
    cause: Throwable? = null,
) : IllegalStateException(controllerFailure.detail, cause), ControllerFailureCarrier

fun controllerFailure(
    code: ControllerErrorCode,
    detail: String,
    evidence: String = "",
    rejectedProposal: String = "",
): ControllerException = ControllerException(
    ControllerFailure(
        code = code,
        detail = detail.replace("\u0000", "").trim().take(2_000),
        evidence = evidence.replace("\u0000", "").trim().take(4_000),
        rejectedProposal = rejectedProposal.replace("\u0000", "").trim().take(1_000),
    ),
)

private fun normalizeFailureText(value: String): String = value
    .replace(Regex("\\s+"), " ")
    .trim()
    .lowercase(Locale.ROOT)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
