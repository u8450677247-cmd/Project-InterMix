package dev.anicloud.sovereign.prototype

enum class ContinuitySignalKind(
    val memoryKind: String,
    val label: String,
) {
    Project("project_fact", "project"),
    Preference("user_preference", "preference"),
    Goal("user_goal", "goal"),
    Decision("decision", "decision"),
}

data class ContinuitySignal(
    val kind: ContinuitySignalKind,
    val text: String,
    val salience: Double,
    val currentTask: Boolean,
    val openLoop: Boolean,
    val decision: Boolean,
)

/**
 * Deterministic, extractive continuity capture derived from the mature Termux second-brain lane.
 * It stores only bounded text the user actually wrote. It does not ask the model to invent a
 * summary, psychological trait, tool result, or unstated project fact.
 */
object ContinuityCapturePolicy {
    private val project = Regex(
        "\\b(?:project\\s+intermix|anicloudai|termux|memory\\s+matrix|context\\s+window|" +
            "workspace|repository|roadmap|release|build|benchmark|feature|bug|issue|blocker|" +
            "e2b|e4b|npu|gpu|litert|" +
            "we\\s+(?:built|build|verified|decided|need|should|will)|" +
            "let['’]s\\s+(?:build|add|fix|implement|proceed|test))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val preference = Regex(
        "\\b(?:i\\s+(?:prefer|like|want|would\\s+rather|don['’]t\\s+want)|" +
            "my\\s+preference\\s+is|please\\s+(?:keep|make|use|avoid))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val goal = Regex(
        "\\b(?:my\\s+goal\\s+is|i\\s+(?:aim|intend|plan|need|want)\\s+to|" +
            "we\\s+need\\s+to|next\\s+step|must\\s+do|let['’]s)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val decision = Regex(
        "\\b(?:we\\s+(?:decided|agreed|will)|i\\s+(?:decided|will)|" +
            "the\\s+decision\\s+is|let['’]s\\s+proceed|should\\s+be)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val unresolved = Regex(
        "\\b(?:still|not\\s+yet|missing|need\\s+to|next\\s+step|issue|bug|blocker|" +
            "todo|can['’]t|cannot|doesn['’]t|does\\s+not|fails?|broken|unusable)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun extract(userText: String, limit: Int = 6): List<ContinuitySignal> {
        val clean = userText.replace("\u0000", " ").trim()
        if (clean.isBlank() || clean.startsWith('/')) return emptyList()
        val candidates = clean
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .asSequence()
            .map { it.replace(Regex("\\s+"), " ").trim().take(900) }
            .filter { it.length >= 8 }
            .take(24)

        return candidates.mapNotNull { sentence ->
            val matchesDecision = decision.containsMatchIn(sentence)
            val matchesGoal = goal.containsMatchIn(sentence)
            val matchesPreference = preference.containsMatchIn(sentence)
            val matchesProject = project.containsMatchIn(sentence)
            val kind = when {
                matchesDecision -> ContinuitySignalKind.Decision
                matchesPreference -> ContinuitySignalKind.Preference
                matchesGoal -> ContinuitySignalKind.Goal
                matchesProject -> ContinuitySignalKind.Project
                else -> null
            } ?: return@mapNotNull null
            val isOpenLoop = sentence.endsWith('?') || unresolved.containsMatchIn(sentence)
            ContinuitySignal(
                kind = kind,
                text = sentence,
                salience = when (kind) {
                    ContinuitySignalKind.Decision -> 0.86
                    ContinuitySignalKind.Goal -> 0.82
                    ContinuitySignalKind.Preference -> 0.78
                    ContinuitySignalKind.Project -> 0.74
                },
                currentTask = matchesGoal || (matchesProject && isOpenLoop),
                openLoop = isOpenLoop,
                decision = matchesDecision,
            )
        }.distinctBy { it.kind to it.text.lowercase() }.take(limit.coerceIn(1, 8)).toList()
    }
}
