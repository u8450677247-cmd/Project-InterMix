package dev.anicloud.sovereign.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal const val MaximumMissionTasks = 120
internal const val MaximumMissionObjectiveCharacters = 256 * 1024
internal const val DefaultMissionLogicalStepBudget = 120
internal const val DefaultMissionToolOperationBudget = 1_440
internal const val MaximumMissionTransactionOperations = 8
internal const val MaximumMissionRecoveryAttempts = 3
internal const val MissionTaskPromptCharacters = 5_600

enum class MissionTaskStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Blocked,
}

/** One controller-owned, user-visible unit compiled from the immutable objective. */
data class MissionManifestTask(
    val id: String,
    val ordinal: Int,
    val displayLabel: String,
    val instructions: String,
    val workspaceDirectory: String,
    val status: MissionTaskStatus = MissionTaskStatus.Pending,
    val artifactIds: List<String> = emptyList(),
    val attempts: Int = 0,
    val lastVerifiedCheckpoint: String = "",
    val failure: String = "",
) {
    init {
        require(ordinal in 1..MaximumMissionTasks) { "Mission task ordinal is outside the bounded manifest." }
        require(id == "TASK-${ordinal.toString().padStart(3, '0')}") { "Mission task id is not canonical." }
        require(displayLabel.isNotBlank() && displayLabel.length <= 240) { "Mission task label is invalid." }
        require(instructions.isNotBlank()) { "Mission task instructions are required." }
        require(Regex("^[0-9]{3}_[a-z0-9][a-z0-9_-]{0,79}$").matches(workspaceDirectory)) {
            "Mission task workspace directory is not controller-owned."
        }
        require(artifactIds.distinct().size == artifactIds.size) { "Mission task artifacts must be unique." }
        require(artifactIds.all(::isValidWorkspaceArtifactId)) { "Mission task has an invalid artifact id." }
        require(attempts >= 0) { "Mission task attempts cannot be negative." }
    }
}

/**
 * Durable structured work state. The original objective remains separately persisted as immutable
 * evidence; this manifest owns task counting, selection, progress, and completion truth.
 */
data class MissionManifest(
    val missionId: String,
    val originalObjectiveSha256: String,
    val globalConstraints: String,
    val tasks: List<MissionManifestTask>,
    val currentTaskId: String,
) {
    init {
        require(missionId.isNotBlank()) { "Mission manifest requires its mission id." }
        require(Regex("^[0-9a-f]{64}$").matches(originalObjectiveSha256)) {
            "Mission manifest objective hash is invalid."
        }
        require(tasks.isNotEmpty() && tasks.size <= MaximumMissionTasks) {
            "Mission manifest must contain between 1 and $MaximumMissionTasks tasks."
        }
        require(tasks.map(MissionManifestTask::ordinal) == (1..tasks.size).toList()) {
            "Mission manifest task ordinals must be contiguous."
        }
        require(tasks.map(MissionManifestTask::id).distinct().size == tasks.size) {
            "Mission manifest task ids must be unique."
        }
        require(currentTaskId.isBlank() || tasks.any { it.id == currentTaskId }) {
            "Mission manifest current task is unknown."
        }
    }

    val expectedTaskCount: Int get() = tasks.size
    val completedTaskCount: Int get() = tasks.count { it.status == MissionTaskStatus.Completed }
    val complete: Boolean get() = completedTaskCount == expectedTaskCount
    val currentTask: MissionManifestTask?
        get() = tasks.firstOrNull { it.id == currentTaskId }

    fun task(taskId: String): MissionManifestTask =
        tasks.firstOrNull { it.id == taskId } ?: error("Unknown mission task $taskId.")
}

/** Deterministic compiler for explicit numbered TASK/JOB/ITEM/TARGET manifests. */
object MissionManifestCompiler {
    private data class Marker(
        val lineIndex: Int,
        val ordinal: Int,
        val label: String,
    )

