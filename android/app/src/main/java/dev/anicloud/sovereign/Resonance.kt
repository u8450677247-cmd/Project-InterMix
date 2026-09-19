package dev.anicloud.sovereign.prototype

import java.util.Locale
import kotlin.math.ceil

/**
 * Delivery preferences only. Resonance values never grant tools, filesystem access,
 * network access, credentials, or factual authority.
 */
enum class ResonanceTrait(
    val wireName: String,
    val defaultValue: Double = 0.50,
) {
    TechnicalDepth("technical_depth"),
    Verbosity("verbosity"),
    Directness("directness"),
    ArchitectureFirst("architecture_first"),
    CommandsFirst("commands_first"),
    AnalogyDensity("analogy_density"),
    ExampleDensity("example_density"),
    HumorLevel("humor_level"),
    HumorAbsurdity("humor_absurdity"),
    HumorDeadpan("humor_deadpan"),
    HumorCallbacks("humor_callbacks"),
    EmojiDensity("emoji_density"),
    Formality("formality"),
    Initiative("initiative"),
    StepByStep("step_by_step"),
    CodeDensity("code_density"),
    ToolTransparency("tool_transparency"),
    EvidenceDensity("evidence_density"),
    CitationDensity("citation_density"),
    ChallengeLevel("challenge_level"),
    ClarificationTolerance("clarification_tolerance"),
    VisualStructure("visual_structure"),
    CreativeBranching("creative_branching"),
    OfflinePriority("offline_priority"),
    AgenticDepth("agentic_depth"),
    ;

    companion object {
        fun fromWireName(raw: String): ResonanceTrait? {
            val normalized = raw.trim().lowercase(Locale.ROOT).replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

enum class ResonanceProfileId(
    val wireName: String,
    val displayName: String,
    val description: String,
) {
    JustTesting(
        "just_testing",
        "Just Testing",
        "Playful, adaptive, broad exploration with minimal setup.",
    ),
    Everyday(
        "everyday",
        "Everyday",
        "Balanced, practical, clear, and adaptable.",
    ),
    Creator(
        "creator",
        "Creator",
        "High ideation, visual thinking, expressive iteration.",
    ),
    Advanced(
        "advanced",
        "Advanced",
        "Dense, direct, technical, with reduced hand-holding.",
    ),
    Sovereign(
        "sovereign",
        "Sovereign / Off-Grid",
        "Local-first, architecture-heavy, inspectable, agentic.",
    ),
    ;

    companion object {
        fun fromWireName(raw: String): ResonanceProfileId? {
            val normalized = raw.trim().lowercase(Locale.ROOT).replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

enum class ResonanceSource(val priority: Int) {
    Base(0),
    Pack(1),
    InferredLowRisk(2),
    Feedback(3),
    Explicit(4),
}

enum class ResonanceScope(val specificity: Int) {
    Global(0),
    Domain(1),
    Project(2),
    Task(3),
    Session(4),
}

data class ResonanceTraitValue(
    val trait: ResonanceTrait,
    val value: Double,
    val confidence: Double,
    val source: ResonanceSource,
    val scope: ResonanceScope,
    val updatedAtEpochMillis: Long,
    val lastReinforcedEpochMillis: Long = updatedAtEpochMillis,
    val decayPolicy: String = "none",
) {
    init {
        require(value.isFinite() && value in 0.0..1.0) { "Resonance value must be finite and in 0..1." }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Resonance confidence must be finite and in 0..1."
        }
        require(updatedAtEpochMillis >= 0L) { "Resonance update time cannot be negative." }
        require(lastReinforcedEpochMillis >= 0L) { "Resonance reinforcement time cannot be negative." }
        require(decayPolicy in ALLOWED_DECAY_POLICIES) { "Unknown Resonance decay policy." }
    }

    companion object {
        val ALLOWED_DECAY_POLICIES = setOf("none", "session", "soft_30d", "soft_90d")

        fun createOrNull(
            trait: ResonanceTrait,
            value: Double,
            confidence: Double,
            source: ResonanceSource,
            scope: ResonanceScope,
            updatedAtEpochMillis: Long,
            lastReinforcedEpochMillis: Long = updatedAtEpochMillis,
            decayPolicy: String = "none",
        ): ResonanceTraitValue? = runCatching {
            ResonanceTraitValue(
                trait = trait,
                value = value,
                confidence = confidence,
                source = source,
                scope = scope,
                updatedAtEpochMillis = updatedAtEpochMillis,
                lastReinforcedEpochMillis = lastReinforcedEpochMillis,
                decayPolicy = decayPolicy,
            )
        }.getOrNull()
    }
}

data class ResonanceStarterProfile(
    val id: ResonanceProfileId,
    val declaredTraits: Map<ResonanceTrait, Double>,
) {
    val traits: Map<ResonanceTrait, Double> = ResonanceTrait.entries.associateWith { trait ->
        declaredTraits[trait] ?: trait.defaultValue
    }

    init {
        require(declaredTraits.keys.all { it in ResonanceTrait.entries })
        require(declaredTraits.values.all { it.isFinite() && it in 0.0..1.0 })
    }
}

/** Exact sparse values from ANICLOUDAI_RESONANCE_STARTER_PROFILES_v1_1.json. */
object ResonanceStarterProfiles {
    val JustTesting = ResonanceStarterProfile(
        ResonanceProfileId.JustTesting,
        mapOf(
            ResonanceTrait.TechnicalDepth to 0.45,
            ResonanceTrait.Verbosity to 0.45,
            ResonanceTrait.Directness to 0.55,
            ResonanceTrait.ArchitectureFirst to 0.35,
            ResonanceTrait.CommandsFirst to 0.35,
            ResonanceTrait.AnalogyDensity to 0.55,
            ResonanceTrait.ExampleDensity to 0.55,
            ResonanceTrait.HumorLevel to 0.75,
            ResonanceTrait.HumorAbsurdity to 0.70,
            ResonanceTrait.HumorDeadpan to 0.45,
            ResonanceTrait.HumorCallbacks to 0.60,
            ResonanceTrait.EmojiDensity to 0.55,
            ResonanceTrait.Initiative to 0.65,
            ResonanceTrait.StepByStep to 0.50,
            ResonanceTrait.ToolTransparency to 0.55,
            ResonanceTrait.CreativeBranching to 0.75,
        ),
    )

    val Everyday = ResonanceStarterProfile(
        ResonanceProfileId.Everyday,
        mapOf(
            ResonanceTrait.TechnicalDepth to 0.50,
            ResonanceTrait.Verbosity to 0.50,
            ResonanceTrait.Directness to 0.60,
            ResonanceTrait.ArchitectureFirst to 0.45,
            ResonanceTrait.CommandsFirst to 0.45,
            ResonanceTrait.AnalogyDensity to 0.45,
            ResonanceTrait.ExampleDensity to 0.55,
            ResonanceTrait.HumorLevel to 0.45,
            ResonanceTrait.EmojiDensity to 0.25,
            ResonanceTrait.Initiative to 0.55,
            ResonanceTrait.StepByStep to 0.60,
            ResonanceTrait.ToolTransparency to 0.55,
        ),
    )

    val Creator = ResonanceStarterProfile(
        ResonanceProfileId.Creator,
        mapOf(
            ResonanceTrait.TechnicalDepth to 0.55,
            ResonanceTrait.Verbosity to 0.60,
            ResonanceTrait.Directness to 0.55,
            ResonanceTrait.ArchitectureFirst to 0.50,
            ResonanceTrait.AnalogyDensity to 0.75,
            ResonanceTrait.ExampleDensity to 0.65,
            ResonanceTrait.HumorLevel to 0.60,
            ResonanceTrait.EmojiDensity to 0.45,
            ResonanceTrait.Initiative to 0.80,
            ResonanceTrait.CreativeBranching to 0.95,
            ResonanceTrait.VisualStructure to 0.85,
        ),
    )

    val Advanced = ResonanceStarterProfile(
        ResonanceProfileId.Advanced,
        mapOf(
            ResonanceTrait.TechnicalDepth to 0.90,
            ResonanceTrait.Verbosity to 0.65,
            ResonanceTrait.Directness to 0.90,
            ResonanceTrait.ArchitectureFirst to 0.75,
            ResonanceTrait.CommandsFirst to 0.55,
            ResonanceTrait.AnalogyDensity to 0.35,
            ResonanceTrait.ExampleDensity to 0.55,
            ResonanceTrait.HumorLevel to 0.25,
            ResonanceTrait.EmojiDensity to 0.10,
            ResonanceTrait.Initiative to 0.75,
            ResonanceTrait.StepByStep to 0.45,
            ResonanceTrait.CodeDensity to 0.80,
            ResonanceTrait.ToolTransparency to 0.80,
            ResonanceTrait.EvidenceDensity to 0.75,
            ResonanceTrait.CitationDensity to 0.65,
        ),
    )

    val Sovereign = ResonanceStarterProfile(
        ResonanceProfileId.Sovereign,
        mapOf(
            ResonanceTrait.TechnicalDepth to 0.98,
            ResonanceTrait.Verbosity to 0.78,
            ResonanceTrait.Directness to 0.92,
            ResonanceTrait.ArchitectureFirst to 0.95,
            ResonanceTrait.CommandsFirst to 0.50,
            ResonanceTrait.AnalogyDensity to 0.50,
            ResonanceTrait.ExampleDensity to 0.65,
            ResonanceTrait.HumorLevel to 0.42,
            ResonanceTrait.HumorCallbacks to 0.55,
            ResonanceTrait.EmojiDensity to 0.22,
            ResonanceTrait.Initiative to 0.92,
            ResonanceTrait.StepByStep to 0.62,
            ResonanceTrait.CodeDensity to 0.88,
            ResonanceTrait.ToolTransparency to 0.98,
            ResonanceTrait.EvidenceDensity to 0.85,
            ResonanceTrait.CitationDensity to 0.75,
            ResonanceTrait.OfflinePriority to 1.0,
            ResonanceTrait.AgenticDepth to 1.0,
        ),
    )

    val all = listOf(JustTesting, Everyday, Creator, Advanced, Sovereign)

    fun byId(id: ResonanceProfileId): ResonanceStarterProfile =
        all.first { it.id == id }
}

enum class ResonanceSessionMode {
    CommandsOnly,
    Concise,
    DeepArchitecture,
    NoJokes,
}

data class ResolvedResonanceProfile(
    val starterProfile: ResonanceProfileId,
    val traits: Map<ResonanceTrait, ResonanceTraitValue>,
    val sessionModes: Set<ResonanceSessionMode>,
) {
    fun valueOf(trait: ResonanceTrait): Double =
        traits.getValue(trait).value
}

object ResonanceResolver {
    private val candidateOrder = compareBy<ResonanceTraitValue>(
        { if (it.source == ResonanceSource.Explicit) 1 else 0 },
        { it.scope.specificity },
        { it.source.priority },
        { it.confidence },
        { it.updatedAtEpochMillis },
        { it.lastReinforcedEpochMillis },
        { it.value },
    )

    fun resolve(
        starterProfile: ResonanceStarterProfile,
        candidates: Collection<ResonanceTraitValue> = emptyList(),
        sessionModes: Set<ResonanceSessionMode> = emptySet(),
    ): ResolvedResonanceProfile {
        val base = starterProfile.traits.map { (trait, value) ->
            ResonanceTraitValue(
                trait = trait,
                value = value,
                confidence = 1.0,
                source = ResonanceSource.Base,
                scope = ResonanceScope.Global,
                updatedAtEpochMillis = 0L,
            )
        }
        val session = sessionModes.flatMap(::sessionModeCandidates)
        val resolved = (base + candidates + session)
            .groupBy(ResonanceTraitValue::trait)
            .mapValues { (_, values) -> values.maxWith(candidateOrder) }

        check(resolved.keys.containsAll(ResonanceTrait.entries))
        return ResolvedResonanceProfile(starterProfile.id, resolved, sessionModes.toSet())
    }

    private fun sessionModeCandidates(mode: ResonanceSessionMode): List<ResonanceTraitValue> {
        val values = when (mode) {
            ResonanceSessionMode.CommandsOnly -> mapOf(
                ResonanceTrait.CommandsFirst to 1.0,
                ResonanceTrait.Verbosity to 0.20,
                ResonanceTrait.Directness to 0.95,
            )
            ResonanceSessionMode.Concise -> mapOf(
                ResonanceTrait.Verbosity to 0.20,
                ResonanceTrait.Directness to 0.90,
            )
            ResonanceSessionMode.DeepArchitecture -> mapOf(
                ResonanceTrait.TechnicalDepth to 0.95,
                ResonanceTrait.ArchitectureFirst to 1.0,
                ResonanceTrait.EvidenceDensity to 0.85,
            )
            ResonanceSessionMode.NoJokes -> mapOf(
                ResonanceTrait.HumorLevel to 0.0,
                ResonanceTrait.HumorAbsurdity to 0.0,
                ResonanceTrait.HumorCallbacks to 0.0,
            )
        }
        return values.map { (trait, value) ->
            ResonanceTraitValue(
                trait = trait,
                value = value,
                confidence = 1.0,
                source = ResonanceSource.Explicit,
                scope = ResonanceScope.Session,
                updatedAtEpochMillis = Long.MAX_VALUE,
                lastReinforcedEpochMillis = Long.MAX_VALUE,
                decayPolicy = "session",
            )
        }
    }
}

data class ResonanceDeliveryContract(
    val text: String,
    val characterCount: Int,
    val estimatedTokens: Int,
)

object ResonanceDeliveryCompiler {
    const val MAX_CHARACTERS = 720
    const val MAX_ESTIMATED_TOKENS = 180

    fun compile(profile: ResolvedResonanceProfile): ResonanceDeliveryContract {
        fun value(trait: ResonanceTrait) = profile.valueOf(trait)
        val order = when {
            value(ResonanceTrait.CommandsFirst) >= 0.72 -> "commands before explanation"
            value(ResonanceTrait.ArchitectureFirst) >= 0.72 -> "architecture before implementation"
            else -> "answer first, then supporting detail"
        }
        val lines = listOf(
            "DELIVERY CONTRACT",
            "Technical depth: ${band(value(ResonanceTrait.TechnicalDepth), "accessible", "balanced", "high")}.",
            "Verbosity: ${band(value(ResonanceTrait.Verbosity), "concise", "moderate", "thorough")}.",
            "Directness: ${band(value(ResonanceTrait.Directness), "gentle", "clear", "high")}.",
            "Order: $order.",
            "Examples: ${band(value(ResonanceTrait.ExampleDensity), "only when needed", "after concepts", "frequent and concrete")}.",
            "Humor: ${band(value(ResonanceTrait.HumorLevel), "minimal", "light", "playful")}.",
            "Structure: ${band(value(ResonanceTrait.VisualStructure), "plain", "scannable", "strong visual hierarchy")}.",
            "Evidence: ${band(value(ResonanceTrait.EvidenceDensity), "light", "when useful", "explicit trade-offs and evidence")}.",
            "Initiative: ${band(value(ResonanceTrait.Initiative), "wait for direction", "suggest next steps", "advance bounded work")}.",
            "Offline priority: ${band(value(ResonanceTrait.OfflinePriority), "normal", "prefer local when practical", "local-first")}.",
        )
        val text = buildString {
            for (line in lines) {
                val separator = if (isEmpty()) "" else "\n"
                if (length + separator.length + line.length > MAX_CHARACTERS) break
                append(separator)
                append(line)
            }
        }
        val estimatedTokens = ceil(text.length / 4.0).toInt()
        check(text.length <= MAX_CHARACTERS)
        check(estimatedTokens <= MAX_ESTIMATED_TOKENS)
        return ResonanceDeliveryContract(text, text.length, estimatedTokens)
    }

    private fun band(value: Double, low: String, middle: String, high: String): String = when {
        value < 0.34 -> low
        value < 0.72 -> middle
        else -> high
    }
}

enum class ResonanceFeedbackAction {
    MoreTechnical,
    MoreConcise,
    MoreExamples,
    MoreDirect,
    MorePlayful,
    LessPlayful,
    ArchitectureFirst,
    CommandsFirst,
}

data class ResonanceFeedbackChange(
    val action: ResonanceFeedbackAction,
    val trait: ResonanceTrait,
    val before: Double,
    val after: Double,
) {
    fun undo(current: Double): Double = if (current == after) before else current
}

object ResonanceFeedbackPolicy {
    private data class Adjustment(val trait: ResonanceTrait, val delta: Double)

    private val adjustments = mapOf(
        ResonanceFeedbackAction.MoreTechnical to Adjustment(ResonanceTrait.TechnicalDepth, 0.08),
        ResonanceFeedbackAction.MoreConcise to Adjustment(ResonanceTrait.Verbosity, -0.10),
        ResonanceFeedbackAction.MoreExamples to Adjustment(ResonanceTrait.ExampleDensity, 0.10),
        ResonanceFeedbackAction.MoreDirect to Adjustment(ResonanceTrait.Directness, 0.08),
        ResonanceFeedbackAction.MorePlayful to Adjustment(ResonanceTrait.HumorLevel, 0.10),
        ResonanceFeedbackAction.LessPlayful to Adjustment(ResonanceTrait.HumorLevel, -0.10),
        ResonanceFeedbackAction.ArchitectureFirst to Adjustment(ResonanceTrait.ArchitectureFirst, 0.10),
        ResonanceFeedbackAction.CommandsFirst to Adjustment(ResonanceTrait.CommandsFirst, 0.10),
    )

    fun apply(action: ResonanceFeedbackAction, current: Double): ResonanceFeedbackChange {
        require(current.isFinite() && current in 0.0..1.0)
        val adjustment = adjustments.getValue(action)
        return ResonanceFeedbackChange(
            action = action,
            trait = adjustment.trait,
            before = current,
            after = (current + adjustment.delta).coerceIn(0.0, 1.0),
        )
    }
}

data class ResonanceCueResult(
    val feedback: Set<ResonanceFeedbackAction>,
    val sessionModes: Set<ResonanceSessionMode>,
)

object ResonanceCueDetector {
    private data class FeedbackCue(val action: ResonanceFeedbackAction, val pattern: Regex)
    private data class SessionCue(val mode: ResonanceSessionMode, val pattern: Regex)

    private val feedbackCues = listOf(
        FeedbackCue(ResonanceFeedbackAction.MoreTechnical, Regex("\\bmore technical\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.MoreConcise, Regex("\\b(?:more concise|shorter)\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.MoreExamples, Regex("\\bmore examples?\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.MoreDirect, Regex("\\bmore direct\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.MorePlayful, Regex("\\bmore playful\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.LessPlayful, Regex("\\b(?:less playful|less humor)\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.ArchitectureFirst, Regex("\\barchitecture first\\b", RegexOption.IGNORE_CASE)),
        FeedbackCue(ResonanceFeedbackAction.CommandsFirst, Regex("\\bcommands first\\b", RegexOption.IGNORE_CASE)),
    )
    private val sessionCues = listOf(
        SessionCue(ResonanceSessionMode.CommandsOnly, Regex("\\bcommands only\\b", RegexOption.IGNORE_CASE)),
        SessionCue(ResonanceSessionMode.Concise, Regex("\\b(?:keep (?:this|it) concise|concise for this (?:task|session))\\b", RegexOption.IGNORE_CASE)),
        SessionCue(ResonanceSessionMode.DeepArchitecture, Regex("\\bdeep architecture\\b", RegexOption.IGNORE_CASE)),
        SessionCue(ResonanceSessionMode.NoJokes, Regex("\\b(?:no jokes|no humor)\\b", RegexOption.IGNORE_CASE)),
    )

    fun detect(text: String): ResonanceCueResult = ResonanceCueResult(
        feedback = feedbackCues.filter { it.pattern.containsMatchIn(text) }.map { it.action }.toSet(),
        sessionModes = sessionCues.filter { it.pattern.containsMatchIn(text) }.map { it.mode }.toSet(),
    )
}

data class ResonanceExperiencePack(
    val id: String,
    val name: String,
    val version: String,
    val traits: Map<String, Double>,
    val promptRules: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val signaturePresent: Boolean,
)

data class ResonancePackValidation(
    val valid: Boolean,
    val errors: List<String>,
)

object ResonancePackValidator {
    private val safeId = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val safeVersion = Regex("^[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][a-zA-Z0-9.-]+)?$")
    private val forbiddenField = Regex(
        "(?:api[_-]?key|credential|secret|token|password|tool|permission|authority|filesystem|network|executable|script|command|shell)",
        RegexOption.IGNORE_CASE,
    )
    private val hostileText = Regex(
        "(?:ignore (?:all |any )?(?:previous|prior) instructions?|<\\s*/?system\\b|developer message|grant (?:tool|network|filesystem)|execute (?:this|the following)|BEGIN_[A-Z_]+)",
        RegexOption.IGNORE_CASE,
    )
    private val allowedPromptRules = setOf("ordering", "tone", "example_policy", "format", "humor")
    private val allowedMetadata = setOf(
        "author",
        "description",
        "license",
        "homepage",
        "signature",
        "signing_key_id",
    )

    fun validate(pack: ResonanceExperiencePack): ResonancePackValidation {
        val errors = mutableListOf<String>()
        if (!safeId.matches(pack.id)) errors += "Pack id is not canonical."
        if (!safeVersion.matches(pack.version)) errors += "Pack version is not canonical."
        if (!safeText(pack.name, 80)) errors += "Pack name is unsafe."
        if (!pack.signaturePresent) errors += "Pack signature is missing."

        pack.traits.forEach { (key, value) ->
            if (forbiddenField.containsMatchIn(key)) errors += "Forbidden trait field: $key"
            if (ResonanceTrait.fromWireName(key) == null) errors += "Unknown trait: $key"
            if (!value.isFinite() || value !in 0.0..1.0) errors += "Invalid trait value: $key"
        }
        pack.promptRules.forEach { (key, value) ->
            if (forbiddenField.containsMatchIn(key) || key !in allowedPromptRules) {
                errors += "Forbidden prompt-rule field: $key"
            }
            if (!safeText(value, 160)) errors += "Unsafe prompt-rule value: $key"
        }
        pack.metadata.forEach { (key, value) ->
            if (forbiddenField.containsMatchIn(key) || key !in allowedMetadata) {
                errors += "Forbidden metadata field: $key"
            }
            if (!safeText(value, 240)) errors += "Unsafe metadata value: $key"
        }
        return ResonancePackValidation(errors.isEmpty(), errors.distinct())
    }

    private fun safeText(value: String, maxLength: Int): Boolean =
        value.isNotBlank() &&
            value.length <= maxLength &&
            value.none { it.isISOControl() && it != '\n' && it != '\t' } &&
            !hostileText.containsMatchIn(value)
}
