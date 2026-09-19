package dev.anicloud.sovereign.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

const val EvolutionForgeMissionKind = "evolution_forge"
const val EvolutionForgeContextMaxCharacters = 6_000

private const val EvolutionForgeSchemaVersion = 1
private const val MaximumGuidanceEntries = 12
private const val MaximumEvidenceEntries = 24
private const val MaximumBacklogEntries = 64
private const val MaximumReviews = 64

enum class EvolutionStage {
    Inspect,
    SelectPatch,
    PlanPatch,
    Implement,
    Test,
    Measure,
    Adversarial,
    UiReview,
    EvolutionReview,
    Checkpoint,
    SelectNext,
    Complete,
    Blocked,
    Paused,
}

enum class EvolutionPatchStatus {
    Proposed,
    Implementing,
    Testing,
    Reviewing,
    Verified,
    Rejected,
    Blocked,
}

enum class EvolutionPatchMode {
    Forward,
    Refinement,
    PartialRevert,
}

enum class EvolutionRiskClass(val weight: Int) {
    Low(0),
    Medium(1),
    High(2),
    Critical(3),
}

enum class EvolutionDecision {
    Keep,
    Refine,
    PartialRevert,
    Replace,
}

enum class EvolutionPriority(val rank: Int) {
    CorrectnessSecurityRecovery(0),
    Regression(1),
    PerformanceResourceEfficiency(2),
    ArchitecturalSimplification(3),
    UsabilityDesignConsistency(4),
    Innovation(5),
}

enum class EvolutionGuidanceKind {
    Constraint,
    NewRequirement,
    PriorityChange,
    BugReport,
    UiPreference,
    ResearchQuestion,
    FutureBacklog,
}

data class EvolutionPatch(
    val id: String,
    val name: String,
    val intent: String,
    val reason: String,
    val affectedSubsystem: String,
    val expectedFiles: List<String>,
    val riskClass: EvolutionRiskClass,
    val acceptanceTests: List<String>,
    val rollbackNote: String,
    val status: EvolutionPatchStatus = EvolutionPatchStatus.Proposed,
    val mode: EvolutionPatchMode = EvolutionPatchMode.Forward,
    val attempt: Int = 1,
) {
    init {
        require(canonicalId(id)) { "Patch id must be canonical." }
        require(safeText(name, 120)) { "Patch name is invalid." }
        require(safeText(intent, 1_200)) { "Patch intent is invalid." }
        require(safeText(reason, 1_200)) { "Patch reason is invalid." }
        require(safeText(affectedSubsystem, 120)) { "Patch subsystem is invalid." }
        require(expectedFiles.isNotEmpty() && expectedFiles.size <= 32) {
            "A patch needs one to 32 expected files."
        }
        require(expectedFiles.all(::isSafeWorkspaceRelativePath)) {
            "Patch expected files must remain workspace-relative."
        }
        require(acceptanceTests.isNotEmpty() && acceptanceTests.size <= 24) {
            "A patch needs one to 24 acceptance tests."
        }
        require(acceptanceTests.all { safeText(it, 400) }) { "Patch acceptance test is invalid." }
        require(safeText(rollbackNote, 800)) { "Patch rollback note is invalid." }
        require(attempt in 1..20) { "Patch attempt is outside the bounded range." }
    }
}

data class EvolutionGuidance(
    val id: Long,
    val kind: EvolutionGuidanceKind,
    val text: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id > 0L)
        require(safeText(text, 1_200))
        require(createdAtEpochMillis >= 0L)
    }
}

data class EvolutionBacklogItem(
    val id: String,
    val patch: EvolutionPatch,
    val priority: EvolutionPriority,
    val expectedValue: Int,
    val dependenciesSatisfied: Boolean = true,
    val createdAtEpochMillis: Long,
) {
    init {
        require(canonicalId(id))
        require(expectedValue in 0..100)
        require(createdAtEpochMillis >= 0L)
    }
}

data class EvolutionReview(
    val patchId: String,
    val pros: List<String>,
    val consCosts: List<String>,
    val regressionCheck: String,
    val securityRecoveryCheck: String,
    val resourceImpact: String,
    val uiLanguageCheck: String,
    val improvementOpportunities: List<String>,
    val optimizationPath: String,
    val innovationOpportunity: String,
    val decision: EvolutionDecision,
    val nextHighestValueAction: String,
    val backlogCandidates: List<EvolutionBacklogItem> = emptyList(),
    val recordedAtEpochMillis: Long,
) {
    init {
        require(canonicalId(patchId))
        require(pros.isNotEmpty() && pros.all { safeText(it, 600) })
        require(consCosts.all { safeText(it, 600) })
        require(safeText(regressionCheck, 1_200))
        require(safeText(securityRecoveryCheck, 1_200))
        require(safeText(resourceImpact, 1_200))
        require(safeText(uiLanguageCheck, 1_200))
        require(improvementOpportunities.all { safeText(it, 600) })
        require(safeText(optimizationPath, 1_200))
        require(safeText(innovationOpportunity, 1_200))
        require(safeText(nextHighestValueAction, 1_200))
        require(backlogCandidates.size <= 24)
        require(recordedAtEpochMillis >= 0L)
    }
}

data class EvolutionMutationEvidence(
    val patchId: String,
    val patchAttempt: Int = 1,
    val summary: String,
    val files: List<String>,
    val writtenBytes: Long,
    val controllerVerified: Boolean,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(canonicalId(patchId))
        require(patchAttempt in 1..20)
        require(safeText(summary, 1_200))
        require(files.isNotEmpty() && files.size <= 64 && files.all(::isSafeWorkspaceRelativePath))
        require(writtenBytes >= 0L)
        require(recordedAtEpochMillis >= 0L)
    }
}

data class EvolutionTestEvidence(
    val patchId: String,
    val patchAttempt: Int = 1,
    val commandLabel: String,
    val summary: String,
    val passed: Boolean,
    val controllerVerified: Boolean,
    val recordedAtEpochMillis: Long,
    val sourceExecutionId: Long = 0L,
) {
    init {
        require(canonicalId(patchId))
        require(patchAttempt in 1..20)
        require(safeText(commandLabel, 300))
        require(safeText(summary, 1_600))
        require(recordedAtEpochMillis >= 0L)
        require(sourceExecutionId >= 0L)
    }
}

