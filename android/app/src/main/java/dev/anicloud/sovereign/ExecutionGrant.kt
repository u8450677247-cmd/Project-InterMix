package dev.anicloud.sovereign.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private val GrantIdPattern = Regex("^EG-[0-9A-F]{32}$")
private val GrantSafeKinds = setOf(
    ExecutionKind.InspectEnvironment,
    ExecutionKind.Build,
    ExecutionKind.Test,
    ExecutionKind.Lint,
)

enum class ExecutionGrantStatus {
    Active,
    Expired,
    Exhausted,
    Revoked,
}

data class ExecutionGrant(
    val id: String,
    val missionId: String,
    val workspaceRoot: String,
    val allowedKinds: Set<ExecutionKind>,
    val allowedCommandPrefixes: Set<String>,
    val networkAllowed: Boolean = false,
    val dependencyInstallAllowed: Boolean = false,
    val destructiveCommandsAllowed: Boolean = false,
    val maxExecutions: Int,
    val consumedExecutions: Int = 0,
    val maxWallTimeSeconds: Int,
    val reservedWallTimeSeconds: Int = 0,
    val maxOutputBytes: Long,
    val consumedOutputBytes: Long = 0L,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val status: ExecutionGrantStatus = ExecutionGrantStatus.Active,
) {
    init {
        require(GrantIdPattern.matches(id))
        require(missionId.isNotBlank())
        require(workspaceRoot.isNotBlank() && ".." !in workspaceRoot.split('/'))
        require(allowedKinds.isNotEmpty() && allowedKinds.all(GrantSafeKinds::contains))
        require(allowedCommandPrefixes.isNotEmpty() && allowedCommandPrefixes.size <= 40)
        require(allowedCommandPrefixes.all { prefix ->
            prefix.isNotBlank() && prefix.length <= 160 && prefix.none { it == '\n' || it == '\r' }
        })
        require(!networkAllowed) { "Autonomous execution grants cannot include network authority." }
        require(!dependencyInstallAllowed) {
            "Autonomous execution grants cannot include dependency installation authority."
        }
        require(!destructiveCommandsAllowed) {
            "Autonomous execution grants cannot include destructive command authority."
        }
        require(maxExecutions in 1..120 && consumedExecutions in 0..maxExecutions)
        require(maxWallTimeSeconds in 5..28_800)
        require(reservedWallTimeSeconds in 0..maxWallTimeSeconds)
        require(maxOutputBytes in 1_024L..(32L * 1024L * 1024L))
        require(consumedOutputBytes in 0L..maxOutputBytes)
        require(issuedAtEpochMillis > 0L && expiresAtEpochMillis > issuedAtEpochMillis)
    }

    fun effectiveStatus(nowEpochMillis: Long): ExecutionGrantStatus = when {
        status != ExecutionGrantStatus.Active -> status
        nowEpochMillis >= expiresAtEpochMillis -> ExecutionGrantStatus.Expired
        consumedExecutions >= maxExecutions || reservedWallTimeSeconds >= maxWallTimeSeconds ||
            consumedOutputBytes >= maxOutputBytes -> ExecutionGrantStatus.Exhausted
        else -> ExecutionGrantStatus.Active
    }
}

sealed interface ExecutionGrantAdmission {
    data class Granted(val grant: ExecutionGrant, val proposal: ExecutionProposal) : ExecutionGrantAdmission
    data class Rejected(val failure: ControllerFailure) : ExecutionGrantAdmission
}

sealed interface GrantedExecutionQueueResult {
    data class Queued(
        val actionId: Long,
        val grant: ExecutionGrant,
        val proposal: ExecutionProposal,
    ) : GrantedExecutionQueueResult

    data class Rejected(val failure: ControllerFailure) : GrantedExecutionQueueResult
}

object ExecutionGrantPolicy {
    private val shellControl = Regex("(?:[;&|<>`]|\\$\\()")

    fun admit(
        grant: ExecutionGrant,
        rawProposal: ExecutionProposal,
        missionId: String,
        nowEpochMillis: Long,
    ): ExecutionGrantAdmission {
        val proposal = runCatching { validateExecutionProposal(rawProposal) }.getOrElse { failure ->
            return rejected(ControllerErrorCode.SECURITY_VIOLATION, failure.message.orEmpty(), rawProposal)
        }
        if (grant.missionId != missionId) {
            return rejected(ControllerErrorCode.AUTHORITY_REQUIRED, "Grant belongs to another mission.", proposal)
        }
        val status = grant.effectiveStatus(nowEpochMillis)
        if (status != ExecutionGrantStatus.Active) {
            return rejected(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                "Execution grant ${grant.id} is ${status.name.lowercase()}.",
                proposal,
            )
        }
        if (proposal.kind !in grant.allowedKinds) {
            return rejected(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                "${proposal.kind.label} is outside execution grant ${grant.id}.",
                proposal,
            )
        }
        if (proposal.networkRequired || proposal.dependencies.isNotEmpty() ||
            proposal.kind == ExecutionKind.InstallDependencies
        ) {
            return rejected(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                "Network and dependency changes require a separate explicit approval.",
                proposal,
            )
        }
        if (shellControl.containsMatchIn(proposal.command)) {
            return rejected(
                ControllerErrorCode.SECURITY_VIOLATION,
                "Granted commands cannot contain shell chaining, substitution, pipes, or redirection.",
                proposal,
            )
        }
        val commandAllowed = grant.allowedCommandPrefixes.any { prefix ->
            proposal.command == prefix || proposal.command.startsWith("$prefix ")
        }
        if (!commandAllowed) {
            return rejected(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                "Command does not match an explicitly granted binary/template.",
                proposal,
            )
        }
        if (grant.reservedWallTimeSeconds + proposal.timeoutSeconds > grant.maxWallTimeSeconds) {
            return rejected(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                "Command timeout exceeds the grant's remaining wall-time budget.",
                proposal,
            )
        }
        return ExecutionGrantAdmission.Granted(
            grant = grant.copy(
                consumedExecutions = grant.consumedExecutions + 1,
                reservedWallTimeSeconds = grant.reservedWallTimeSeconds + proposal.timeoutSeconds,
            ),
            proposal = proposal,
        )
    }

