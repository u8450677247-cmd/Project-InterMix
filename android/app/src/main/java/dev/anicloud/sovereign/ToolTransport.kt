package dev.anicloud.sovereign.prototype

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

enum class ToolTransportKind {
    NativeLiteRt,
    TaggedText,
}

data class RuntimeToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
) {
    init {
        require(name.isNotBlank()) { "A runtime tool call requires a name." }
    }
}

data class ToolTransportEnvelope(
    val generatedText: String,
    val nativeToolCalls: List<RuntimeToolCall> = emptyList(),
    val nativeToolsAvailable: Boolean = false,
)

interface ToolTransport {
    val kind: ToolTransportKind
    fun decode(envelope: ToolTransportEnvelope): ControllerProtocolResult
}

object TaggedTextToolTransport : ToolTransport {
    override val kind: ToolTransportKind = ToolTransportKind.TaggedText

    override fun decode(envelope: ToolTransportEnvelope): ControllerProtocolResult =
        ControllerProtocol.parse(envelope.generatedText)
}

object NativeLiteRtToolTransport : ToolTransport {
    override val kind: ToolTransportKind = ToolTransportKind.NativeLiteRt

    override fun decode(envelope: ToolTransportEnvelope): ControllerProtocolResult {
        val tagged = ControllerProtocol.parse(envelope.generatedText)
        if (envelope.nativeToolCalls.size != 1) {
            return ControllerProtocolResult(
                visibleText = tagged.visibleText,
                controllerFailure = ControllerFailure(
                    code = ControllerErrorCode.TOOL_SCHEMA_INVALID,
                    detail = "A native turn must contain exactly one semantic tool call; received " +
                        "${envelope.nativeToolCalls.size}.",
                    rejectedProposal = envelope.nativeToolCalls.joinToString { it.name }.take(1_000),
                ),
                transport = kind,
            )
        }
        val taggedPayloadCount = listOf(
            tagged.workspaceAction,
            tagged.workspaceTransaction,
            tagged.missionCheckpoint,
            tagged.executionAction,
            tagged.calculationAction,
            tagged.storyChapter,
            tagged.evolutionProposal,
            tagged.memoryPayload,
            tagged.profilePayload,
        ).count { it != null }
        if (taggedPayloadCount > 0 || tagged.malformedProtocolSuffix) {
            return ControllerProtocolResult(
                visibleText = tagged.visibleText,
                controllerFailure = ControllerFailure(
                    code = ControllerErrorCode.TOOL_SCHEMA_INVALID,
                    detail = "A turn cannot mix native and tagged tool transports.",
                    rejectedProposal = envelope.nativeToolCalls.single().name,
                ),
                transport = kind,
            )
        }
        return ControllerProtocol.parseSemanticToolCall(
            call = envelope.nativeToolCalls.single(),
            visibleText = tagged.visibleText,
        )
    }
}

object ToolTransportRouter {
    fun decode(envelope: ToolTransportEnvelope): ControllerProtocolResult {
        if (envelope.nativeToolCalls.isNotEmpty()) {
            return NativeLiteRtToolTransport.decode(envelope)
        }
        val tagged = TaggedTextToolTransport.decode(envelope)
        if (!envelope.nativeToolsAvailable) return tagged
        val nativeEquivalentPayload = listOf(
            tagged.workspaceAction,
            tagged.workspaceTransaction,
            tagged.missionCheckpoint,
            tagged.executionAction,
            tagged.calculationAction,
        ).any { it != null }
        if (nativeEquivalentPayload || tagged.malformedProtocolSuffix) {
            return ControllerProtocolResult(
                visibleText = tagged.visibleText,
                controllerFailure = ControllerFailure(
                    code = ControllerErrorCode.TOOL_SCHEMA_INVALID,
                    detail = "Tagged controller envelopes are disabled while native tools are available.",
                    rejectedProposal = envelope.generatedText.take(1_000),
                ),
                transport = ToolTransportKind.NativeLiteRt,
            )
        }
        val compatibilityPayload = tagged.storyChapter != null || tagged.evolutionProposal != null ||
            tagged.memoryPayload != null || tagged.profilePayload != null
        return if (compatibilityPayload) tagged else tagged.copy(transport = ToolTransportKind.NativeLiteRt)
    }
}

/**
 * LiteRT reflects these signatures into model-visible schemas. Bodies are intentionally inert:
 * automatic tool calling remains disabled and Android executes only proposals admitted by its
 * authority controller.
 */