    private val markerPattern = Regex(
        "^\\s*(?:#{1,6}\\s*)?(?:[-*]\\s*)?(?:(\\d{1,3})[.)]|" +
            "(?:TASK|JOB|ITEM|TARGET)\\s*[-#:]*\\s*(\\d{1,3})\\s*(?:[.)_:#-]\\s*)?)" +
            "\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun compile(missionId: String, objective: String): MissionManifest {
        val immutableObjective = objective.replace("\u0000", "").trim()
        require(immutableObjective.isNotBlank()) { "A mission objective is required." }
        val lines = immutableObjective.lines()
        val candidates = lines.mapIndexedNotNull { lineIndex, line ->
            val match = markerPattern.matchEntire(line) ?: return@mapIndexedNotNull null
            val ordinal = (match.groupValues[1].ifBlank { match.groupValues[2] }).toIntOrNull()
                ?: return@mapIndexedNotNull null
            Marker(lineIndex, ordinal, match.groupValues[3].trim())
        }
        val markers = longestContiguousManifest(candidates)
        require(markers.size <= MaximumMissionTasks) {
            "The objective declares ${markers.size} tasks; the bounded manifest supports at most " +
                "$MaximumMissionTasks and will not silently discard later work."
        }
        val tasks = if (markers.size >= 2) {
            markers.mapIndexed { index, marker ->
                val end = markers.getOrNull(index + 1)?.lineIndex ?: lines.size
                val block = buildString {
                    append(marker.label)
                    val continuation = lines.subList(marker.lineIndex + 1, end).joinToString("\n").trim()
                    if (continuation.isNotBlank()) append("\n").append(continuation)
                }.trim()
                task(marker.ordinal, marker.label, block)
            }
        } else {
            listOf(task(1, firstUsefulLabel(lines), immutableObjective))
        }
        val prefixEnd = markers.firstOrNull()?.lineIndex ?: 0
        val constraints = if (markers.size >= 2) {
            lines.take(prefixEnd).joinToString("\n").trim()
        } else {
            ""
        }
        return MissionManifest(
            missionId = missionId,
            originalObjectiveSha256 = missionObjectiveSha256(immutableObjective),
            globalConstraints = constraints,
            tasks = tasks,
            currentTaskId = tasks.first().id,
        )
    }

    private fun longestContiguousManifest(candidates: List<Marker>): List<Marker> {
        var best = emptyList<Marker>()
        candidates.forEachIndexed { start, marker ->
            if (marker.ordinal != 1) return@forEachIndexed
            val run = mutableListOf(marker)
            var expected = 2
            for (candidate in candidates.drop(start + 1)) {
                if (candidate.ordinal == expected) {
                    run += candidate
                    expected++
                } else if (candidate.ordinal == 1) {
                    break
                }
            }
            if (run.size > best.size) best = run
        }
        return best
    }

    private fun firstUsefulLabel(lines: List<String>): String = lines
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.removePrefix("#")
        ?.trim()
        ?.take(240)
        ?.ifBlank { "Mission objective" }
        ?: "Mission objective"

    private fun task(ordinal: Int, rawLabel: String, instructions: String): MissionManifestTask {
        val boundedOrdinal = ordinal.coerceIn(1, MaximumMissionTasks)
        val label = rawLabel.replace("\u0000", "").trim().take(240).ifBlank { "Task $boundedOrdinal" }
        return MissionManifestTask(
            id = "TASK-${boundedOrdinal.toString().padStart(3, '0')}",
            ordinal = boundedOrdinal,
            displayLabel = label,
            instructions = instructions.replace("\u0000", "").trim(),
            workspaceDirectory = "${boundedOrdinal.toString().padStart(3, '0')}_${asciiSlug(label)}",
        )
    }

    private fun asciiSlug(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        return decomposed
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(64)
            .ifBlank { "task" }
    }

}

fun missionObjectiveSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * Selects prompt material from the durable source by task identity. Long task sources are never
 * mistaken for complete prefixes: Android keeps both ends, identifies the omitted evidence, and
 * retains the exact full source in the manifest for restart and later controller selection.
 */
fun missionTaskSourceForPrompt(
    task: MissionManifestTask,
    characterLimit: Int = MissionTaskPromptCharacters,
): String = durableMissionSourceForPrompt(
    identity = "TASK SOURCE ${task.id}",
    source = task.instructions,
    characterLimit = characterLimit,
)

fun durableMissionSourceForPrompt(
    identity: String,
    source: String,
    characterLimit: Int,
): String {
    require(characterLimit >= 800) { "Mission task prompt windows must retain useful evidence." }
    require(identity.isNotBlank()) { "Durable mission source identity is required." }
    val provenance = missionObjectiveSha256(source)
    val header = "[DURABLE $identity · SHA-256 $provenance]"
    if (header.length + 1 + source.length <= characterLimit) return "$header\n$source"

    val omittedTemplate = "\n[... %d durable characters elided for $identity; full source retained ...]\n"
    val minimumMarker = omittedTemplate.format(0)
    val available = (characterLimit - header.length - 1 - minimumMarker.length).coerceAtLeast(2)
    val headCharacters = (available * 0.56).toInt()
    val tailCharacters = available - headCharacters
    val omittedCharacters = (source.length - headCharacters - tailCharacters).coerceAtLeast(0)
    val marker = omittedTemplate.format(omittedCharacters)
    val adjustedAvailable = (characterLimit - header.length - 1 - marker.length).coerceAtLeast(2)
    val adjustedHead = (adjustedAvailable * 0.56).toInt()
    val adjustedTail = adjustedAvailable - adjustedHead
    return buildString(characterLimit) {
        appendLine(header)
        append(source.take(adjustedHead))
        append(marker)
        append(source.takeLast(adjustedTail))
    }
}