    fun recordOutput(grant: ExecutionGrant, bytes: Long, nowEpochMillis: Long): ExecutionGrant {
        val consumed = (grant.consumedOutputBytes + bytes.coerceAtLeast(0L))
            .coerceAtMost(grant.maxOutputBytes)
        val updated = grant.copy(consumedOutputBytes = consumed)
        return updated.copy(status = updated.effectiveStatus(nowEpochMillis))
    }

    private fun rejected(
        code: ControllerErrorCode,
        detail: String,
        proposal: ExecutionProposal,
    ) = ExecutionGrantAdmission.Rejected(
        ControllerFailure(
            code = code,
            detail = detail.ifBlank { "Execution grant rejected the proposal." }.take(2_000),
            rejectedProposal = "${proposal.kind.wireName} ${proposal.command}".take(1_000),
        ),
    )
}

fun defaultMissionExecutionGrant(
    missionId: String,
    workspaceRoot: String,
    nowEpochMillis: Long,
): ExecutionGrant = ExecutionGrant(
    id = "EG-" + UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT),
    missionId = missionId,
    workspaceRoot = workspaceRoot,
    allowedKinds = setOf(
        ExecutionKind.InspectEnvironment,
        ExecutionKind.Build,
        ExecutionKind.Test,
        ExecutionKind.Lint,
    ),
    allowedCommandPrefixes = setOf(
        "./gradlew --offline assembleDebug",
        "./gradlew --offline test",
        "./gradlew --offline check",
        "./gradlew --offline lint",
        "gradle --offline assembleDebug",
        "gradle --offline test",
        "gradle --offline check",
        "gradle --offline lint",
        "python -m unittest",
        "python3 -m unittest",
        "python -m pytest",
        "python3 -m pytest",
        "pytest",
        "cargo test --offline",
        "cargo clippy --offline",
        "GOPROXY=off go test",
        "npm_config_offline=true npm test",
        "npm_config_offline=true npm run lint",
        "git status",
        "git diff",
        "rg",
        "ls",
    ),
    maxExecutions = 24,
    maxWallTimeSeconds = 3_600,
    maxOutputBytes = 2L * 1024L * 1024L,
    issuedAtEpochMillis = nowEpochMillis,
    expiresAtEpochMillis = nowEpochMillis + (8L * 60L * 60L * 1_000L),
)

object ExecutionGrantCodec {
    fun encode(grant: ExecutionGrant): JSONObject = JSONObject()
        .put("id", grant.id)
        .put("mission_id", grant.missionId)
        .put("workspace_root", grant.workspaceRoot)
        .put("allowed_kinds", JSONArray(grant.allowedKinds.map { it.wireName }))
        .put("allowed_command_prefixes", JSONArray(grant.allowedCommandPrefixes.sorted()))
        .put("network_allowed", grant.networkAllowed)
        .put("dependency_install_allowed", grant.dependencyInstallAllowed)
        .put("destructive_commands_allowed", grant.destructiveCommandsAllowed)
        .put("max_executions", grant.maxExecutions)
        .put("consumed_executions", grant.consumedExecutions)
        .put("max_wall_time_seconds", grant.maxWallTimeSeconds)
        .put("reserved_wall_time_seconds", grant.reservedWallTimeSeconds)
        .put("max_output_bytes", grant.maxOutputBytes)
        .put("consumed_output_bytes", grant.consumedOutputBytes)
        .put("issued_at_epoch_millis", grant.issuedAtEpochMillis)
        .put("expires_at_epoch_millis", grant.expiresAtEpochMillis)
        .put("status", grant.status.name)

    fun decode(payload: JSONObject): ExecutionGrant = ExecutionGrant(
        id = payload.getString("id"),
        missionId = payload.getString("mission_id"),
        workspaceRoot = payload.getString("workspace_root"),
        allowedKinds = payload.getJSONArray("allowed_kinds").stringValues()
            .map { requireNotNull(ExecutionKind.fromWireName(it)) { "Unknown granted execution kind: $it" } }
            .toSet(),
        allowedCommandPrefixes = payload.getJSONArray("allowed_command_prefixes").stringValues().toSet(),
        networkAllowed = payload.optBoolean("network_allowed", false),
        dependencyInstallAllowed = payload.optBoolean("dependency_install_allowed", false),
        destructiveCommandsAllowed = payload.optBoolean("destructive_commands_allowed", false),
        maxExecutions = payload.getInt("max_executions"),
        consumedExecutions = payload.optInt("consumed_executions", 0),
        maxWallTimeSeconds = payload.getInt("max_wall_time_seconds"),
        reservedWallTimeSeconds = payload.optInt("reserved_wall_time_seconds", 0),
        maxOutputBytes = payload.getLong("max_output_bytes"),
        consumedOutputBytes = payload.optLong("consumed_output_bytes", 0L),
        issuedAtEpochMillis = payload.getLong("issued_at_epoch_millis"),
        expiresAtEpochMillis = payload.getLong("expires_at_epoch_millis"),
        status = runCatching { ExecutionGrantStatus.valueOf(payload.getString("status")) }
            .getOrDefault(ExecutionGrantStatus.Revoked),
    )

    private fun JSONArray.stringValues(): List<String> = buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}