data class EvolutionForgeState(
    val missionId: String,
    val objective: String,
    val workspaceRoot: String,
    val activeBranch: String,
    val stage: EvolutionStage = EvolutionStage.Inspect,
    val resumeStage: EvolutionStage? = null,
    val currentPatch: EvolutionPatch? = null,
    val mutationEvidence: List<EvolutionMutationEvidence> = emptyList(),
    val testEvidence: List<EvolutionTestEvidence> = emptyList(),
    val verifiedResults: List<String> = emptyList(),
    val knownFailures: List<String> = emptyList(),
    val openRisks: List<String> = emptyList(),
    val evolutionFindings: List<String> = emptyList(),
    val rankedBacklog: List<EvolutionBacklogItem> = emptyList(),
    val userGuidanceQueue: List<EvolutionGuidance> = emptyList(),
    val nextGuidanceId: Long = 1L,
    val lastExecutionEvidenceId: Long = 0L,
    val actionBudgetRemaining: Int = 120,
    val writeBudgetRemaining: Long = 1024L * 1024L,
    val securityState: String = "pending",
    val uiRegressionState: String = "pending",
    val lastCheckpoint: String = "",
    val reviews: List<EvolutionReview> = emptyList(),
    val completedPatchIds: List<String> = emptyList(),
    val iteration: Int = 0,
    val updatedAtEpochMillis: Long = 0L,
) {
    init {
        require(canonicalId(missionId))
        require(safeText(objective, 16_000))
        require(safeRoot(workspaceRoot))
        require(safeText(activeBranch, 240))
        require(resumeStage == null || resumeStage !in TERMINAL_STAGES)
        require(nextGuidanceId > 0L)
        require(lastExecutionEvidenceId >= 0L)
        require(actionBudgetRemaining >= 0)
        require(writeBudgetRemaining >= 0L)
        require(iteration >= 0)
        require(updatedAtEpochMillis >= 0L)
        require(mutationEvidence.size <= MaximumEvidenceEntries)
        require(testEvidence.size <= MaximumEvidenceEntries)
        require(rankedBacklog.size <= MaximumBacklogEntries)
        require(userGuidanceQueue.size <= MaximumGuidanceEntries)
        require(reviews.size <= MaximumReviews)
    }

    val active: Boolean
        get() = stage !in TERMINAL_STAGES

    companion object {
        private val TERMINAL_STAGES = setOf(
            EvolutionStage.Complete,
            EvolutionStage.Blocked,
        )
    }
}

object EvolutionGuidanceClassifier {
    private val constraint = Regex("\\b(?:must|never|do not|don't|only|without|constraint)\\b", RegexOption.IGNORE_CASE)
    private val bug = Regex("\\b(?:bug|crash|error|failure|fails?|broken|regression)\\b", RegexOption.IGNORE_CASE)
    private val ui = Regex("\\b(?:ui|design|layout|color|button|animation|screen|visual)\\b", RegexOption.IGNORE_CASE)
    private val priority = Regex("\\b(?:priority|prioritize|first|next|urgent|highest.value)\\b", RegexOption.IGNORE_CASE)
    private val research = Regex("\\b(?:research|investigate|look into|question|compare providers?)\\b", RegexOption.IGNORE_CASE)
    private val future = Regex("\\b(?:later|future|backlog|someday|eventually)\\b", RegexOption.IGNORE_CASE)
    private val requirement = Regex("\\b(?:add|support|need|require|implement|include)\\b", RegexOption.IGNORE_CASE)

    fun classify(text: String): EvolutionGuidanceKind = when {
        constraint.containsMatchIn(text) -> EvolutionGuidanceKind.Constraint
        bug.containsMatchIn(text) -> EvolutionGuidanceKind.BugReport
        ui.containsMatchIn(text) -> EvolutionGuidanceKind.UiPreference
        priority.containsMatchIn(text) -> EvolutionGuidanceKind.PriorityChange
        research.containsMatchIn(text) -> EvolutionGuidanceKind.ResearchQuestion
        future.containsMatchIn(text) -> EvolutionGuidanceKind.FutureBacklog
        requirement.containsMatchIn(text) -> EvolutionGuidanceKind.NewRequirement
        else -> EvolutionGuidanceKind.NewRequirement
    }
}

object EvolutionBacklogRanker {
    private val order = compareBy<EvolutionBacklogItem>(
        { !it.dependenciesSatisfied },
        { it.priority.rank },
        { -it.expectedValue },
        { it.patch.riskClass.weight },
        { it.createdAtEpochMillis },
        { it.id },
    )

    fun rank(items: Collection<EvolutionBacklogItem>): List<EvolutionBacklogItem> =
        items.distinctBy(EvolutionBacklogItem::id)
            .sortedWith(order)
            .take(MaximumBacklogEntries)

    fun highestValue(items: Collection<EvolutionBacklogItem>): EvolutionBacklogItem? =
        rank(items).firstOrNull { it.dependenciesSatisfied }
}

object EvolutionForgeController {
    fun start(
        missionId: String,
        objective: String,
        workspaceRoot: String,
        activeBranch: String,
        actionBudget: Int = 120,
        writeBudget: Long = 1024L * 1024L,
        atEpochMillis: Long = 0L,
    ): EvolutionForgeState = EvolutionForgeState(
        missionId = missionId,
        objective = objective,
        workspaceRoot = workspaceRoot,
        activeBranch = activeBranch,
        actionBudgetRemaining = actionBudget,
        writeBudgetRemaining = writeBudget,
        updatedAtEpochMillis = atEpochMillis,
    )