enum class MissionCheckpointKind(val wireName: String) {
    LogicalStep("logical_step"),
    TaskComplete("task_complete"),
    ;

    companion object {
        fun fromWireName(raw: String): MissionCheckpointKind? = entries.firstOrNull {
            it.wireName == raw.trim().lowercase(Locale.ROOT)
        }
    }
}

data class MissionCheckpointProposal(
    val kind: MissionCheckpointKind,
    val taskId: String,
    val summary: String,
    val artifactIds: List<String> = emptyList(),
)

/** Resolves a semantic artifact request without asking the model to invent a filesystem name. */
fun resolveManifestArtifactPath(
    manifest: MissionManifest,
    rootPath: String,
    taskId: String,
    artifactType: String,
): String {
    val task = manifest.task(taskId)
    val type = artifactType.trim().lowercase(Locale.ROOT)
    require(Regex("^[a-z][a-z0-9_-]{0,63}$").matches(type)) {
        "Artifact type must be a stable lowercase semantic token."
    }
    // Keep every accepted semantic token one-to-one on disk: replacing '-' with '_' would make
    // distinct types such as `cover-letter` and `cover_letter` collide at the same path.
    val fileName = "$type.md"
    return normalizeWorkspacePath("${normalizeWorkspacePath(rootPath)}/${task.workspaceDirectory}/$fileName")
}

object MissionManifestCodec {
    fun encode(manifest: MissionManifest): String = JSONObject()
        .put("mission_id", manifest.missionId)
        .put("original_objective_sha256", manifest.originalObjectiveSha256)
        .put("global_constraints", manifest.globalConstraints)
        .put("current_task_id", manifest.currentTaskId)
        .put("tasks", JSONArray().apply {
            manifest.tasks.forEach { task ->
                put(JSONObject()
                    .put("id", task.id)
                    .put("ordinal", task.ordinal)
                    .put("display_label", task.displayLabel)
                    .put("instructions", task.instructions)
                    .put("workspace_directory", task.workspaceDirectory)
                    .put("status", task.status.name)
                    .put("artifact_ids", JSONArray(task.artifactIds))
                    .put("attempts", task.attempts)
                    .put("last_verified_checkpoint", task.lastVerifiedCheckpoint)
                    .put("failure", task.failure))
            }
        })
        .toString()

