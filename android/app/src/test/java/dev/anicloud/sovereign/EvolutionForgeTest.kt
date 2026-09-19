package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EvolutionForgeTest {
    @Test
    fun guidanceIsClassifiedBoundedAndMonotonicAfterEviction() {
        var state = started()
        repeat(14) { index ->
            state = EvolutionForgeController.enqueueGuidance(
                state,
                if (index == 13) "Bug: the app crashes" else "Add requirement ${index + 1}",
                atEpochMillis = index.toLong() + 1L,
            )
        }

        assertEquals(12, state.userGuidanceQueue.size)
        assertEquals(3L, state.userGuidanceQueue.first().id)
        assertEquals(14L, state.userGuidanceQueue.last().id)
        assertEquals(15L, state.nextGuidanceId)
        assertEquals(EvolutionGuidanceKind.BugReport, state.userGuidanceQueue.last().kind)
    }

    @Test
    fun backlogRankingUsesSafetyPriorityBeforeModelValue() {
        val innovation = backlog(
            id = "innovation",
            priority = EvolutionPriority.Innovation,
            value = 100,
            at = 1L,
        )
        val correctness = backlog(
            id = "correctness",
            priority = EvolutionPriority.CorrectnessSecurityRecovery,
            value = 30,
            at = 2L,
        )
        val blocked = backlog(
            id = "blocked",
            priority = EvolutionPriority.CorrectnessSecurityRecovery,
            value = 100,
            at = 0L,
        ).copy(dependenciesSatisfied = false)

        assertEquals(
            listOf("correctness", "innovation", "blocked"),
            EvolutionBacklogRanker.rank(listOf(innovation, blocked, correctness)).map { it.id },
        )
        assertEquals(
            "correctness",
            EvolutionBacklogRanker.highestValue(listOf(innovation, blocked, correctness))?.id,
        )
    }

    @Test
    fun verifiedPatchTraversesEveryRequiredStage() {
        val patch = patch("patch-one")
        var state = planned(patch)
        assertEquals(EvolutionStage.Implement, state.stage)

        state = implement(state, patch, 3L)
        assertEquals(EvolutionStage.Test, state.stage)
        state = test(state, patch, passed = true, at = 4L)
        assertEquals(EvolutionStage.Measure, state.stage)
        state = EvolutionForgeController.recordMeasurement(state, "8 ms median", 5L)
        assertEquals(EvolutionStage.Adversarial, state.stage)
        state = EvolutionForgeController.recordAdversarialCheck(state, "Traversal rejected", true, 6L)
        assertEquals(EvolutionStage.UiReview, state.stage)
        state = EvolutionForgeController.recordUiReview(state, "No visible UI change", true, 7L)
        assertEquals(EvolutionStage.EvolutionReview, state.stage)
        state = EvolutionForgeController.recordReview(state, review(patch.id, EvolutionDecision.Keep, 8L))
        assertEquals(EvolutionStage.Checkpoint, state.stage)
        assertEquals(EvolutionPatchStatus.Verified, state.currentPatch?.status)
        state = EvolutionForgeController.recordCheckpoint(state, "Verified patch one", 9L)

        assertEquals(EvolutionStage.SelectNext, state.stage)
        assertEquals(listOf(patch.id), state.completedPatchIds)
    }

    @Test
    fun failedPatchCanRefineWithoutLosingEvidence() {
        val patch = patch("refine-me")
        var state = implement(planned(patch), patch, 3L)
        state = test(state, patch, passed = false, at = 4L)
        assertEquals(EvolutionStage.EvolutionReview, state.stage)
        assertTrue(state.knownFailures.single().contains("TEST FAIL"))

        state = EvolutionForgeController.recordReview(
            state,
            review(patch.id, EvolutionDecision.Refine, 5L),
        )

        assertEquals(EvolutionStage.PlanPatch, state.stage)
        assertEquals(EvolutionPatchMode.Refinement, state.currentPatch?.mode)
        assertEquals(2, state.currentPatch?.attempt)
        assertTrue(state.testEvidence.isNotEmpty())
        assertTrue(state.knownFailures.isNotEmpty())
    }

    @Test
    fun executionAndTestsCannotBeSkippedByNarration() {
        val patch = patch("no-skips")
        val implementing = planned(patch)
        assertFails {
            EvolutionForgeController.recordTest(
                implementing,
                testEvidence(patch, passed = true, at = 4L),
            )
        }
        assertFails {
            EvolutionForgeController.recordImplementation(
                implementing,
                mutation(patch, at = 3L).copy(controllerVerified = false),
            )
        }
        assertFails {
            EvolutionForgeController.recordTest(
                implementing.copy(stage = EvolutionStage.Test),
                testEvidence(patch, passed = false, at = 4L),
            )
        }
        assertFails {
            EvolutionForgeController.recordTest(
                implement(implementing, patch, 3L).copy(stage = EvolutionStage.Measure),
                testEvidence(patch, passed = false, at = 4L),
            )
        }
    }

    @Test
    fun rankedBacklogOverridesArbitraryNextPatchAndClearsPatchEvidence() {
        val rankedPatch = patch("ranked")
        val arbitraryPatch = patch("arbitrary")
        val previous = started().copy(
            stage = EvolutionStage.SelectNext,
            currentPatch = patch("previous").copy(status = EvolutionPatchStatus.Verified),
            mutationEvidence = listOf(mutation(patch("previous"), 1L)),
            testEvidence = listOf(testEvidence(patch("previous"), true, 2L)),
            verifiedResults = listOf("old result"),
            knownFailures = listOf("old failure"),
            openRisks = listOf("old risk"),
            rankedBacklog = listOf(
                backlog(
                    id = "ranked-item",
                    priority = EvolutionPriority.CorrectnessSecurityRecovery,
                    value = 50,
                    at = 1L,
                    patch = rankedPatch,
                ),
            ),
        )

        val selected = EvolutionForgeController.selectPatch(previous, arbitraryPatch, 3L)

        assertEquals("ranked", selected.currentPatch?.id)
        assertTrue(selected.mutationEvidence.isEmpty())
        assertTrue(selected.testEvidence.isEmpty())
        assertTrue(selected.verifiedResults.isEmpty())
        assertTrue(selected.knownFailures.isEmpty())
        assertTrue(selected.openRisks.isEmpty())
    }

    @Test
    fun deterministicMultiPatchMissionCanPartialRevertThenComplete() {
        val first = patch("first")
        val second = patch("second")
        val secondBacklog = backlog(
            id = "second-item",
            priority = EvolutionPriority.Regression,
            value = 80,
            at = 1L,
            patch = second,
        )

        var state = passToReview(planned(first), first, 3L)
        state = EvolutionForgeController.recordReview(
            state,
            review(first.id, EvolutionDecision.Keep, 8L, listOf(secondBacklog)),
        )
        state = EvolutionForgeController.recordCheckpoint(state, "first verified", 9L)
        state = EvolutionForgeController.selectPatch(state, patch("model-distraction"), 10L)
        assertEquals(second.id, state.currentPatch?.id)

        state = EvolutionForgeController.acceptPlan(state, "Plan second", 11L)
        state = implement(state, second, 12L)
        state = test(state, second, passed = false, at = 13L)
        state = EvolutionForgeController.recordReview(
            state,
            review(second.id, EvolutionDecision.PartialRevert, 14L),
        )
        assertEquals(EvolutionPatchMode.PartialRevert, state.currentPatch?.mode)

        state = EvolutionForgeController.acceptPlan(state, "Revert only the faulty portion", 15L)
        state = implement(state, state.currentPatch!!, 16L)
        state = passToReviewAfterImplementation(state, state.currentPatch!!, 17L)
        state = EvolutionForgeController.recordReview(
            state,
            review(second.id, EvolutionDecision.Keep, 21L),
        )
        state = EvolutionForgeController.recordCheckpoint(state, "second verified after partial revert", 22L)
        state = EvolutionForgeController.complete(state, "Mission complete", 23L)

        assertEquals(EvolutionStage.Complete, state.stage)
        assertEquals(listOf(first.id, second.id), state.completedPatchIds)
        assertEquals(
            listOf(EvolutionDecision.PartialRevert, EvolutionDecision.Keep),
            state.reviews.filter { it.patchId == second.id }.map { it.decision },
        )
    }

    @Test
    fun processDeathRoundTripPreservesExactControllerState() {
        val patch = patch("round-trip")
        var state = planned(patch)
        state = implement(state, patch, 3L)
        state = EvolutionForgeController.enqueueGuidance(state, "Do not widen the workspace root", 4L)
        state = EvolutionForgeController.pause(state, "Process stopped safely", 5L)

        val encoded = EvolutionForgeStateCodec.encode(state)
        val restored = EvolutionForgeStateCodec.decode(encoded)

        assertEquals(state, restored)
        assertEquals(EvolutionStage.Paused, restored.stage)
        assertEquals(EvolutionStage.Test, restored.resumeStage)
        assertEquals(EvolutionStage.Test, EvolutionForgeController.resume(restored, 6L).stage)
    }

    @Test
    fun codecAcceptsLegitimatelyEmptyArrays() {
        val initial = started()
        val restored = EvolutionForgeStateCodec.decode(EvolutionForgeStateCodec.encode(initial))

        assertEquals(initial, restored)
        assertTrue(restored.rankedBacklog.isEmpty())
        assertTrue(restored.reviews.isEmpty())
        assertTrue(restored.knownFailures.isEmpty())
    }

    @Test
    fun compiledPatchContextIsBoundedAndContainsOnlyNewestGuidance() {
        var state = started(objective = "x".repeat(15_000))
        repeat(14) { index ->
            state = EvolutionForgeController.enqueueGuidance(
                state,
                "Add bounded requirement ${index + 1} ${"z".repeat(300)}",
                index.toLong() + 1L,
            )
        }
        state = state.copy(
            stage = EvolutionStage.PlanPatch,
            currentPatch = patch("context"),
            verifiedResults = List(24) { "result-$it ${"r".repeat(300)}" },
            knownFailures = List(24) { "failure-$it ${"f".repeat(300)}" },
            openRisks = List(24) { "risk-$it ${"q".repeat(300)}" },
        )

        val context = EvolutionForgeContextCompiler.compile(state)

        assertTrue(context.length <= EvolutionForgeContextMaxCharacters)
        assertTrue(context.contains("requirement 14"))
        assertFalse(context.contains("requirement 1 "))
        assertTrue(context.contains("model proposes; deterministic controller"))
    }

    @Test
    fun hostilePathsAndIncompleteReviewEvidenceAreRejected() {
        assertFails { patch("hostile", expectedFile = "../outside.txt") }
        assertFails {
            EvolutionForgeProtocol.parse(
                """
                <INTERMIX_EVOLUTION>
                {"action":"implementation","summary":"done","files":["../secret"]}
                </INTERMIX_EVOLUTION>
                """.trimIndent(),
            )
        }
        assertFails {
            EvolutionReview(
                patchId = "hostile",
                pros = listOf("One benefit"),
                consCosts = emptyList(),
                regressionCheck = "",
                securityRecoveryCheck = "checked",
                resourceImpact = "measured",
                uiLanguageCheck = "no visible change",
                improvementOpportunities = emptyList(),
                optimizationPath = "none",
                innovationOpportunity = "none",
                decision = EvolutionDecision.Keep,
                nextHighestValueAction = "next",
                recordedAtEpochMillis = 1L,
            )
        }
    }

    @Test
    fun privateProtocolBlockIsParsedAndRemovedFromVisibleText() {
        val parsed = EvolutionForgeProtocol.parse(
            """
            Visible progress report.
            <INTERMIX_EVOLUTION>
            {
              "action": "implementation",
              "summary": "Controller should verify two files",
              "files": ["src/main.kt", "tests/main_test.kt"],
              "written_bytes": 42
            }
            </INTERMIX_EVOLUTION>
            """.trimIndent(),
        )

        assertEquals("Visible progress report.", parsed.visibleText)
        assertNotNull(parsed.proposal)
        assertEquals("implementation", parsed.proposal?.action)
        assertEquals(listOf("src/main.kt", "tests/main_test.kt"), parsed.proposal?.files)
        assertEquals(42L, parsed.proposal?.writtenBytes)
    }

    private fun started(objective: String = "Improve the bounded project") =
        EvolutionForgeController.start(
            missionId = "EF-1",
            objective = objective,
            workspaceRoot = "/workspace/project",
            activeBranch = "feature/evolution",
            atEpochMillis = 0L,
        )

    private fun planned(patch: EvolutionPatch): EvolutionForgeState {
        var state = EvolutionForgeController.recordInspection(started(), "Workspace inspected", atEpochMillis = 1L)
        state = EvolutionForgeController.selectPatch(state, patch, 2L)
        return EvolutionForgeController.acceptPlan(state, "Bounded patch plan", 2L)
    }

    private fun implement(state: EvolutionForgeState, patch: EvolutionPatch, at: Long) =
        EvolutionForgeController.recordImplementation(state, mutation(patch, at))

    private fun mutation(patch: EvolutionPatch, at: Long) = EvolutionMutationEvidence(
        patchId = patch.id,
        summary = "Controller verified the write",
        files = patch.expectedFiles,
        writtenBytes = 42L,
        controllerVerified = true,
        recordedAtEpochMillis = at,
    )

    private fun test(state: EvolutionForgeState, patch: EvolutionPatch, passed: Boolean, at: Long) =
        EvolutionForgeController.recordTest(state, testEvidence(patch, passed, at))

    private fun testEvidence(patch: EvolutionPatch, passed: Boolean, at: Long) = EvolutionTestEvidence(
        patchId = patch.id,
        commandLabel = "focused unit test",
        summary = if (passed) "All focused checks passed" else "Focused check exposed a regression",
        passed = passed,
        controllerVerified = true,
        recordedAtEpochMillis = at,
    )

    private fun passToReview(state: EvolutionForgeState, patch: EvolutionPatch, at: Long): EvolutionForgeState =
        passToReviewAfterImplementation(implement(state, patch, at), patch, at + 1L)

    private fun passToReviewAfterImplementation(
        state: EvolutionForgeState,
        patch: EvolutionPatch,
        at: Long,
    ): EvolutionForgeState {
        var next = test(state, patch, passed = true, at = at)
        next = EvolutionForgeController.recordMeasurement(next, "Bounded resource impact", at + 1L)
        next = EvolutionForgeController.recordAdversarialCheck(next, "Hostile cases rejected", true, at + 2L)
        return EvolutionForgeController.recordUiReview(next, "No visible UI regression", true, at + 3L)
    }

    private fun patch(id: String, expectedFile: String = "src/$id.kt") = EvolutionPatch(
        id = id,
        name = "Patch $id",
        intent = "Implement one bounded change",
        reason = "Highest-value compatible work",
        affectedSubsystem = "controller",
        expectedFiles = listOf(expectedFile),
        riskClass = EvolutionRiskClass.Low,
        acceptanceTests = listOf("Focused unit test passes"),
        rollbackNote = "Revert the one-file change",
    )

    private fun backlog(
        id: String,
        priority: EvolutionPriority,
        value: Int,
        at: Long,
        patch: EvolutionPatch = patch("patch-$id"),
    ) = EvolutionBacklogItem(
        id = id,
        patch = patch,
        priority = priority,
        expectedValue = value,
        createdAtEpochMillis = at,
    )

    private fun review(
        patchId: String,
        decision: EvolutionDecision,
        at: Long,
        backlog: List<EvolutionBacklogItem> = emptyList(),
    ) = EvolutionReview(
        patchId = patchId,
        pros = listOf("Bounded improvement"),
        consCosts = listOf("Small maintenance cost"),
        regressionCheck = "Focused regression checks recorded",
        securityRecoveryCheck = "Workspace and rollback boundaries preserved",
        resourceImpact = "Measured and bounded",
        uiLanguageCheck = "No visible UI change",
        improvementOpportunities = listOf("Continue with the ranked backlog"),
        optimizationPath = "Optimize only after measurement",
        innovationOpportunity = "Keep optional ideas in backlog",
        decision = decision,
        nextHighestValueAction = "Select the next safe patch",
        backlogCandidates = backlog,
        recordedAtEpochMillis = at,
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected the operation to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected deterministic validation failure.
        } catch (_: IllegalStateException) {
            // Expected deterministic state-machine failure.
        }
    }
}