class AniCloudToolSet : ToolSet {
    @Tool(description = "List an authorized workspace directory without mutating it.")
    fun workspace_list(
        @ToolParam(description = "Workspace-relative directory path, or empty for the granted root.")
        path: String = "",
        @ToolParam(description = "Exact controller artifact id for an existing directory, if known.")
        artifact_id: String = "",
    ): Map<String, Any> = manualProposal("workspace_list", path, artifact_id)

    @Tool(description = "Read one authorized workspace file without mutating it.")
    fun workspace_read(
        @ToolParam(description = "Workspace-relative path, empty only when artifact_id is supplied.")
        path: String = "",
        @ToolParam(description = "Exact controller artifact id for an existing file, if known.")
        artifact_id: String = "",
    ): Map<String, Any> = manualProposal("workspace_read", path, artifact_id)

    @Tool(description = "Read an existing logical artifact by its exact controller identity.")
    fun artifact_read(
        @ToolParam(description = "Exact artifact id in WA- plus 32 uppercase hexadecimal form.")
        artifact_id: String,
    ): Map<String, Any> = manualProposal("artifact_read", artifact_id)

    @Tool(description = "Propose a complete new manifest artifact; Android chooses its storage path.")
    fun artifact_create(
        @ToolParam(description = "Current manifest task id, for example TASK-001.") task_id: String,
        @ToolParam(description = "Stable lowercase semantic type, for example cover_letter.")
        artifact_type: String,
        @ToolParam(description = "Human-facing label; Unicode is allowed.") display_name: String = "",
        @ToolParam(description = "Complete non-empty final file content.") content: String,
    ): Map<String, Any> = manualProposal(
        "artifact_create",
        task_id,
        artifact_type,
        display_name,
        content,
    )

    @Tool(description = "Propose a complete replacement for an existing logical artifact.")
    fun artifact_update(
        @ToolParam(description = "Exact controller-owned artifact id.") artifact_id: String,
        @ToolParam(description = "Complete non-empty replacement content.") content: String,
        @ToolParam(description = "Short reason for the update.") reason: String = "",
    ): Map<String, Any> = manualProposal("artifact_update", artifact_id, content, reason)

    @Tool(description = "Propose two to eight ordered workspace operations as one semantic transaction.")
    fun workspace_transaction(
        @ToolParam(description = "Two to eight complete operation JSON objects, one string per operation.")
        operations: List<String>,
        @ToolParam(description = "Short reason the operations form one coherent step.") reason: String = "",
        @ToolParam(description = "Optional checkpoint JSON object after all operations verify.")
        checkpoint_json: String? = null,
    ): Map<String, Any> = manualProposal(
        "workspace_transaction",
        operations,
        reason,
        checkpoint_json.orEmpty(),
    )

    @Tool(description = "Propose a controller checkpoint after new verified evidence.")
    fun mission_checkpoint(
        @ToolParam(description = "logical_step or task_complete.") kind: String,
        @ToolParam(description = "Current manifest task id.") task_id: String,
        @ToolParam(description = "Concise verified boundary summary.") summary: String,
        @ToolParam(description = "Exact artifact ids supporting this checkpoint.")
        artifact_ids: List<String> = emptyList(),
    ): Map<String, Any> = manualProposal("mission_checkpoint", kind, task_id, summary, artifact_ids)

    @Tool(description = "Request a separately authorized bounded developer execution.")
    fun request_execution(
        @ToolParam(description = "inspect_environment, run, test, build, or install_dependencies.")
        kind: String,
        @ToolParam(description = "One inspectable command line.") command: String,
        @ToolParam(description = "Project-relative working directory.") workdir: String = ".",
        @ToolParam(description = "Whether this exact command needs network access.")
        network_required: Boolean = false,
        @ToolParam(description = "Exact dependency names when installation is requested.")
        dependencies: List<String> = emptyList(),
        @ToolParam(description = "Short execution reason.") reason: String = "",
        @ToolParam(description = "Bounded timeout in seconds.") timeout_seconds: Int = 600,
    ): Map<String, Any> = manualProposal(
        "request_execution",
        kind,
        command,
        workdir,
        network_required,
        dependencies,
        reason,
        timeout_seconds,
    )

    @Tool(description = "Request deterministic decimal arithmetic from Android.")
    fun numeric_calculate(
        @ToolParam(description = "Bounded decimal expression.") expression: String,
        @ToolParam(description = "Short reason for the calculation.") reason: String = "",
    ): Map<String, Any> = manualProposal("numeric_calculate", expression, reason)

    private fun manualProposal(name: String, vararg values: Any): Map<String, Any> = mapOf(
        "status" to "manual_controller_execution_required",
        "tool" to name,
        "argument_count" to values.size,
    )
}