    fun decode(raw: String): MissionManifest {
        val payload = JSONObject(raw)
        val taskPayloads = payload.getJSONArray("tasks")
        val tasks = buildList {
            for (index in 0 until taskPayloads.length()) {
                val task = taskPayloads.getJSONObject(index)
                val artifacts = task.optJSONArray("artifact_ids") ?: JSONArray()
                add(MissionManifestTask(
                    id = task.getString("id"),
                    ordinal = task.getInt("ordinal"),
                    displayLabel = task.getString("display_label"),
                    instructions = task.getString("instructions"),
                    workspaceDirectory = task.getString("workspace_directory"),
                    status = runCatching { MissionTaskStatus.valueOf(task.optString("status")) }
                        .getOrDefault(MissionTaskStatus.Pending),
                    artifactIds = buildList {
                        for (artifactIndex in 0 until artifacts.length()) {
                            artifacts.optString(artifactIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }.distinct(),
                    attempts = task.optInt("attempts", 0).coerceAtLeast(0),
                    lastVerifiedCheckpoint = task.optString("last_verified_checkpoint").take(2_000),
                    failure = task.optString("failure").take(2_000),
                ))
            }
        }
        return MissionManifest(
            missionId = payload.getString("mission_id"),
            originalObjectiveSha256 = payload.getString("original_objective_sha256"),
            globalConstraints = payload.optString("global_constraints"),
            tasks = tasks,
            currentTaskId = payload.optString("current_task_id"),
        )
    }
}

/**
 * Version-tolerant, process-independent persistence for the complete mission cursor. Keeping this
 * codec out of SQLite makes restart semantics executable in local JVM tests and keeps provenance
 * validation in the deterministic controller rather than in UI lifecycle code.
 */
object AgentMissionCheckpointCodec {
    fun encode(mission: AgentMissionCheckpoint): String = JSONObject()
        .put("id", mission.id)
        .put("root_path", mission.rootPath)
        .put("objective", mission.objective)
        .put("mode", mission.mode.name)
        .put("started_message_id", mission.startedMessageId)
        .put("status", mission.status.name)
        .put("completed_actions", mission.completedActions)
        .put("logical_agent_steps", mission.logicalStepsCompleted)
        .put("max_actions", mission.maxActions)
        .put("tool_operations", mission.toolOperations)
        .put("max_tool_operations", mission.maxToolOperations)
        .put("tool_failures", mission.toolFailures)
        .put("inference_cycles", mission.inferenceCycles)
        .put("recoveries", mission.recoveries)
        .put("tool_operations_at_last_checkpoint", mission.toolOperationsAtLastCheckpoint)
        .put("last_tool_succeeded", mission.lastToolSucceeded)
        .put("written_bytes", mission.writtenBytes)
        .put("max_write_bytes", mission.maxWriteBytes)
        .put("plan_kind", mission.planKind)
        .put("plan_state", mission.planState)
        .put("evolution_state", mission.evolutionState)
        .put(
            "mission_manifest",
            mission.missionManifest?.let { JSONObject(MissionManifestCodec.encode(it)) }
                ?: JSONObject.NULL,
        )
        .put(
            "execution_ledger",
            mission.executionLedger?.let(AgentExecutionLedgerCodec::encode) ?: JSONObject.NULL,
        )
        .put("manifest_tasks_completed", mission.missionManifest?.completedTaskCount ?: 0)
        .put("guidance", JSONArray(mission.guidance))
        .put("action_trail", JSONArray(mission.actionTrail))
        .put("recovery_trail", JSONArray(mission.recoveryTrail))
        .put("last_controller_error_code", mission.lastControllerErrorCode?.name ?: "")
        .put("last_recovery_disposition", mission.lastRecoveryDisposition?.name ?: "")
        .put("last_verified_checkpoint", mission.lastVerifiedCheckpoint)
        .put("last_action", mission.lastAction)
        .put("last_result", mission.lastResult)
        .put("updated_at", mission.updatedAt)
        .toString()

    fun decode(raw: String): AgentMissionCheckpoint {
        val payload = JSONObject(raw)
        val id = payload.optString("id").ifBlank { "LF-RECOVERED" }
        val objective = payload.getString("objective").replace("\u0000", "").trim()
        require(objective.isNotBlank()) { "A recovered mission requires its original objective." }
        require(objective.length <= MaximumMissionObjectiveCharacters) {
            "The recovered mission objective exceeds the durable evidence limit."
        }
        val status = runCatching { AgentMissionStatus.valueOf(payload.optString("status")) }
            .getOrDefault(AgentMissionStatus.Paused)
        val planKind = payload.optString("plan_kind", WorkspaceMissionKind)
            .takeIf { it in setOf(WorkspaceMissionKind, StoryForgeMissionKind, EvolutionForgeMissionKind) }
            ?: WorkspaceMissionKind
        val manifest = payload.optJSONObject("mission_manifest")?.let {
            MissionManifestCodec.decode(it.toString())
        } ?: if (planKind == WorkspaceMissionKind) {
            MissionManifestCompiler.compile(id, objective).let { compiled ->
                compiled.copy(
                    tasks = compiled.tasks.mapIndexed { index, task ->
                        task.copy(
                            status = when {
                                status == AgentMissionStatus.Completed -> MissionTaskStatus.Completed
                                index == 0 -> MissionTaskStatus.InProgress
                                else -> MissionTaskStatus.Pending
                            },
                        )
                    },
                    currentTaskId = if (status == AgentMissionStatus.Completed) "" else compiled.currentTaskId,
                )
            }
        } else {
            null
        }
        manifest?.let {
            require(it.missionId == id) { "Recovered mission manifest identity does not match its checkpoint." }
            require(it.originalObjectiveSha256 == missionObjectiveSha256(objective)) {
                "Recovered mission objective does not match its immutable manifest provenance."
            }
            val inProgressTasks = it.tasks.filter { task -> task.status == MissionTaskStatus.InProgress }
            if (it.complete) {
                require(it.currentTaskId.isBlank() && inProgressTasks.isEmpty()) {
                    "A complete recovered manifest cannot retain an active task cursor."
                }
            } else {
                require(it.currentTaskId.isNotBlank() && inProgressTasks.singleOrNull()?.id == it.currentTaskId) {
                    "An incomplete recovered manifest requires exactly one matching in-progress task."
                }
                val currentOrdinal = it.task(it.currentTaskId).ordinal
                require(it.tasks.filter { task -> task.ordinal < currentOrdinal }
                    .all { task -> task.status == MissionTaskStatus.Completed }) {
                    "Recovered manifest tasks before the cursor are not exactly complete."
                }
                require(it.tasks.filter { task -> task.ordinal > currentOrdinal }
                    .all { task -> task.status == MissionTaskStatus.Pending }) {
                    "Recovered manifest tasks after the cursor are not exactly pending."
                }
            }
            if (payload.has("manifest_tasks_completed")) {
                require(payload.optInt("manifest_tasks_completed", -1) == it.completedTaskCount) {
                    "Recovered manifest task count does not match its controller-owned task ledger."
                }
            }
        }
        val maxActions = payload.optInt("max_actions", DefaultMissionLogicalStepBudget)
            .coerceIn(1, DefaultMissionLogicalStepBudget)
        if (planKind == WorkspaceMissionKind) {
            require((manifest?.expectedTaskCount ?: 0) <= maxActions) {
                "Recovered manifest task count exceeds its logical-step grant."
            }
        }
        val legacyCompletedActions = payload.optInt("completed_actions", 0).coerceAtLeast(0)
        val completedActions = if (payload.has("logical_agent_steps")) {
            payload.optInt("logical_agent_steps", 0).coerceAtLeast(0)
        } else if (planKind == WorkspaceMissionKind) {
            // Before the reliability convergence, completed_actions counted filesystem calls.
            // Active upgrade installs migrate those calls to the tool ledger instead of falsely
            // claiming human-level progress. A historical completed mission stays read-only.
            if (status == AgentMissionStatus.Completed) {
                legacyCompletedActions.coerceAtMost(maxActions)
            } else {
                0
            }
        } else {
            legacyCompletedActions
        }
        require(completedActions <= maxActions) { "Recovered logical-step count exceeds its grant." }
        val maxToolOperations = payload.optInt(
            "max_tool_operations",
            DefaultMissionToolOperationBudget,
        ).coerceIn(DefaultMissionLogicalStepBudget, DefaultMissionToolOperationBudget * 2)
        val toolOperations = if (payload.has("tool_operations")) {
            payload.optInt("tool_operations", 0).coerceAtLeast(0)
        } else if (planKind == WorkspaceMissionKind) {
            legacyCompletedActions
        } else {
            0
        }
        require(toolOperations <= maxToolOperations) { "Recovered tool-operation count exceeds its grant." }
        if (planKind == WorkspaceMissionKind) {
            require((manifest?.completedTaskCount ?: 0) <= completedActions) {
                "Recovered task completion exceeds the verified logical-step ledger."
            }
            require(completedActions <= toolOperations) {
                "Recovered logical-step progress exceeds available tool evidence."
            }
        }
        val toolOperationsAtLastCheckpoint = payload
            .optInt("tool_operations_at_last_checkpoint", 0)
            .coerceAtLeast(0)
        require(toolOperationsAtLastCheckpoint <= toolOperations) {
            "Recovered checkpoint refers to unavailable tool evidence."
        }
        val maxWriteBytes = payload.optLong("max_write_bytes", 1024L * 1024L)
            .coerceIn(64L * 1024L, 2L * 1024L * 1024L)
        val writtenBytes = payload.optLong("written_bytes", 0L).coerceAtLeast(0L)
        require(writtenBytes <= maxWriteBytes) { "Recovered write count exceeds its grant." }
        require(status != AgentMissionStatus.Completed || planKind != WorkspaceMissionKind || manifest?.complete == true) {
            "A recovered manifest mission cannot be complete before all tasks are verified."
        }
        val executionLedger = payload.optJSONObject("execution_ledger")?.let(
            AgentExecutionLedgerCodec::decode,
        ) ?: AgentExecutionController.initial(
            missionId = id,
            taskId = manifest?.currentTaskId.orEmpty(),
            logicalStepsCompleted = completedActions,
        )
        require(executionLedger.missionId == id) {
            "Recovered execution ledger belongs to another mission."
        }
        if (manifest?.complete != true) {
            require(executionLedger.currentTaskId == manifest?.currentTaskId || planKind != WorkspaceMissionKind) {
                "Recovered execution ledger task does not match the manifest cursor."
            }
        }
        return AgentMissionCheckpoint(
            id = id,
            rootPath = normalizeWorkspacePath(payload.getString("root_path")),
            objective = objective,
            mode = runCatching { AnswerMode.valueOf(payload.optString("mode")) }
                .getOrDefault(AnswerMode.Quality),
            startedMessageId = payload.optLong("started_message_id", 0L).coerceAtLeast(0L),
            status = status,
            completedActions = completedActions,
            maxActions = maxActions,
            toolOperations = toolOperations,
            maxToolOperations = maxToolOperations,
            toolFailures = payload.optInt("tool_failures", 0).coerceAtLeast(0),
            inferenceCycles = payload.optInt("inference_cycles", 0).coerceAtLeast(0),
            recoveries = payload.optInt("recoveries", 0).coerceAtLeast(0),
            toolOperationsAtLastCheckpoint = toolOperationsAtLastCheckpoint,
            lastToolSucceeded = payload.optBoolean("last_tool_succeeded", false),
            writtenBytes = writtenBytes,
            maxWriteBytes = maxWriteBytes,
            planKind = planKind,
            planState = payload.optString("plan_state").replace("\u0000", "").trim().take(1_200),
            evolutionState = payload.optString("evolution_state").replace("\u0000", "").trim()
                .take(512 * 1024),
            missionManifest = manifest,
            executionLedger = executionLedger,
            guidance = payload.stringList("guidance", 12, 2_000),
            actionTrail = payload.stringList("action_trail", 240, 800),
            recoveryTrail = payload.stringList("recovery_trail", 24, 32),
            lastControllerErrorCode = payload.optString("last_controller_error_code")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { ControllerErrorCode.valueOf(it) }.getOrNull() },
            lastRecoveryDisposition = payload.optString("last_recovery_disposition")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { MissionRecoveryDisposition.valueOf(it) }.getOrNull() },
            lastVerifiedCheckpoint = payload.optString("last_verified_checkpoint").take(2_000),
            lastAction = payload.optString("last_action").take(700),
            lastResult = payload.optString("last_result").take(2_000),
            updatedAt = payload.optString("updated_at"),
        )
    }

