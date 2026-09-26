package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationContractTest {
    @Test
    fun automaticLayoutUsesTheDesktopThreshold() {
        assertEquals(1200, DesktopThresholdDp)
        assertEquals(
            FoundationLayout.Phone,
            resolveFoundationLayout(DesktopThresholdDp - 1, LayoutPreference.Automatic),
        )
        assertEquals(
            FoundationLayout.Desktop,
            resolveFoundationLayout(DesktopThresholdDp, LayoutPreference.Automatic),
        )
    }

    @Test
    fun workspaceRemainsAFirstClassDestination() {
        assertTrue(Destination.entries.contains(Destination.Workspace))
        assertEquals("Workspace", Destination.Workspace.label)
    }

    @Test
    fun explicitLayoutChoiceWinsAtAnyWidth() {
        assertEquals(
            FoundationLayout.Desktop,
            resolveFoundationLayout(360, LayoutPreference.Desktop),
        )
        assertEquals(
            FoundationLayout.Phone,
            resolveFoundationLayout(1600, LayoutPreference.Phone),
        )
    }

    @Test
    fun oneResponseAnswerOverrideIsConsumed() {
        val selected = AnswerModeSelection(
            defaultMode = AnswerMode.Adaptive,
            oneResponseOverride = AnswerMode.Quality,
        )
        assertEquals(AnswerMode.Quality, selected.modeForNextResponse())
        assertEquals(AnswerMode.Adaptive, selected.afterResponse().modeForNextResponse())
    }

    @Test
    fun authenticationGraceExpiresOnlyAfterThirtySeconds() {
        val grace = AuthenticationGrace()
        assertTrue(grace.needsAuthentication(0))
        grace.markAuthenticated()
        assertFalse(grace.needsAuthentication(10))
        grace.markBackgrounded(1_000)
        assertFalse(grace.needsAuthentication(31_000))
        assertTrue(grace.needsAuthentication(31_001))
    }

    @Test
    fun repeatedTailIsQuarantinedButOrdinaryTextPasses() {
        val repeated = "the the the the"
        assertEquals(
            "repetition-loop",
            GenerationIntegrityGuard.inspectStreamingText(repeated)?.code,
        )
        assertEquals(
            null,
            GenerationIntegrityGuard.inspectStreamingText(
                "The local model answered once, preserved its values, and stopped normally.",
            ),
        )
    }

    @Test
    fun malformedProtocolSuffixIsWithheldWithoutErasingSafeProse() {
        val raw = "Your goal and preference survived.<INTERACTION>{not executable}</INTERACTION>"
        assertEquals(
            "Your goal and preference survived.",
            ControllerProtocol.visibleStreamingText(raw),
        )
        val parsed = ControllerProtocol.parse(raw)
        assertEquals("Your goal and preference survived.", parsed.visibleText)
        assertTrue(parsed.malformedProtocolSuffix)
        assertEquals(null, GenerationIntegrityGuard.inspectStreamingText(parsed.visibleText))

        assertEquals(
            "Your goal and preference survived.",
            ControllerProtocol.visibleStreamingText("Your goal and preference survived.<INTERA"),
        )
        assertEquals(
            "protocol-leak",
            GenerationIntegrityGuard.inspectStreamingText(raw)?.code,
        )
    }

    @Test
    fun validControllerActionStillParsesAfterProtocolShielding() {
        val raw = "Reading the requested folder." +
            "<INTERMIX_ACTION>{\"kind\":\"list_files\",\"path\":\"src\"}</INTERMIX_ACTION>"
        val parsed = ControllerProtocol.parse(raw)

        assertEquals("Reading the requested folder.", parsed.visibleText)
        assertEquals(WorkspaceActionKind.ListFiles, parsed.workspaceAction?.kind)
        assertEquals("src", parsed.workspaceAction?.path)
        assertFalse(parsed.malformedProtocolSuffix)
    }

    @Test
    fun exactArtifactHandleCanReplaceAMutableReadPath() {
        val artifactId = "WA-0123456789ABCDEF0123456789ABCDEF"
        val raw = "<INTERMIX_ACTION>{\"kind\":\"read_file\",\"artifact_id\":\"$artifactId\"}" +
            "</INTERMIX_ACTION>"
        val parsed = ControllerProtocol.parse(raw)

        assertEquals(WorkspaceActionKind.ReadFile, parsed.workspaceAction?.kind)
        assertEquals("", parsed.workspaceAction?.path)
        assertEquals(artifactId, parsed.workspaceAction?.artifactId)
        assertFalse(parsed.malformedProtocolSuffix)

        val drifted = ControllerProtocol.parse(
            "<INTERMIX_ACTION>{\"kind\":\"read_file\",\"artifact_id\":\"wa-0123456789abcdef0123456789abcdef\"}" +
                "</INTERMIX_ACTION>",
        )
        assertEquals(null, drifted.workspaceAction)
        assertTrue(drifted.malformedProtocolSuffix)
    }

    @Test
    fun emptyWorkspaceMutationsNeverBecomeExecutableActions() {
        listOf(
            "<INTERMIX_ACTION>{\"kind\":\"create_file\",\"path\":\"draft.txt\"}</INTERMIX_ACTION>",
            "<INTERMIX_ACTION>{\"kind\":\"write_file\",\"path\":\"draft.txt\",\"content\":\"\"}</INTERMIX_ACTION>",
            "<INTERMIX_ACTION>{\"kind\":\"write_file\",\"path\":\"draft.txt\",\"content\":\"   \"}</INTERMIX_ACTION>",
        ).forEach { raw ->
            val parsed = ControllerProtocol.parse(raw)
            assertEquals(null, parsed.workspaceAction)
            assertTrue(parsed.malformedProtocolSuffix)
        }
    }

    @Test
    fun missionPathsAnchorExactRootsAndRejectAliasDrift() {
        val root = "story-forge-orbit"
        assertEquals(root, scopeWorkspaceMissionPath("", root))
        assertEquals(
            "$root/installment_01.txt",
            scopeWorkspaceMissionPath("installment_01.txt", root),
        )
        assertEquals(
            "$root/installment_01.txt",
            scopeWorkspaceMissionPath("story-forge-orbit/installment_01.txt", root),
        )
        assertEquals(
            "$root/installment_01.txt",
            scopeWorkspaceMissionPath(
                "story-forge-orbit/story-forge-orbit/installment_01.txt",
                root,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            scopeWorkspaceMissionPath("story-forge_orbit/installment_01.txt", root)
        }
        assertThrows(IllegalStateException::class.java) {
            scopeWorkspaceMissionPath(
                "Story-Forge-Orbit/installment_01.txt",
                root,
            )
        }
        assertEquals(
            "$root/chapters/001.txt",
            scopeWorkspaceMissionPath("chapters/001.txt", root),
        )
    }

    @Test
    fun pathCandidatesAreReviewOnlyAndArtifactIdsRemainExact() {
        val artifactId = "WA-0123456789ABCDEF0123456789ABCDEF"
        assertTrue(isValidWorkspaceArtifactId(artifactId))
        assertEquals(artifactId, normalizeWorkspaceArtifactId(artifactId))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeWorkspaceArtifactId(artifactId.lowercase())
        }
        val candidates = workspaceNameCandidates(
            requestedName = "worldflow.md",
            availableNames = listOf("worldline.md", "worldview.md", "characters.json"),
            limit = 2,
        )
        assertEquals(2, candidates.size)
        assertTrue("worldview.md" in candidates)
    }

    @Test
    fun scopedMissionRepairsOnlyAWriteToAVerifiedMissingTarget() {
        val write = WorkspaceActionProposal(
            kind = WorkspaceActionKind.WriteFile,
            path = "mission/proof.txt",
            content = "verified content",
        )
        val repaired = reconcileScopedWorkspaceAction(write, targetExists = false)

        assertEquals(WorkspaceActionKind.CreateFile, repaired.proposal.kind)
        assertTrue(repaired.detail.contains("verified the target was absent"))
        assertEquals(
            WorkspaceActionKind.WriteFile,
            reconcileScopedWorkspaceAction(write, targetExists = true).proposal.kind,
        )

        val create = write.copy(kind = WorkspaceActionKind.CreateFile)
        assertEquals(
            WorkspaceActionKind.CreateFile,
            reconcileScopedWorkspaceAction(create, targetExists = true).proposal.kind,
        )
    }

    @Test
    fun missionParentRepairPlansAreScopedOrderedAndBounded() {
        val target = "mission/chapters/act-1/opening.md"
        val ordered = orderedMissionParentPaths(target, "mission")
        assertEquals(
            listOf("mission/chapters", "mission/chapters/act-1"),
            ordered,
        )
        assertEquals(
            ordered,
            validateMissionParentRepairPlan(target, "mission", ordered),
        )
        assertEquals(
            emptyList<String>(),
            orderedMissionParentPaths("mission/readme.md", "mission"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            orderedMissionParentPaths("other/chapters/opening.md", "mission")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMissionParentRepairPlan(target, "mission", ordered.reversed())
        }

        val deepTarget = "mission/" + (1..9).joinToString("/") { "level-$it" } + "/proof.md"
        val excessive = orderedMissionParentPaths(deepTarget, "mission")
        assertEquals(MaxMissionParentRepairs + 1, excessive.size)
        assertThrows(IllegalArgumentException::class.java) {
            validateMissionParentRepairPlan(deepTarget, "mission", excessive)
        }
    }

    @Test
    fun recursiveMissionActionPatternsAreDetectedWithoutRejectingProgress() {
        assertTrue(hasRecursiveActionTail(listOf("read:a", "read:a", "read:a")))
        assertTrue(
            hasRecursiveActionTail(
                listOf("read:a", "write:b", "read:a", "write:b", "read:a", "write:b"),
            ),
        )
        assertFalse(
            hasRecursiveActionTail(
                listOf("read:a", "write:b", "read:c", "write:d", "read:e", "write:f"),
            ),
        )
    }

    @Test
    fun exactNumericAnchorsMustSurviveWhenExplicitlyRequested() {
        val prompt = "Preserve every value exactly: 6800 tokens, 6.99 GiB, 0.52x, 814.8 MiB."
        val valid = "Context 6800 tokens; memory 6.99 GiB; voice 0.52x; peak 814.8 MiB."
        val drifted = "Context 6800 tokens; memory about 7 GiB; voice 0.52x."

        assertTrue(GenerationIntegrityGuard.missingExactNumericAnchors(prompt, valid).isEmpty())
        assertEquals(
            listOf("6.99 gib", "814.8 mib"),
            GenerationIntegrityGuard.missingExactNumericAnchors(prompt, drifted),
        )
    }

    @Test
    fun ambientMotionYieldsToInferenceAndThermalPressure() {
        assertTrue(allowsAmbientMotion(status = 0, stage = ModelStage.Ready))
        assertFalse(allowsAmbientMotion(status = 2, stage = ModelStage.Ready))
        assertFalse(allowsAmbientMotion(status = 0, stage = ModelStage.Generating))
        assertTrue(isSevereThermalStatus(3))
        assertFalse(isSevereThermalStatus(2))
    }

    @Test
    fun termuxExecutionRequiresAReviewedBoundedProjectPlan() {
        val dependencyPlan = validateExecutionProposal(
            ExecutionProposal(
                kind = ExecutionKind.InstallDependencies,
                command = "python -m pip install -r requirements.txt",
                networkRequired = true,
                dependencies = listOf("requirements.txt"),
                timeoutSeconds = 9_999,
            ),
        )
        assertEquals(1_800, dependencyPlan.timeoutSeconds)
        val termuxHome = "/data/data/com.termux/files/" + "home"
        assertEquals("$termuxHome/project", normalizeTermuxRoot("\$HOME/project"))
        assertEquals("$termuxHome/project", normalizeTermuxRoot("~/project"))
        assertEquals(
            "$termuxHome/project/tests",
            resolveExecutionWorkdir("\$HOME/project", "tests"),
        )
        assertTrue(
            runCatching {
                validateExecutionProposal(
                    ExecutionProposal(ExecutionKind.Run, "rm -rf build"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateExecutionProposal(
                    ExecutionProposal(ExecutionKind.Run, "bash ../outside.sh"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateExecutionProposal(
                    ExecutionProposal(ExecutionKind.Run, "bash -lc 'rm -rf build'"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateExecutionProposal(
                    ExecutionProposal(ExecutionKind.Run, "python -m pip install requests"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                validateExecutionProposal(
                    ExecutionProposal(ExecutionKind.Run, "curl https://example.com"),
                )
            }.isFailure,
        )
    }
}
