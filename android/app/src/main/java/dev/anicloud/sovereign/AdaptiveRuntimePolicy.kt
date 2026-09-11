package dev.anicloud.sovereign.prototype

import java.util.Locale

private const val MinimumNpuAvailableBytes = 1_750L * 1024L * 1024L

enum class ModelRole(val label: String, val shortLabel: String) {
    Conversation("Conversation + Memory", "E2B"),
    Reasoning("Reasoning + Coding", "E4B"),
}

enum class RuntimeBackendPreference {
    NpuOnly,
    GpuThenCpu,
}

data class DeviceRuntimeFacts(
    val socModel: String,
    val hardware: String,
    val dispatcherAvailable: Boolean,
    val availableMemoryBytes: Long,
)

data class NpuEligibility(val eligible: Boolean, val detail: String)

data class AdaptiveModelRoute(
    val model: ImportedModel,
    val backendPreference: RuntimeBackendPreference,
    val label: String,
    val reason: String,
)

/** Controller-owned routing; generated model text can never select a backend. */
object AdaptiveRuntimePolicy {
    const val TensorG5E2BSha256 =
        "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"
    const val GoogleTensorDispatcher = "libLiteRtDispatch_GoogleTensor.so"

    private val reasoningSignals = listOf(
        "analy", "architect", "benchmark", "build", "code", "compile", "debug",
        "deep dive", "design", "implement", "install", "patch", "program", "refactor",
        "research", "run ", "test", "workspace", "write a file", "long form",
    )
    private val memorySignals = listOf(
        "remember", "memory", "recall", "what did", "what was", "summarize our",
        "preference", "decision", "earlier", "last time",
    )

    /** Immutable package/device gates that are safe to evaluate with E4B resident. */
    fun npuPackageEligibility(model: ImportedModel?, facts: DeviceRuntimeFacts): NpuEligibility {
        if (model == null) return NpuEligibility(false, "E2B package not installed")
        if (model.role != ModelRole.Conversation) {
            return NpuEligibility(false, "Only the reviewed E2B role may use Tensor NPU")
        }
        if (!model.sha256.equals(TensorG5E2BSha256, ignoreCase = true)) {
            return NpuEligibility(
                false,
                "E2B fingerprint is not the reviewed Tensor G5 package (${model.sha256.take(12)}…)",
            )
        }
        val device = "${facts.socModel} ${facts.hardware}".lowercase(Locale.ROOT)
        val tensorG5 = ("tensor" in device && "g5" in device) || "blazer" in device
        if (!tensorG5) {
            return NpuEligibility(false, "Device does not identify as the Tensor G5 reference target")
        }
        if (!facts.dispatcherAvailable) {
            return NpuEligibility(false, "Pinned Google Tensor 2.1.6 dispatcher is not packaged")
        }
        return NpuEligibility(true, "Exact Tensor G5 E2B fingerprint + dispatcher verified")
    }

    /** Final load gate; call after releasing a resident E4B engine. */
    fun npuEligibility(model: ImportedModel?, facts: DeviceRuntimeFacts): NpuEligibility {
        val packageEligibility = npuPackageEligibility(model, facts)
        if (!packageEligibility.eligible) return packageEligibility
        if (facts.availableMemoryBytes < MinimumNpuAvailableBytes) {
            return NpuEligibility(false, "Android MemAvailable is below the 1.75 GiB NPU load floor")
        }
        return packageEligibility
    }

    fun select(
        mode: AnswerMode,
        prompt: String,
        conversationModel: ImportedModel?,
        reasoningModel: ImportedModel?,
        facts: DeviceRuntimeFacts,
    ): AdaptiveModelRoute? {
        // Route selection must not reject E2B merely because the currently
        // resident E4B engine depresses MemAvailable. The final memory check is
        // performed after that engine is released by the runtime owner.
        val npu = npuPackageEligibility(conversationModel, facts)
        val normalized = prompt.lowercase(Locale.ROOT)
        val needsReasoning = reasoningSignals.any(normalized::contains) || prompt.length > 1_600
        val memoryFirst = memorySignals.any(normalized::contains)
        val preferConversation = when (mode) {
            AnswerMode.Performance -> true
            AnswerMode.Quality -> false
            AnswerMode.Adaptive -> memoryFirst || !needsReasoning
        }

        if (preferConversation && npu.eligible && conversationModel != null) {
            return AdaptiveModelRoute(
                model = conversationModel,
                backendPreference = RuntimeBackendPreference.NpuOnly,
                label = "E2B · NPU",
                reason = when {
                    memoryFirst -> "Memory/librarian turn routed to E2B"
                    mode == AnswerMode.Performance -> "Performance mode prefers conversational E2B"
                    else -> "Adaptive mode selected conversational E2B"
                },
            )
        }
        if (reasoningModel != null) {
            val fallback = if (preferConversation && !npu.eligible) " · ${npu.detail}" else ""
            return AdaptiveModelRoute(
                model = reasoningModel,
                backendPreference = RuntimeBackendPreference.GpuThenCpu,
                label = "E4B · ${mode.label}",
                reason = when {
                    mode == AnswerMode.Quality -> "Quality mode selected reasoning E4B"
                    needsReasoning -> "Technical/long-form request selected reasoning E4B"
                    else -> "E4B fallback$fallback"
                },
            )
        }
        if (npu.eligible && conversationModel != null) {
            return AdaptiveModelRoute(
                model = conversationModel,
                backendPreference = RuntimeBackendPreference.NpuOnly,
                label = "E2B · NPU",
                reason = "Only verified local model available",
            )
        }
        return null
    }
}