    private fun JSONObject.stringList(key: String, limit: Int, itemLimit: Int): List<String> {
        val entries = optJSONArray(key) ?: JSONArray()
        return buildList {
            for (index in 0 until entries.length()) {
                entries.optString(index).replace("\u0000", "").trim()
                    .takeIf(String::isNotBlank)
                    ?.let { add(it.take(itemLimit)) }
            }
        }.takeLast(limit)
    }
}

/** Pure state reducer used by SQLite persistence and JVM regression tests. */
object MissionProgressReducer {
    fun recordToolOperation(
        current: AgentMissionCheckpoint,
        proposal: WorkspaceActionProposal,
        result: WorkspaceActionResult,
        succeeded: Boolean,
        updatedAt: String,
        identity: AgentOperationIdentity? = null,
    ): AgentMissionCheckpoint {
        var working = current
        var ledger = working.executionLedger ?: AgentExecutionController.initial(
            missionId = working.id,
            taskId = working.missionManifest?.currentTaskId.orEmpty(),
            logicalStepsCompleted = working.logicalStepsCompleted,
        )
        if (identity == null && (ledger.currentRunId.isBlank() || ledger.currentRunId !in ledger.admittedRunIds)) {
            ledger = AgentExecutionController.beginRun(
                ledger = ledger,
                taskId = working.missionManifest?.currentTaskId.orEmpty(),
                logicalStepsCompleted = working.logicalStepsCompleted,
            )
            val runIdentity = requireNotNull(ledger.currentRunIdentity)
            ledger = AgentExecutionController.admitRunResult(ledger, runIdentity).ledger
        }
        val operationIdentity = identity ?: AgentExecutionController.beginTransaction(
            ledger = ledger,
            operationCount = 1,
            explicitBundle = false,
            updatedAt = updatedAt,
        ).also { lease -> ledger = lease.ledger }.operations.single()
        val admission = AgentExecutionController.admitOperationResult(
            ledger = ledger,
            identity = operationIdentity,
            succeeded = succeeded,
            evidence = result.detail,
            updatedAt = updatedAt,
        )
        working = working.copy(executionLedger = admission.ledger)
        if (!admission.accepted) {
            return working.copy(
                lastResult = when {
                    admission.duplicate -> "Duplicate ${operationIdentity.toolCallId} ignored."
                    else -> "Stale ${operationIdentity.toolCallId} retained as evidence and ignored."
                },
                updatedAt = updatedAt,
            )
        }
        require(working.status == AgentMissionStatus.Running) { "The long-form mission is not running." }
        require(working.toolOperations < working.maxToolOperations) {
            "The mission exhausted its ${working.maxToolOperations}-operation controller budget."
        }
        val writeBytes = when {
            !succeeded -> 0L
            proposal.kind in setOf(WorkspaceActionKind.CreateFile, WorkspaceActionKind.WriteFile) ->
                proposal.content.toByteArray(Charsets.UTF_8).size.toLong()
            else -> 0L
        }
        require(working.writtenBytes + writeBytes <= working.maxWriteBytes) {
            "The mission exhausted its ${working.maxWriteBytes}-byte write grant."
        }
        val updatedManifest = working.missionManifest?.let { manifest ->
            val artifactId = result.artifactId
                ?.takeIf { succeeded }
                ?.takeIf {
                    proposal.kind !in setOf(
                        WorkspaceActionKind.ListFiles,
                        WorkspaceActionKind.CreateDirectory,
                    )
                }
                ?.takeIf(::isValidWorkspaceArtifactId)
            if (artifactId == null || manifest.currentTaskId.isBlank()) {
                manifest
            } else {
                manifest.copy(tasks = manifest.tasks.map { task ->
                    if (task.id != manifest.currentTaskId) task else task.copy(
                        artifactIds = (task.artifactIds + artifactId).distinct(),
                    )
                })
            }
        }
        return working.copy(
            toolOperations = working.toolOperations + 1,
            toolFailures = working.toolFailures + if (succeeded) 0 else 1,
            lastToolSucceeded = succeeded,
            writtenBytes = working.writtenBytes + writeBytes,
            missionManifest = updatedManifest,
            actionTrail = (
                working.actionTrail +
                    "${proposal.kind.wireName}:${proposal.path}:${proposal.content.hashCode()}"
                ).takeLast(240),
            lastAction = "${proposal.kind.wireName} ${proposal.path}".take(700),
            lastResult = result.detail.take(2_000),
            updatedAt = updatedAt,
        )
    }

