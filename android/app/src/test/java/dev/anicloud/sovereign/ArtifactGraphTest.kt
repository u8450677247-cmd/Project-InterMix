package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactGraphTest {
    private val artifactId = "WA-0123456789ABCDEF0123456789ABCDEF"

    @Test
    fun renameOrMoveDoesNotForkLogicalIdentityOrVersion() {
        val unchanged = decideArtifactVersion(
            currentVersion = 3,
            currentContentSha256 = "a".repeat(64),
            observedContentSha256 = "a".repeat(64),
        )

        assertEquals(3, unchanged.version)
        assertFalse(unchanged.createsVersion)
        val node = ArtifactNode(
            artifactId = artifactId,
            taskId = "TASK-007",
            logicalType = "cover_letter",
            displayName = "Renamed cover letter.md",
            status = ArtifactStatus.Verified,
            currentVersion = unchanged.version,
            contentSha256 = "a".repeat(64),
            storageReference = "content://workspace/moved-document",
            createdByRunId = "RUN-00000042",
            verifiedCheckpointId = "CP-000018",
        )
        assertEquals(artifactId, node.artifactId)
    }

    @Test
    fun changedVerifiedContentCreatesProvenancedVersion() {
        val changed = decideArtifactVersion(
            currentVersion = 1,
            currentContentSha256 = "a".repeat(64),
            observedContentSha256 = "b".repeat(64),
        )

        assertTrue(changed.createsVersion)
        assertEquals(2, changed.version)
        val version = ArtifactVersion(
            artifactId = artifactId,
            version = changed.version,
            storageReference = "content://workspace/document",
            contentSha256 = "b".repeat(64),
            createdByRunId = "RUN-00000043",
            supersedesVersion = 1,
        )
        assertEquals(1, version.supersedesVersion)
    }

    @Test
    fun graphSupportsEveryRequiredRelationship() {
        val targetId = "WA-FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
        val relations = ArtifactRelation.entries.map { relation ->
            if (relation in setOf(ArtifactRelation.BELONGS_TO, ArtifactRelation.STORED_AS)) {
                ArtifactEdge(
                    sourceArtifactId = artifactId,
                    relation = relation,
                    targetReference = if (relation == ArtifactRelation.BELONGS_TO) {
                        "TASK-007"
                    } else {
                        "content://workspace/document"
                    },
                )
            } else {
                ArtifactEdge(
                    sourceArtifactId = artifactId,
                    relation = relation,
                    targetArtifactId = targetId,
                )
            }
        }

        assertEquals(ArtifactRelation.entries.toSet(), relations.map(ArtifactEdge::relation).toSet())
    }
}
