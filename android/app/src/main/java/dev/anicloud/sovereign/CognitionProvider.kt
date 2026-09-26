package dev.anicloud.sovereign.prototype

enum class CognitionLocality {
    Device,
    Home,
    Cloud,
}

enum class CognitionPrivacyClass {
    DevicePrivate,
    TrustedHome,
    External,
}

enum class CognitionStrength {
    Minimal,
    General,
    Strong,
    Frontier,
}

enum class CognitionThermalCost {
    Low,
    Moderate,
    High,
}

data class CognitionCapabilities(
    val toolCalling: Boolean,
    val vision: Boolean,
    val contextCapacity: Int,
    val structuredGeneration: Boolean,
    val codingStrength: CognitionStrength,
    val reasoningStrength: CognitionStrength,
    val locality: CognitionLocality,
    val privacyClass: CognitionPrivacyClass,
    val marginalCostMicros: Long,
    val expectedLatencyMillis: Long,
    val networkRequired: Boolean,
    val availableBackends: Set<String>,
    val thermalCost: CognitionThermalCost,
) {
    init {
        require(contextCapacity > 0)
        require(marginalCostMicros >= 0L)
        require(expectedLatencyMillis >= 0L)
        require(availableBackends.isNotEmpty())
    }
}

/** A replaceable intelligence endpoint. Mission state and authority never live in this object. */
interface CognitionProvider {
    val id: String
    val capabilities: CognitionCapabilities
    val available: Boolean
}

data class DeclaredCognitionProvider(
    override val id: String,
    override val capabilities: CognitionCapabilities,
    override val available: Boolean = true,
) : CognitionProvider {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{2,63}").matches(id))
    }
}

data class CognitionRequest(
    val toolCalling: Boolean = false,
    val vision: Boolean = false,
    val minimumContextCapacity: Int = 1,
    val structuredGeneration: Boolean = false,
    val minimumCodingStrength: CognitionStrength = CognitionStrength.Minimal,
    val minimumReasoningStrength: CognitionStrength = CognitionStrength.Minimal,
    val allowedLocalities: Set<CognitionLocality> = CognitionLocality.entries.toSet(),
    val maximumPrivacyClass: CognitionPrivacyClass = CognitionPrivacyClass.External,
    val maximumThermalCost: CognitionThermalCost = CognitionThermalCost.High,
    val offline: Boolean = false,
    val preferredProviderIds: List<String> = emptyList(),
)

data class CognitionCandidate<T>(
    val provider: CognitionProvider,
    val payload: T,
)

/** Pure controller policy: generated text cannot change capability declarations or authority. */
object CognitionRoutingPolicy {
    fun <T> select(
        candidates: List<CognitionCandidate<T>>,
        request: CognitionRequest,
    ): CognitionCandidate<T>? = candidates
        .asSequence()
        .filter { it.provider.available }
        .filter { compatible(it.provider.capabilities, request) }
        .maxByOrNull { score(it.provider, request) }

    fun compatible(capabilities: CognitionCapabilities, request: CognitionRequest): Boolean =
        (!request.toolCalling || capabilities.toolCalling) &&
            (!request.vision || capabilities.vision) &&
            capabilities.contextCapacity >= request.minimumContextCapacity &&
            (!request.structuredGeneration || capabilities.structuredGeneration) &&
            capabilities.codingStrength >= request.minimumCodingStrength &&
            capabilities.reasoningStrength >= request.minimumReasoningStrength &&
            capabilities.locality in request.allowedLocalities &&
            capabilities.privacyClass <= request.maximumPrivacyClass &&
            capabilities.thermalCost <= request.maximumThermalCost &&
            (!request.offline || !capabilities.networkRequired)

    private fun score(provider: CognitionProvider, request: CognitionRequest): Long {
        val preference = request.preferredProviderIds.indexOf(provider.id).let { index ->
            if (index < 0) 0L else 1_000_000L - (index * 10_000L)
        }
        val local = when (provider.capabilities.locality) {
            CognitionLocality.Device -> 30_000L
            CognitionLocality.Home -> 20_000L
            CognitionLocality.Cloud -> 10_000L
        }
        val privacy = (CognitionPrivacyClass.entries.size - provider.capabilities.privacyClass.ordinal) * 2_000L
        val context = provider.capabilities.contextCapacity.coerceAtMost(1_000_000).toLong()
        val strength = (provider.capabilities.reasoningStrength.ordinal * 1_000L) +
            (provider.capabilities.codingStrength.ordinal * 1_000L)
        val costPenalty = provider.capabilities.marginalCostMicros.coerceAtMost(1_000_000L)
        val latencyPenalty = provider.capabilities.expectedLatencyMillis.coerceAtMost(120_000L)
        return preference + local + privacy + context + strength - costPenalty - latencyPenalty
    }
}

fun localLiteRtProvider(model: ImportedModel, npu: Boolean): CognitionProvider =
    DeclaredCognitionProvider(
        id = "litert.${model.role.name.lowercase()}",
        capabilities = CognitionCapabilities(
            toolCalling = true,
            vision = false,
            contextCapacity = ContextPhysicalTokens,
            structuredGeneration = true,
            codingStrength = if (model.role == ModelRole.Reasoning) {
                CognitionStrength.Strong
            } else {
                CognitionStrength.General
            },
            reasoningStrength = if (model.role == ModelRole.Reasoning) {
                CognitionStrength.Strong
            } else {
                CognitionStrength.General
            },
            locality = CognitionLocality.Device,
            privacyClass = CognitionPrivacyClass.DevicePrivate,
            marginalCostMicros = 0L,
            expectedLatencyMillis = if (npu) 800L else 1_800L,
            networkRequired = false,
            availableBackends = if (npu) setOf("NPU") else setOf("GPU", "CPU"),
            thermalCost = if (npu) CognitionThermalCost.Low else CognitionThermalCost.High,
        ),
    )