    fun recordInferenceCycle(
        current: AgentMissionCheckpoint,
        updatedAt: String,
    ): AgentMissionCheckpoint {
        require(current.status == AgentMissionStatus.Running) { "The long-form mission is not running." }
        val ledger = AgentExecutionController.beginRun(
            ledger = current.executionLedger ?: AgentExecutionController.initial(
                missionId = current.id,
                taskId = current.missionManifest?.currentTaskId.orEmpty(),
                logicalStepsCompleted = current.logicalStepsCompleted,
            ),
            taskId = current.missionManifest?.currentTaskId.orEmpty(),
            logicalStepsCompleted = current.logicalStepsCompleted,
        )
        return current.copy(
            inferenceCycles = current.inferenceCycles + 1,
            executionLedger = ledger,
            updatedAt = updatedAt,
        )
    }

    fun recordRecovery(
        current: AgentMissionCheckpoint,
        decision: MissionRecoveryDecision,
        updatedAt: String,
    ): AgentMissionCheckpoint {
        require(current.status == AgentMissionStatus.Running) { "The long-form mission is not running." }
        val manifest = current.missionManifest?.let { activeManifest ->
            activeManifest.copy(tasks = activeManifest.tasks.map { task ->
                if (task.id != activeManifest.currentTaskId) task else task.copy(
                    attempts = task.attempts + 1,
                    failure = decision.detail,
                )
            })
        }
        return current.copy(
            status = if (decision.automatic) current.status else AgentMissionStatus.Paused,
            recoveries = current.recoveries + if (decision.automatic) 1 else 0,
            recoveryTrail = (current.recoveryTrail + decision.signature).takeLast(24),
            lastControllerErrorCode = decision.errorCode,
            lastRecoveryDisposition = decision.disposition,
            missionManifest = manifest,
            lastResult = "Recovery ${decision.disposition.name}: ${decision.detail}".take(2_000),
            updatedAt = updatedAt,
        )
    }

