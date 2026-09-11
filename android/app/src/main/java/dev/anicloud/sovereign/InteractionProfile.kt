package dev.anicloud.sovereign.prototype

import java.util.Locale

enum class InteractionTrait(
    val wireName: String,
    val label: String,
    val defaultValue: Double,
) {
    Warmth("warmth", "Warmth", 0.78),
    Directness("directness", "Directness", 0.72),
    Detail("detail", "Detail", 0.64),
    Emoji("emoji", "Emoji", 0.42),
    Initiative("initiative", "Initiative", 0.68),
    ContextPrecision("context_precision", "Context precision", 0.76),
    ;

    companion object {
        fun fromWireName(raw: String): InteractionTrait? {
            val normalized = raw.trim().lowercase(Locale.ROOT).replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

data class InteractionProfile(
    val warmth: Double = InteractionTrait.Warmth.defaultValue,
    val directness: Double = InteractionTrait.Directness.defaultValue,
    val detail: Double = InteractionTrait.Detail.defaultValue,
    val emoji: Double = InteractionTrait.Emoji.defaultValue,
    val initiative: Double = InteractionTrait.Initiative.defaultValue,
    val contextPrecision: Double = InteractionTrait.ContextPrecision.defaultValue,
    val automaticAdaptation: Boolean = true,
    val revision: Int = 0,
    val updatedAt: String = "",
    val lastReason: String = "Factory interaction profile",
    val lastEvidence: String = "",
) {
    fun valueOf(trait: InteractionTrait): Double = when (trait) {
        InteractionTrait.Warmth -> warmth
        InteractionTrait.Directness -> directness
        InteractionTrait.Detail -> detail
        InteractionTrait.Emoji -> emoji
        InteractionTrait.Initiative -> initiative
        InteractionTrait.ContextPrecision -> contextPrecision
    }

    fun withValue(trait: InteractionTrait, value: Double): InteractionProfile {
        val bounded = boundedValue(value, trait.defaultValue)
        return when (trait) {
            InteractionTrait.Warmth -> copy(warmth = bounded)
            InteractionTrait.Directness -> copy(directness = bounded)
            InteractionTrait.Detail -> copy(detail = bounded)
            InteractionTrait.Emoji -> copy(emoji = bounded)
            InteractionTrait.Initiative -> copy(initiative = bounded)
            InteractionTrait.ContextPrecision -> copy(contextPrecision = bounded)
        }
    }

    fun normalized(): InteractionProfile = copy(
        warmth = boundedValue(warmth, InteractionTrait.Warmth.defaultValue),
        directness = boundedValue(directness, InteractionTrait.Directness.defaultValue),
        detail = boundedValue(detail, InteractionTrait.Detail.defaultValue),
        emoji = boundedValue(emoji, InteractionTrait.Emoji.defaultValue),
        initiative = boundedValue(initiative, InteractionTrait.Initiative.defaultValue),
        contextPrecision = boundedValue(
            contextPrecision,
            InteractionTrait.ContextPrecision.defaultValue,
        ),
    )

    private fun boundedValue(value: Double, fallback: Double): Double =
        value.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: fallback
}

enum class ContextScope(val label: String) {
    General("General"),
    Continuity("Continuity"),
    Project("Project"),
}

data class ContextDecision(
    val scope: ContextScope,
    val relevanceScore: Int,
    val threshold: Int,
    val reason: String,
    val characterBudget: Int,
    val memoryLimit: Int,
    val recentMessageLimit: Int,
    val archivedMessageLimit: Int,
    val allowMemoryFallback: Boolean,
    val includeWorkspace: Boolean,
)

data class ProfileAdjustment(
    val trait: InteractionTrait,
    val delta: Double,
    val evidence: String,
)

/**
 * Pure policy for bounded style learning and context selection. It may shape a
 * prompt, but it can never grant tools, widen a workspace, or rewrite identity.
 */
object InteractionProfilePolicy {
    private data class Cue(
        val trait: InteractionTrait,
        val delta: Double,
        val pattern: Regex,
    )

    private val explicitCues = listOf(
        Cue(
            InteractionTrait.Warmth,
            0.08,
            Regex("\\b(?:be|sound|feel|write)\\s+(?:a\\s+little\\s+)?(?:more\\s+)?(?:warm|friendly|human)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Warmth,
            -0.08,
            Regex("\\b(?:be|sound|write)\\s+(?:a\\s+little\\s+)?(?:less|not\\s+so)\\s+(?:warm|chatty|personal)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Directness,
            0.08,
            Regex("\\b(?:be|sound|write)\\s+(?:more\\s+)?(?:direct|decisive)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Directness,
            -0.08,
            Regex("\\b(?:be|sound|write)\\s+less\\s+(?:direct|blunt)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Detail,
            -0.10,
            Regex("\\b(?:be|keep(?:\\s+it)?|make(?:\\s+it)?|write)\\s+(?:more\\s+)?(?:concise|brief|shorter)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Detail,
            0.10,
            Regex("\\b(?:be|make(?:\\s+it)?|write|give)\\s+(?:more\\s+)?(?:detailed|thorough|long[- ]form)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Emoji,
            0.08,
            Regex("\\b(?:use|add|include)\\s+more\\s+emoji(?:s)?\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Emoji,
            -0.12,
            Regex("\\b(?:use\\s+(?:fewer|less)|no|avoid)\\s+emoji(?:s)?\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Initiative,
            0.08,
            Regex("\\b(?:be|act)\\s+more\\s+(?:proactive|autonomous)|take\\s+more\\s+initiative\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.Initiative,
            -0.08,
            Regex("\\bask\\s+(?:me\\s+)?before\\s+(?:continuing|acting|changing)|be\\s+less\\s+(?:proactive|autonomous)\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.ContextPrecision,
            0.08,
            Regex("\\b(?:focus|answer)\\s+(?:only\\s+)?(?:on\\s+)?my\\s+(?:actual\\s+)?question|stop\\s+assuming\\s+(?:the\\s+)?workspace\\b", RegexOption.IGNORE_CASE),
        ),
        Cue(
            InteractionTrait.ContextPrecision,
            -0.08,
            Regex("\\buse\\s+more\\s+(?:conversation|project|workspace)\\s+context|connect\\s+(?:it|this)\\s+to\\s+(?:our|the)\\s+project\\b", RegexOption.IGNORE_CASE),
        ),
    )

    private val workspaceCommand = Regex("^\\s*/(?:files|read)\\b", RegexOption.IGNORE_CASE)
    private val explicitProject = Regex(
        "\\b(?:workspace|repository|repo|project\\s+intermix|anicloudai|codebase|source\\s+tree)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val pathOrFile = Regex(
        "(?:^|\\s)(?:[./~][^\\s]+|[A-Za-z0-9_.-]+\\.(?:kt|kts|java|py|js|ts|tsx|jsx|json|ya?ml|toml|md|txt|sh|gradle|xml|html|css|sql))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val workspaceAction = Regex(
        "\\b(?:read|open|list|inspect|edit|change|write|create|fix|patch|refactor|run|execute|compile|build|test|install)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val technicalObject = Regex(
        "\\b(?:file|folder|directory|path|code|script|function|class|dependency|requirements?|package|gradle|git|android|kotlin|python|terminal|shell|agent|ide|website|webpage|site)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val directListing = Regex(
        "\\b(?:what|which|show|list|read|open)\\s+(?:are\\s+)?(?:the\\s+)?(?:files?|folders?|directories)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val continuity = Regex(
        "\\b(?:this|that|those|these|it|earlier|previous|above|again|continue|still|same\\s+one|last\\s+time|you\\s+said|we\\s+(?:said|were|did))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val genericQuestion = Regex(
        "^\\s*(?:what\\s+is|what\\s+are|define|explain|how\\s+(?:does|do|can)|why\\s+does)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun detectExplicitAdjustments(prompt: String): List<ProfileAdjustment> = explicitCues.mapNotNull { cue ->
        cue.pattern.find(prompt)?.let { match ->
            ProfileAdjustment(cue.trait, cue.delta, match.value.trim())
        }
    }.distinctBy(ProfileAdjustment::trait)

    fun apply(profile: InteractionProfile, adjustments: List<ProfileAdjustment>): InteractionProfile {
        return adjustments.fold(profile) { current, adjustment ->
            current.withValue(
                adjustment.trait,
                current.valueOf(adjustment.trait) + adjustment.delta.coerceIn(-0.12, 0.12),
            )
        }.normalized()
    }

    fun selectContext(prompt: String, profile: InteractionProfile): ContextDecision {
        var score = 0
        val evidence = mutableListOf<String>()
        fun add(points: Int, label: String) {
            score += points
            evidence += label
        }

        if (workspaceCommand.containsMatchIn(prompt)) add(8, "explicit workspace command")
        if (explicitProject.containsMatchIn(prompt)) add(4, "named project/workspace")
        if (pathOrFile.containsMatchIn(prompt)) add(4, "file or path")
        if (directListing.containsMatchIn(prompt)) add(4, "direct file request")
        val hasAction = workspaceAction.containsMatchIn(prompt)
        val hasTechnicalObject = technicalObject.containsMatchIn(prompt)
        if (hasAction && hasTechnicalObject) add(3, "technical action")
        else if (hasTechnicalObject) add(1, "technical term")
        if (genericQuestion.containsMatchIn(prompt) && score < 4) {
            score = (score - 2).coerceAtLeast(0)
            evidence += "self-contained question"
        }

        val threshold = when {
            profile.contextPrecision >= 0.80 -> 4
            profile.contextPrecision >= 0.50 -> 3
            else -> 2
        }
        val hasContinuity = continuity.containsMatchIn(prompt)
        return when {
            score >= threshold -> ContextDecision(
                scope = ContextScope.Project,
                relevanceScore = score,
                threshold = threshold,
                reason = evidence.joinToString().ifBlank { "project evidence met the gate" },
                characterBudget = 10_000,
                memoryLimit = 8,
                recentMessageLimit = 8,
                archivedMessageLimit = 4,
                allowMemoryFallback = true,
                includeWorkspace = true,
            )

            hasContinuity -> ContextDecision(
                scope = ContextScope.Continuity,
                relevanceScore = score,
                threshold = threshold,
                reason = "referential follow-up; conversation continuity included, workspace withheld",
                characterBudget = 6_000,
                memoryLimit = 4,
                recentMessageLimit = 6,
                archivedMessageLimit = 2,
                allowMemoryFallback = true,
                includeWorkspace = false,
            )

            else -> ContextDecision(
                scope = ContextScope.General,
                relevanceScore = score,
                threshold = threshold,
                reason = "ordinary chat; recent session continuity included, workspace withheld",
                characterBudget = 8_000,
                memoryLimit = 3,
                recentMessageLimit = 12,
                archivedMessageLimit = 0,
                allowMemoryFallback = false,
                includeWorkspace = false,
            )
        }
    }

    fun promptDirectives(profile: InteractionProfile): List<String> = listOf(
        "Warmth ${percent(profile.warmth)}: ${band(profile.warmth, "reserved", "warm and natural", "richly human without flattery")}",
        "Directness ${percent(profile.directness)}: ${band(profile.directness, "gentle", "clear and balanced", "lead decisively with the answer")}",
        "Detail ${percent(profile.detail)}: ${band(profile.detail, "brief", "compact but sufficient", "thorough when the task warrants it")}",
        "Emoji ${percent(profile.emoji)}: ${band(profile.emoji, "usually omit", "one when natural", "use sparingly as meaningful anchors")}",
        "Initiative ${percent(profile.initiative)}: ${band(profile.initiative, "wait for direction", "anticipate the useful next step", "proactively advance bounded work")}",
        "Context precision ${percent(profile.contextPrecision)}: prefer direct relevance over incidental recalled material",
    )

    fun percent(value: Double): Int = (value.coerceIn(0.0, 1.0) * 100.0).toInt()

    private fun band(value: Double, low: String, middle: String, high: String): String = when {
        value < 0.34 -> low
        value < 0.72 -> middle
        else -> high
    }
}