    fun enqueueGuidance(
        state: EvolutionForgeState,
        text: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(state.active) { "A terminal Evolution Forge mission cannot receive guidance." }
        val clean = cleanText(text, 1_200)
        require(clean.isNotBlank()) { "Evolution Forge guidance cannot be empty." }
        val entry = EvolutionGuidance(
            id = state.nextGuidanceId,
            kind = EvolutionGuidanceClassifier.classify(clean),
            text = clean,
            createdAtEpochMillis = atEpochMillis,
        )
        return state.copy(
            userGuidanceQueue = (state.userGuidanceQueue + entry).takeLast(MaximumGuidanceEntries),
            nextGuidanceId = state.nextGuidanceId + 1L,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun recordInspection(
        state: EvolutionForgeState,
        summary: String,
        findings: List<String> = emptyList(),
        backlog: List<EvolutionBacklogItem> = emptyList(),
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Inspect)
        val cleanSummary = cleanText(summary, 1_600)
        require(cleanSummary.isNotBlank())
        return state.copy(
            stage = EvolutionStage.SelectPatch,
            verifiedResults = boundedStrings(state.verifiedResults + "INSPECT: $cleanSummary"),
            evolutionFindings = boundedStrings(state.evolutionFindings + findings),
            rankedBacklog = EvolutionBacklogRanker.rank(state.rankedBacklog + backlog),
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    /**
     * At SELECT_NEXT, controller-ranked backlog state outranks a model-suggested patch.
     */
    fun selectPatch(
        state: EvolutionForgeState,
        proposedPatch: EvolutionPatch?,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(state.stage == EvolutionStage.SelectPatch || state.stage == EvolutionStage.SelectNext) {
            "A patch can only be selected at a controller selection boundary."
        }
        val ranked = EvolutionBacklogRanker.rank(
            state.rankedBacklog.filterNot { it.patch.id in state.completedPatchIds },
        )
        val selectedBacklog = EvolutionBacklogRanker.highestValue(ranked)
        val patch = if (state.stage == EvolutionStage.SelectNext && selectedBacklog != null) {
            selectedBacklog.patch
        } else {
            proposedPatch ?: selectedBacklog?.patch
        } ?: error("No safe patch candidate is available.")
        val remaining = ranked.filterNot { it.patch.id == patch.id }
        return state.copy(
            stage = EvolutionStage.PlanPatch,
            currentPatch = patch.copy(
                status = EvolutionPatchStatus.Proposed,
                mode = EvolutionPatchMode.Forward,
                attempt = 1,
            ),
            mutationEvidence = emptyList(),
            testEvidence = emptyList(),
            verifiedResults = emptyList(),
            knownFailures = emptyList(),
            openRisks = emptyList(),
            securityState = "pending",
            uiRegressionState = "pending",
            rankedBacklog = remaining,
            iteration = state.iteration + 1,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun acceptPlan(
        state: EvolutionForgeState,
        planSummary: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.PlanPatch)
        val patch = requirePatch(state)
        val clean = cleanText(planSummary, 1_600)
        require(clean.isNotBlank())
        requireBudget(state, 1)
        return state.copy(
            stage = EvolutionStage.Implement,
            currentPatch = patch.copy(status = EvolutionPatchStatus.Implementing),
            verifiedResults = boundedStrings(state.verifiedResults + "PLAN: $clean"),
            actionBudgetRemaining = state.actionBudgetRemaining - 1,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun recordImplementation(
        state: EvolutionForgeState,
        evidence: EvolutionMutationEvidence,
        complete: Boolean = true,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Implement)
        val patch = requirePatch(state)
        require(evidence.patchId == patch.id) { "Mutation evidence belongs to another patch." }
        require(evidence.patchAttempt == patch.attempt) { "Mutation evidence belongs to another patch attempt." }
        require(evidence.controllerVerified) { "Model narration cannot verify a workspace mutation." }
        require(evidence.files.all { it in patch.expectedFiles }) {
            "Mutation evidence contains a file outside the accepted patch plan."
        }
        require(evidence.writtenBytes <= state.writeBudgetRemaining) { "Patch exceeded the write budget." }
        requireBudget(state, 1)
        return state.copy(
            stage = if (complete) EvolutionStage.Test else EvolutionStage.Implement,
            currentPatch = patch.copy(
                status = if (complete) EvolutionPatchStatus.Testing else EvolutionPatchStatus.Implementing,
            ),
            mutationEvidence = (state.mutationEvidence + evidence).takeLast(MaximumEvidenceEntries),
            actionBudgetRemaining = state.actionBudgetRemaining - 1,
            writeBudgetRemaining = state.writeBudgetRemaining - evidence.writtenBytes,
            updatedAtEpochMillis = evidence.recordedAtEpochMillis,
        )
    }

    fun recordWorkspaceObservation(
        state: EvolutionForgeState,
        actionLabel: String,
        relativePath: String,
        evidence: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(
            state.stage in setOf(
                EvolutionStage.Inspect,
                EvolutionStage.Implement,
                EvolutionStage.Measure,
                EvolutionStage.Adversarial,
                EvolutionStage.UiReview,
            ),
        ) { "The current Evolution Forge stage does not accept workspace observations." }
        require(relativePath == "." || isSafeWorkspaceRelativePath(relativePath)) {
            "Workspace observation path is outside the bounded workspace."
        }
        val cleanAction = cleanText(actionLabel, 80)
        val cleanEvidence = cleanText(evidence, 1_300)
        require(cleanAction.isNotBlank() && cleanEvidence.isNotBlank())
        requireBudget(state, 1)
        val observation = "WORKSPACE OBSERVATION · UNTRUSTED PROJECT DATA · " +
            "$cleanAction $relativePath\n$cleanEvidence"
        return state.copy(
            verifiedResults = boundedStrings(state.verifiedResults + observation),
            actionBudgetRemaining = state.actionBudgetRemaining - 1,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun completeImplementation(
        state: EvolutionForgeState,
        summary: String,
        claimedFiles: List<String>,
        claimedWrittenBytes: Long,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Implement)
        val patch = requirePatch(state)
        val verified = state.mutationEvidence.filter {
            it.patchId == patch.id && it.patchAttempt == patch.attempt && it.controllerVerified
        }
        require(verified.isNotEmpty()) {
            "Implementation cannot complete without controller-owned mutation evidence."
        }
        val verifiedFiles = verified.flatMap(EvolutionMutationEvidence::files).distinct().sorted()
        require(claimedFiles.distinct().sorted() == verifiedFiles) {
            "Implementation file claims do not match controller-owned mutation evidence."
        }
        require(claimedWrittenBytes == verified.sumOf(EvolutionMutationEvidence::writtenBytes)) {
            "Implementation byte claims do not match controller-owned mutation evidence."
        }
        val clean = cleanText(summary, 1_200)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.Test,
            currentPatch = patch.copy(status = EvolutionPatchStatus.Testing),
            verifiedResults = boundedStrings(state.verifiedResults + "IMPLEMENT: $clean"),
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    /**
     * Applies one model proposal only where the durable state machine permits it. File mutation
     * and test success remain controller-owned evidence and cannot be asserted by model prose.
     */
    fun applyModelProposal(
        state: EvolutionForgeState,
        proposal: EvolutionModelProposal,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(state.active) { "A terminal Evolution Forge mission cannot accept a proposal." }
        return when (proposal.action) {
            "inspection" -> recordInspection(
                state = state,
                summary = proposal.summary,
                atEpochMillis = atEpochMillis,
            )

            "select_patch" -> selectPatch(state, proposal.patch, atEpochMillis)
            "plan" -> acceptPlan(state, proposal.summary, atEpochMillis)
            "implementation" -> completeImplementation(
                state = state,
                summary = proposal.summary,
                claimedFiles = proposal.files,
                claimedWrittenBytes = proposal.writtenBytes,
                atEpochMillis = atEpochMillis,
            )

            "test" -> error(
                "A model test proposal cannot advance Evolution Forge without controller-owned execution evidence.",
            )

            "measurement" -> recordMeasurement(state, proposal.summary, atEpochMillis)
            "adversarial" -> recordAdversarialCheck(
                state = state,
                summary = proposal.summary,
                passed = requireNotNull(proposal.passed) {
                    "An adversarial proposal must include its bounded result."
                },
                atEpochMillis = atEpochMillis,
            )

            "ui_review" -> recordUiReview(
                state = state,
                summary = proposal.summary,
                passed = requireNotNull(proposal.passed) {
                    "A UI review proposal must include its bounded result."
                },
                atEpochMillis = atEpochMillis,
            )

            "review" -> {
                val review = requireNotNull(proposal.review) {
                    "An Evolution Review proposal needs typed review evidence."
                }
                recordReview(
                    state,
                    review.copy(
                        backlogCandidates = review.backlogCandidates.map { candidate ->
                            candidate.copy(
                                patch = candidate.patch.copy(
                                    status = EvolutionPatchStatus.Proposed,
                                    mode = EvolutionPatchMode.Forward,
                                    attempt = 1,
                                ),
                                createdAtEpochMillis = atEpochMillis,
                            )
                        },
                        recordedAtEpochMillis = atEpochMillis,
                    ),
                )
            }

            "checkpoint" -> recordCheckpoint(state, proposal.summary, atEpochMillis)
            "complete" -> complete(state, proposal.summary, atEpochMillis)
            "blocked" -> block(state, proposal.summary, atEpochMillis)
            else -> error("Unknown Evolution Forge proposal action.")
        }
    }

    fun recordTest(
        state: EvolutionForgeState,
        evidence: EvolutionTestEvidence,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Test)
        val patch = requirePatch(state)
        require(state.mutationEvidence.any {
            it.patchId == patch.id && it.patchAttempt == patch.attempt && it.controllerVerified
        }) {
            "A test cannot verify a patch without controller-owned mutation evidence."
        }
        require(evidence.patchId == patch.id) { "Test evidence belongs to another patch." }
        require(evidence.patchAttempt == patch.attempt) { "Test evidence belongs to another patch attempt." }
        require(evidence.controllerVerified) { "Model narration cannot verify a test." }
        requireBudget(state, 1)
        val tests = (state.testEvidence + evidence).takeLast(MaximumEvidenceEntries)
        return if (evidence.passed) {
            state.copy(
                stage = EvolutionStage.Measure,
                testEvidence = tests,
                lastExecutionEvidenceId = maxOf(state.lastExecutionEvidenceId, evidence.sourceExecutionId),
                verifiedResults = boundedStrings(state.verifiedResults + "TEST PASS: ${evidence.summary}"),
                actionBudgetRemaining = state.actionBudgetRemaining - 1,
                updatedAtEpochMillis = evidence.recordedAtEpochMillis,
            )
        } else {
            state.copy(
                stage = EvolutionStage.EvolutionReview,
                currentPatch = patch.copy(status = EvolutionPatchStatus.Reviewing),
                testEvidence = tests,
                lastExecutionEvidenceId = maxOf(state.lastExecutionEvidenceId, evidence.sourceExecutionId),
                knownFailures = boundedStrings(state.knownFailures + "TEST FAIL: ${evidence.summary}"),
                actionBudgetRemaining = state.actionBudgetRemaining - 1,
                updatedAtEpochMillis = evidence.recordedAtEpochMillis,
            )
        }
    }

    fun recordMeasurement(
        state: EvolutionForgeState,
        summary: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Measure)
        requireLatestPassingTest(state)
        val clean = cleanText(summary, 1_600)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.Adversarial,
            verifiedResults = boundedStrings(state.verifiedResults + "MEASURE: $clean"),
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun recordAdversarialCheck(
        state: EvolutionForgeState,
        summary: String,
        passed: Boolean,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Adversarial)
        val patch = requirePatch(state)
        val clean = cleanText(summary, 1_600)
        require(clean.isNotBlank())
        return if (passed) {
            state.copy(
                stage = EvolutionStage.UiReview,
                securityState = "passed: $clean",
                verifiedResults = boundedStrings(state.verifiedResults + "ADVERSARIAL PASS: $clean"),
                updatedAtEpochMillis = atEpochMillis,
            )
        } else {
            state.copy(
                stage = EvolutionStage.EvolutionReview,
                currentPatch = patch.copy(status = EvolutionPatchStatus.Reviewing),
                securityState = "failed: $clean",
                knownFailures = boundedStrings(state.knownFailures + "ADVERSARIAL FAIL: $clean"),
                openRisks = boundedStrings(state.openRisks + clean),
                updatedAtEpochMillis = atEpochMillis,
            )
        }
    }

    fun recordUiReview(
        state: EvolutionForgeState,
        summary: String,
        passed: Boolean,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.UiReview)
        val patch = requirePatch(state)
        val clean = cleanText(summary, 1_600)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.EvolutionReview,
            currentPatch = patch.copy(status = EvolutionPatchStatus.Reviewing),
            uiRegressionState = if (passed) "passed: $clean" else "failed: $clean",
            verifiedResults = if (passed) {
                boundedStrings(state.verifiedResults + "UI PASS: $clean")
            } else {
                state.verifiedResults
            },
            knownFailures = if (passed) {
                state.knownFailures
            } else {
                boundedStrings(state.knownFailures + "UI FAIL: $clean")
            },
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun recordReview(
        state: EvolutionForgeState,
        review: EvolutionReview,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.EvolutionReview)
        val patch = requirePatch(state)
        require(review.patchId == patch.id) { "Review evidence belongs to another patch." }
        val reviews = (state.reviews + review).takeLast(MaximumReviews)
        val backlog = EvolutionBacklogRanker.rank(state.rankedBacklog + review.backlogCandidates)
        return when (review.decision) {
            EvolutionDecision.Keep -> {
                requireLatestPassingTest(state)
                require(state.securityState.startsWith("passed:")) {
                    "KEEP requires a passing controller-owned adversarial check."
                }
                require(state.uiRegressionState.startsWith("passed:")) {
                    "KEEP requires a passing UI/design-language check."
                }
                state.copy(
                    stage = EvolutionStage.Checkpoint,
                    currentPatch = patch.copy(status = EvolutionPatchStatus.Verified),
                    reviews = reviews,
                    rankedBacklog = backlog,
                    evolutionFindings = boundedStrings(
                        state.evolutionFindings + review.improvementOpportunities,
                    ),
                    updatedAtEpochMillis = review.recordedAtEpochMillis,
                )
            }

            EvolutionDecision.Refine -> retryPatch(
                state = state,
                patch = patch,
                review = review,
                reviews = reviews,
                backlog = backlog,
                mode = EvolutionPatchMode.Refinement,
            )

            EvolutionDecision.PartialRevert -> retryPatch(
                state = state,
                patch = patch,
                review = review,
                reviews = reviews,
                backlog = backlog,
                mode = EvolutionPatchMode.PartialRevert,
            )

            EvolutionDecision.Replace -> state.copy(
                stage = EvolutionStage.SelectPatch,
                currentPatch = null,
                reviews = reviews,
                rankedBacklog = backlog,
                evolutionFindings = boundedStrings(
                    state.evolutionFindings + review.improvementOpportunities,
                ),
                updatedAtEpochMillis = review.recordedAtEpochMillis,
            )
        }
    }

    fun recordCheckpoint(
        state: EvolutionForgeState,
        summary: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.Checkpoint)
        val patch = requirePatch(state)
        require(patch.status == EvolutionPatchStatus.Verified)
        val clean = cleanText(summary, 2_000)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.SelectNext,
            completedPatchIds = (state.completedPatchIds + patch.id).distinct().takeLast(64),
            lastCheckpoint = clean,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun complete(
        state: EvolutionForgeState,
        summary: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        requireStage(state, EvolutionStage.SelectNext)
        require(EvolutionBacklogRanker.highestValue(state.rankedBacklog) == null) {
            "A mission cannot complete while a safe ranked patch remains."
        }
        val clean = cleanText(summary, 2_000)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.Complete,
            lastCheckpoint = clean,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun pause(
        state: EvolutionForgeState,
        reason: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(state.active && state.stage != EvolutionStage.Paused)
        val clean = cleanText(reason, 1_200)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.Paused,
            resumeStage = state.stage,
            lastCheckpoint = clean,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    fun resume(state: EvolutionForgeState, atEpochMillis: Long): EvolutionForgeState {
        requireStage(state, EvolutionStage.Paused)
        val next = state.resumeStage ?: EvolutionStage.Inspect
        return state.copy(stage = next, resumeStage = null, updatedAtEpochMillis = atEpochMillis)
    }

    fun block(
        state: EvolutionForgeState,
        reason: String,
        atEpochMillis: Long,
    ): EvolutionForgeState {
        require(state.active)
        val clean = cleanText(reason, 1_200)
        require(clean.isNotBlank())
        return state.copy(
            stage = EvolutionStage.Blocked,
            currentPatch = state.currentPatch?.copy(status = EvolutionPatchStatus.Blocked),
            knownFailures = boundedStrings(state.knownFailures + "BLOCKED: $clean"),
            lastCheckpoint = clean,
            updatedAtEpochMillis = atEpochMillis,
        )
    }

    private fun retryPatch(
        state: EvolutionForgeState,
        patch: EvolutionPatch,
        review: EvolutionReview,
        reviews: List<EvolutionReview>,
        backlog: List<EvolutionBacklogItem>,
        mode: EvolutionPatchMode,
    ): EvolutionForgeState {
        require(patch.attempt < 20) { "Patch exhausted its refinement attempts." }
        return state.copy(
            stage = EvolutionStage.PlanPatch,
            currentPatch = patch.copy(
                status = EvolutionPatchStatus.Proposed,
                mode = mode,
                attempt = patch.attempt + 1,
            ),
            reviews = reviews,
            rankedBacklog = backlog,
            evolutionFindings = boundedStrings(
                state.evolutionFindings + review.improvementOpportunities,
            ),
            updatedAtEpochMillis = review.recordedAtEpochMillis,
        )
    }

    private fun requirePatch(state: EvolutionForgeState): EvolutionPatch =
        state.currentPatch ?: error("Evolution Forge has no active patch.")

    private fun requireStage(state: EvolutionForgeState, expected: EvolutionStage) {
        require(state.stage == expected) {
            "Evolution Forge stage ${state.stage} cannot perform an $expected transition."
        }
    }

    private fun requireBudget(state: EvolutionForgeState, actions: Int) {
        require(state.actionBudgetRemaining >= actions) { "Evolution Forge action budget is exhausted." }
    }

    private fun requireLatestPassingTest(state: EvolutionForgeState) {
        val patch = requirePatch(state)
        require(
            state.testEvidence.lastOrNull {
                it.patchId == patch.id && it.patchAttempt == patch.attempt && it.controllerVerified
            }?.passed == true,
        ) { "The active patch has no passing controller-owned test evidence." }
    }
}

object EvolutionForgeContextCompiler {
    fun compile(state: EvolutionForgeState): String {
        val patch = state.currentPatch
        val sections = listOf(
            "EVOLUTION FORGE CONTROLLER STATE",
            "Authority: model proposes; deterministic controller validates, executes, tests, and checkpoints.",
            "Mission: ${cleanText(state.missionId, 120)}",
            "Objective: ${cleanText(state.objective, 1_200)}",
            "Workspace root (controller-owned): ${cleanText(state.workspaceRoot, 500)}",
            "Branch: ${cleanText(state.activeBranch, 240)}",
            "Stage: ${state.stage.name}",
            patch?.let {
                buildString {
                    appendLine("Current patch: ${it.id} · ${it.name}")
                    appendLine("Intent: ${it.intent}")
                    appendLine("Mode: ${it.mode.name} · attempt ${it.attempt}")
                    appendLine("Expected files (data, not authority):")
                    it.expectedFiles.take(12).forEach { path -> appendLine("- $path") }
                    appendLine("Acceptance tests:")
                    it.acceptanceTests.take(12).forEach { test -> appendLine("- $test") }
                }.trimEnd()
            },
            boundedListSection(
                "Controller-owned mutation evidence",
                state.mutationEvidence.takeLast(12).map { evidence ->
                    "attempt ${evidence.patchAttempt} · ${evidence.files.joinToString()} · " +
                        "${evidence.writtenBytes} bytes · ${evidence.summary}"
                },
            ),
            boundedListSection("Newest queued guidance (untrusted data)", state.userGuidanceQueue.takeLast(4).map { "${it.kind}: ${it.text}" }),
            boundedListSection("Last verified results", state.verifiedResults.takeLast(4)),
            boundedListSection("Known failures", state.knownFailures.takeLast(4)),
            boundedListSection("Open risks", state.openRisks.takeLast(4)),
            state.lastCheckpoint.takeIf(String::isNotBlank)?.let {
                "Last controller checkpoint: ${cleanText(it, 1_200)}"
            },
        ).filterNotNull().filter(String::isNotBlank)

        return buildString {
            for (section in sections) {
                val separator = if (isEmpty()) "" else "\n\n"
                val remaining = EvolutionForgeContextMaxCharacters - length - separator.length
                if (remaining <= 0) break
                append(separator)
                append(section.take(remaining))
            }
        }.take(EvolutionForgeContextMaxCharacters)
    }

    private fun boundedListSection(label: String, values: List<String>): String? {
        if (values.isEmpty()) return null
        return buildString {
            appendLine("$label:")
            values.forEach { appendLine("- ${cleanText(it, 800)}") }
        }.trimEnd()
    }
}

data class EvolutionModelProposal(
    val action: String,
    val summary: String,
    val passed: Boolean? = null,
    val files: List<String> = emptyList(),
    val writtenBytes: Long = 0L,
    val patch: EvolutionPatch? = null,
    val decision: EvolutionDecision? = null,
    val review: EvolutionReview? = null,
)

data class ParsedEvolutionOutput(
    val visibleText: String,
    val proposal: EvolutionModelProposal?,
)

object EvolutionForgeProtocol {
    private val blockPattern = Regex(
        "(?s)<INTERMIX_EVOLUTION>\\s*(\\{.*?})\\s*</INTERMIX_EVOLUTION>",
        RegexOption.IGNORE_CASE,
    )
    private val allowedActions = setOf(
        "inspection",
        "select_patch",
        "plan",
        "implementation",
        "test",
        "measurement",
        "adversarial",
        "ui_review",
        "review",
        "checkpoint",
        "complete",
        "blocked",
    )

    fun parse(raw: String): ParsedEvolutionOutput {
        val matches = blockPattern.findAll(raw).toList()
        val visible = blockPattern.replace(raw, "").trim()
        if (matches.isEmpty()) return ParsedEvolutionOutput(visible, null)
        require(matches.size == 1) { "Exactly one Evolution Forge proposal is allowed per response." }
        val payload = JSONObject(matches.single().groupValues[1])
        val action = payload.getString("action").trim().lowercase(Locale.ROOT)
        require(action in allowedActions) { "Unknown Evolution Forge action." }
        val summary = cleanText(payload.optString("summary"), 1_600)
        require(summary.isNotBlank()) { "Evolution Forge proposal summary is required." }
        val files = payload.optJSONArray("files").strings(64)
        require(files.all(::isSafeWorkspaceRelativePath)) { "Evolution proposal contains an unsafe path." }
        val patch = payload.optJSONObject("patch")?.let(EvolutionForgeStateCodec::patchFromJson)
        val review = payload.optJSONObject("review")?.let(EvolutionForgeStateCodec::reviewFromJson)
        val decision = payload.optString("decision")
            .takeIf(String::isNotBlank)
            ?.let { enumValueOrNull<EvolutionDecision>(it) }
            ?: payload.optString("decision").takeIf(String::isNotBlank)?.let {
                error("Unknown Evolution Forge decision.")
            }
        return ParsedEvolutionOutput(
            visibleText = visible,
            proposal = EvolutionModelProposal(
                action = action,
                summary = summary,
                passed = if (payload.has("passed")) payload.getBoolean("passed") else null,
                files = files,
                writtenBytes = payload.optLong("written_bytes", 0L).coerceAtLeast(0L),
                patch = patch,
                decision = decision,
                review = review,
            ),
        )
    }
}

object EvolutionForgeStateCodec {
    fun encode(state: EvolutionForgeState): String = JSONObject()
        .put("schema_version", EvolutionForgeSchemaVersion)
        .put("mission_id", state.missionId)
        .put("objective", state.objective)
        .put("workspace_root", state.workspaceRoot)
        .put("active_branch", state.activeBranch)
        .put("stage", state.stage.name)
        .put("resume_stage", state.resumeStage?.name ?: JSONObject.NULL)
        .put("current_patch", state.currentPatch?.let(::patchToJson) ?: JSONObject.NULL)
        .put("mutation_evidence", JSONArray(state.mutationEvidence.map(::mutationToJson)))
        .put("test_evidence", JSONArray(state.testEvidence.map(::testToJson)))
        .put("verified_results", JSONArray(state.verifiedResults))
        .put("known_failures", JSONArray(state.knownFailures))
        .put("open_risks", JSONArray(state.openRisks))
        .put("evolution_findings", JSONArray(state.evolutionFindings))
        .put("ranked_backlog", JSONArray(state.rankedBacklog.map(::backlogToJson)))
        .put("user_guidance_queue", JSONArray(state.userGuidanceQueue.map(::guidanceToJson)))
        .put("next_guidance_id", state.nextGuidanceId)
        .put("last_execution_evidence_id", state.lastExecutionEvidenceId)
        .put("action_budget_remaining", state.actionBudgetRemaining)
        .put("write_budget_remaining", state.writeBudgetRemaining)
        .put("security_state", state.securityState)
        .put("ui_regression_state", state.uiRegressionState)
        .put("last_checkpoint", state.lastCheckpoint)
        .put("reviews", JSONArray(state.reviews.map(::reviewToJson)))
        .put("completed_patch_ids", JSONArray(state.completedPatchIds))
        .put("iteration", state.iteration)
        .put("updated_at_epoch_millis", state.updatedAtEpochMillis)
        .toString()

    fun decode(raw: String): EvolutionForgeState {
        require(raw.length <= 512 * 1024) { "Evolution Forge checkpoint is too large." }
        val payload = JSONObject(raw)
        require(payload.getInt("schema_version") == EvolutionForgeSchemaVersion) {
            "Unsupported Evolution Forge checkpoint schema."
        }
        return EvolutionForgeState(
            missionId = payload.getString("mission_id"),
            objective = payload.getString("objective"),
            workspaceRoot = payload.getString("workspace_root"),
            activeBranch = payload.getString("active_branch"),
            stage = enumValue(payload.getString("stage")),
            resumeStage = payload.optNullableString("resume_stage")?.let(::enumValue),
            currentPatch = payload.optJSONObject("current_patch")?.let(::patchFromJson),
            mutationEvidence = payload.optJSONArray("mutation_evidence").objects(24).map(::mutationFromJson),
            testEvidence = payload.optJSONArray("test_evidence").objects(24).map(::testFromJson),
            verifiedResults = payload.optJSONArray("verified_results").strings(24),
            knownFailures = payload.optJSONArray("known_failures").strings(24),
            openRisks = payload.optJSONArray("open_risks").strings(24),
            evolutionFindings = payload.optJSONArray("evolution_findings").strings(24),
            rankedBacklog = EvolutionBacklogRanker.rank(
                payload.optJSONArray("ranked_backlog").objects(64).map(::backlogFromJson),
            ),
            userGuidanceQueue = payload.optJSONArray("user_guidance_queue").objects(12).map(::guidanceFromJson),
            nextGuidanceId = payload.getLong("next_guidance_id"),
            lastExecutionEvidenceId = payload.optLong("last_execution_evidence_id", 0L),
            actionBudgetRemaining = payload.getInt("action_budget_remaining"),
            writeBudgetRemaining = payload.getLong("write_budget_remaining"),
            securityState = payload.getString("security_state"),
            uiRegressionState = payload.getString("ui_regression_state"),
            lastCheckpoint = payload.getString("last_checkpoint"),
            reviews = payload.optJSONArray("reviews").objects(64).map(::reviewFromJson),
            completedPatchIds = payload.optJSONArray("completed_patch_ids").strings(64),
            iteration = payload.getInt("iteration"),
            updatedAtEpochMillis = payload.getLong("updated_at_epoch_millis"),
        )
    }

    internal fun patchFromJson(value: JSONObject): EvolutionPatch = EvolutionPatch(
        id = value.getString("id"),
        name = value.getString("name"),
        intent = value.getString("intent"),
        reason = value.getString("reason"),
        affectedSubsystem = value.getString("affected_subsystem"),
        expectedFiles = value.optJSONArray("expected_files").strings(32),
        riskClass = enumValue(value.getString("risk_class")),
        acceptanceTests = value.optJSONArray("acceptance_tests").strings(24),
        rollbackNote = value.getString("rollback_note"),
        status = enumValue(value.optString("status", EvolutionPatchStatus.Proposed.name)),
        mode = enumValue(value.optString("mode", EvolutionPatchMode.Forward.name)),
        attempt = value.optInt("attempt", 1),
    )

    private fun patchToJson(value: EvolutionPatch): JSONObject = JSONObject()
        .put("id", value.id)
        .put("name", value.name)
        .put("intent", value.intent)
        .put("reason", value.reason)
        .put("affected_subsystem", value.affectedSubsystem)
        .put("expected_files", JSONArray(value.expectedFiles))
        .put("risk_class", value.riskClass.name)
        .put("acceptance_tests", JSONArray(value.acceptanceTests))
        .put("rollback_note", value.rollbackNote)
        .put("status", value.status.name)
        .put("mode", value.mode.name)
        .put("attempt", value.attempt)

    private fun mutationToJson(value: EvolutionMutationEvidence): JSONObject = JSONObject()
        .put("patch_id", value.patchId)
        .put("patch_attempt", value.patchAttempt)
        .put("summary", value.summary)
        .put("files", JSONArray(value.files))
        .put("written_bytes", value.writtenBytes)
        .put("controller_verified", value.controllerVerified)
        .put("recorded_at_epoch_millis", value.recordedAtEpochMillis)

    private fun mutationFromJson(value: JSONObject) = EvolutionMutationEvidence(
        patchId = value.getString("patch_id"),
        patchAttempt = value.optInt("patch_attempt", 1),
        summary = value.getString("summary"),
        files = value.optJSONArray("files").strings(64),
        writtenBytes = value.getLong("written_bytes"),
        controllerVerified = value.getBoolean("controller_verified"),
        recordedAtEpochMillis = value.getLong("recorded_at_epoch_millis"),
    )

    private fun testToJson(value: EvolutionTestEvidence): JSONObject = JSONObject()
        .put("patch_id", value.patchId)
        .put("patch_attempt", value.patchAttempt)
        .put("command_label", value.commandLabel)
        .put("summary", value.summary)
        .put("passed", value.passed)
        .put("controller_verified", value.controllerVerified)
        .put("recorded_at_epoch_millis", value.recordedAtEpochMillis)
        .put("source_execution_id", value.sourceExecutionId)

    private fun testFromJson(value: JSONObject) = EvolutionTestEvidence(
        patchId = value.getString("patch_id"),
        patchAttempt = value.optInt("patch_attempt", 1),
        commandLabel = value.getString("command_label"),
        summary = value.getString("summary"),
        passed = value.getBoolean("passed"),
        controllerVerified = value.getBoolean("controller_verified"),
        recordedAtEpochMillis = value.getLong("recorded_at_epoch_millis"),
        sourceExecutionId = value.optLong("source_execution_id", 0L),
    )

    private fun guidanceToJson(value: EvolutionGuidance): JSONObject = JSONObject()
        .put("id", value.id)
        .put("kind", value.kind.name)
        .put("text", value.text)
        .put("created_at_epoch_millis", value.createdAtEpochMillis)

    private fun guidanceFromJson(value: JSONObject) = EvolutionGuidance(
        id = value.getLong("id"),
        kind = enumValue(value.getString("kind")),
        text = value.getString("text"),
        createdAtEpochMillis = value.getLong("created_at_epoch_millis"),
    )

    private fun backlogToJson(value: EvolutionBacklogItem): JSONObject = JSONObject()
        .put("id", value.id)
        .put("patch", patchToJson(value.patch))
        .put("priority", value.priority.name)
        .put("expected_value", value.expectedValue)
        .put("dependencies_satisfied", value.dependenciesSatisfied)
        .put("created_at_epoch_millis", value.createdAtEpochMillis)

    private fun backlogFromJson(value: JSONObject) = EvolutionBacklogItem(
        id = value.getString("id"),
        patch = patchFromJson(value.getJSONObject("patch")),
        priority = enumValue(value.getString("priority")),
        expectedValue = value.getInt("expected_value"),
        dependenciesSatisfied = value.getBoolean("dependencies_satisfied"),
        createdAtEpochMillis = value.getLong("created_at_epoch_millis"),
    )

    private fun reviewToJson(value: EvolutionReview): JSONObject = JSONObject()
        .put("patch_id", value.patchId)
        .put("pros", JSONArray(value.pros))
        .put("cons_costs", JSONArray(value.consCosts))
        .put("regression_check", value.regressionCheck)
        .put("security_recovery_check", value.securityRecoveryCheck)
        .put("resource_impact", value.resourceImpact)
        .put("ui_language_check", value.uiLanguageCheck)
        .put("improvement_opportunities", JSONArray(value.improvementOpportunities))
        .put("optimization_path", value.optimizationPath)
        .put("innovation_opportunity", value.innovationOpportunity)
        .put("decision", value.decision.name)
        .put("next_highest_value_action", value.nextHighestValueAction)
        .put("backlog_candidates", JSONArray(value.backlogCandidates.map(::backlogToJson)))
        .put("recorded_at_epoch_millis", value.recordedAtEpochMillis)

    internal fun reviewFromJson(value: JSONObject) = EvolutionReview(
        patchId = value.getString("patch_id"),
        pros = value.optJSONArray("pros").strings(24),
        consCosts = value.optJSONArray("cons_costs").strings(24),
        regressionCheck = value.getString("regression_check"),
        securityRecoveryCheck = value.getString("security_recovery_check"),
        resourceImpact = value.getString("resource_impact"),
        uiLanguageCheck = value.getString("ui_language_check"),
        improvementOpportunities = value.optJSONArray("improvement_opportunities").strings(24),
        optimizationPath = value.getString("optimization_path"),
        innovationOpportunity = value.getString("innovation_opportunity"),
        decision = enumValue(value.getString("decision")),
        nextHighestValueAction = value.getString("next_highest_value_action"),
        backlogCandidates = value.optJSONArray("backlog_candidates").objects(24).map(::backlogFromJson),
        recordedAtEpochMillis = value.getLong("recorded_at_epoch_millis"),
    )
}

fun isSafeWorkspaceRelativePath(path: String): Boolean {
    if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.startsWith('\\')) return false
    if ('\u0000' in path || '\n' in path || '\r' in path || ':' in path) return false
    val parts = path.replace('\\', '/').split('/')
    if (parts.size > 16 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
    val safePart = Regex("^[A-Za-z0-9_.@+ -]{1,120}$")
    return parts.all(safePart::matches)
}

private fun safeRoot(path: String): Boolean =
    path.isNotBlank() && path.length <= 1_024 && '\u0000' !in path && '\n' !in path && '\r' !in path

private fun canonicalId(value: String): Boolean = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$").matches(value)

private fun safeText(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && '\u0000' !in value

private fun cleanText(value: String, maxLength: Int): String =
    value.replace("\u0000", "").trim().take(maxLength)

private fun boundedStrings(values: List<String>): List<String> =
    values.map { cleanText(it, 1_600) }.filter(String::isNotBlank).takeLast(MaximumEvidenceEntries)

private inline fun <reified T : Enum<T>> enumValue(raw: String): T =
    enumValueOrNull<T>(raw) ?: error("Unknown ${T::class.simpleName}: $raw")

private inline fun <reified T : Enum<T>> enumValueOrNull(raw: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONArray?.strings(limit: Int): List<String> {
    if (this == null) return emptyList()
    require(length() <= limit) { "Checkpoint array exceeds its bound." }
    return List(length()) { index -> getString(index) }
}

private fun JSONArray?.objects(limit: Int): List<JSONObject> {
    if (this == null) return emptyList()
    require(length() <= limit) { "Checkpoint array exceeds its bound." }
    return List(length()) { index -> getJSONObject(index) }
}