    fun recordCheckpoint(
        current: AgentMissionCheckpoint,
        proposal: MissionCheckpointProposal,
        updatedAt: String,
    ): AgentMissionCheckpoint {
        require(current.status == AgentMissionStatus.Running) { "The long-form mission is not running." }
        require(current.planKind == WorkspaceMissionKind) {
            "General mission checkpoints cannot alter a specialized controller lane."
        }
        require(current.completedActions < current.maxActions) {
            "Mission ${current.id} exhausted its ${current.maxActions} logical-step grant."
        }
        require(current.toolOperations > current.toolOperationsAtLastCheckpoint && current.lastToolSucceeded) {
            "A logical checkpoint requires new successful controller evidence."
        }
        val manifest = requireNotNull(current.missionManifest) { "The mission manifest is unavailable." }
        require(proposal.taskId == manifest.currentTaskId) {
            "Checkpoint ${proposal.taskId} does not match current task ${manifest.currentTaskId}."
        }
        val task = manifest.task(proposal.taskId)
        require(task.status == MissionTaskStatus.InProgress) {
            "Checkpoint ${proposal.taskId} is not the active in-progress manifest task."
        }
        require(proposal.summary.isNotBlank()) { "A logical checkpoint requires a verified summary." }
        require(proposal.artifactIds.all { it in task.artifactIds }) {
            "A checkpoint referenced an artifact that was not verified for ${task.id}."
        }
        val completedTask = proposal.kind == MissionCheckpointKind.TaskComplete
        if (completedTask) {
            require(task.artifactIds.isNotEmpty()) {
                "A workspace manifest task cannot complete without at least one verified artifact."
            }
        }
        val nextTasks = manifest.tasks.map { candidate ->
            when {
                candidate.id == task.id -> candidate.copy(
                    status = if (completedTask) MissionTaskStatus.Completed else MissionTaskStatus.InProgress,
                    lastVerifiedCheckpoint = proposal.summary,
                    failure = "",
                )
                else -> candidate
            }
        }.toMutableList()
        val nextTask = if (completedTask) {
            nextTasks.firstOrNull { it.status == MissionTaskStatus.Pending }
        } else {
            task
        }
        if (nextTask != null && nextTask.status == MissionTaskStatus.Pending) {
            val index = nextTasks.indexOfFirst { it.id == nextTask.id }
            nextTasks[index] = nextTask.copy(status = MissionTaskStatus.InProgress)
        }
        val nextManifest = manifest.copy(
            tasks = nextTasks,
            currentTaskId = nextTask?.id.orEmpty(),
        )
        val nextCompletedActions = current.completedActions + 1
        val nextExecutionLedger = AgentExecutionController.afterCheckpoint(
            ledger = current.executionLedger ?: AgentExecutionController.initial(
                missionId = current.id,
                taskId = manifest.currentTaskId,
                logicalStepsCompleted = current.logicalStepsCompleted,
            ),
            nextTaskId = nextManifest.currentTaskId,
            logicalStepsCompleted = nextCompletedActions,
        )
        return current.copy(
            status = if (nextManifest.complete) AgentMissionStatus.Completed else AgentMissionStatus.Running,
            completedActions = nextCompletedActions,
            toolOperationsAtLastCheckpoint = current.toolOperations,
            missionManifest = nextManifest,
            executionLedger = nextExecutionLedger,
            recoveryTrail = emptyList(),
            lastVerifiedCheckpoint = proposal.summary,
            lastAction = "${proposal.kind.wireName} ${proposal.taskId}".take(700),
            lastResult = proposal.summary,
            updatedAt = updatedAt,
        )
    }
}

enum class MissionRecoveryDisposition {
    DeterministicRepair,
    RetryTransient,
    ModelRepair,
    HumanRequired,
    SecurityStop,
    Exhausted,
}

data class MissionRecoveryDecision(
    val disposition: MissionRecoveryDisposition,
    val errorCode: ControllerErrorCode,
    val signature: String,
    val attempt: Int,
    val detail: String,
) {
    val automatic: Boolean
        get() = disposition in setOf(
            MissionRecoveryDisposition.DeterministicRepair,
            MissionRecoveryDisposition.RetryTransient,
            MissionRecoveryDisposition.ModelRepair,
        )
}

/** Pure bounded routing: controller codes choose policy; text classification remains legacy input only. */
object MissionRecoveryRouter {
    fun classify(rawDetail: String, priorSignatures: List<String>): MissionRecoveryDecision {
        return classify(
            failure = ControllerFailure.classifyLegacyDetail(rawDetail),
            priorSignatures = priorSignatures,
        )
    }

    fun classify(
        failure: ControllerFailure,
        priorSignatures: List<String>,
    ): MissionRecoveryDecision {
        val signature = failure.signature
        val sameFailureCount = priorSignatures.count(signature::equals)
        val distinctAttempts = (priorSignatures + signature).distinct().size
        val disposition = when {
            sameFailureCount >= 1 || distinctAttempts > MaximumMissionRecoveryAttempts ->
                MissionRecoveryDisposition.Exhausted
            failure.code in setOf(
                ControllerErrorCode.MISSING_TARGET,
                ControllerErrorCode.ALREADY_EXISTS,
                ControllerErrorCode.MISSING_PARENT,
                ControllerErrorCode.STALE_ARTIFACT,
                ControllerErrorCode.CONTEXT_CAPACITY,
            ) -> MissionRecoveryDisposition.DeterministicRepair
            failure.code in setOf(
                ControllerErrorCode.PROVIDER_TRANSIENT,
                ControllerErrorCode.MODEL_RUNTIME_FAILURE,
            ) -> MissionRecoveryDisposition.RetryTransient
            failure.code in setOf(
                ControllerErrorCode.PATH_INVALID,
                ControllerErrorCode.TOOL_SCHEMA_INVALID,
                ControllerErrorCode.EXECUTION_FAILED,
                ControllerErrorCode.BUILD_FAILED,
                ControllerErrorCode.TEST_FAILED,
            ) -> MissionRecoveryDisposition.ModelRepair
            failure.code in setOf(
                ControllerErrorCode.AUTHORITY_REQUIRED,
                ControllerErrorCode.SEMANTIC_AMBIGUITY,
            ) -> MissionRecoveryDisposition.HumanRequired
            failure.code in setOf(
                ControllerErrorCode.OUTSIDE_AUTHORITY,
                ControllerErrorCode.SECURITY_VIOLATION,
            ) -> MissionRecoveryDisposition.SecurityStop
        }
        return MissionRecoveryDecision(
            disposition = disposition,
            errorCode = failure.code,
            signature = signature,
            attempt = priorSignatures.size + 1,
            detail = failure.detail.take(500),
        )
    }
}
