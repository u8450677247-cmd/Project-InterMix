package dev.anicloud.sovereign.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTransportTest {
    @Test
    fun nativeAndTaggedArtifactCreationReachTheSameSemanticController() {
        val native = ToolTransportRouter.decode(
            ToolTransportEnvelope(
                generatedText = "",
                nativeToolCalls = listOf(
                    RuntimeToolCall(
                        name = "artifact_create",
                        arguments = mapOf(
                            "task_id" to "TASK-001",
                            "artifact_type" to "cover_letter",
                            "display_name" to "Bewerbung – München",
                            "content" to "Complete verified draft.",
                        ),
                    ),
                ),
            ),
        )
        val tagged = ToolTransportRouter.decode(
            ToolTransportEnvelope(
                generatedText = """
                    <INTERMIX_ACTION>{"kind":"create_file","task_id":"TASK-001","artifact_type":"cover_letter","display_name":"Bewerbung – München","content":"Complete verified draft."}</INTERMIX_ACTION>
                """.trimIndent(),
            ),
        )

        assertEquals(ToolTransportKind.NativeLiteRt, native.transport)
        assertEquals(ToolTransportKind.TaggedText, tagged.transport)
        assertEquals(tagged.workspaceAction, native.workspaceAction)
        assertNull(native.controllerFailure)
    }

    @Test
    fun malformedOrUnknownNativeCallsFailClosedWithoutAProposal() {
        listOf(
            RuntimeToolCall(
                name = "artifact_create",
                arguments = mapOf(
                    "task_id" to "TASK-001",
                    "artifact_type" to "cover_letter",
                ),
            ),
            RuntimeToolCall(name = "grant_everything", arguments = emptyMap()),
        ).forEach { call ->
            val parsed = NativeLiteRtToolTransport.decode(
                ToolTransportEnvelope("", listOf(call)),
            )

            assertNull(parsed.workspaceAction)
            assertNull(parsed.workspaceTransaction)
            assertEquals(ControllerErrorCode.TOOL_SCHEMA_INVALID, parsed.controllerFailure?.code)
        }
    }

    @Test
    fun nativeTransportRejectsMixedOrMultipleToolCalls() {
        val call = RuntimeToolCall("workspace_list", mapOf("path" to ""))
        val mixed = NativeLiteRtToolTransport.decode(
            ToolTransportEnvelope(
                generatedText = "<INTERMIX_ACTION>{\"kind\":\"list_files\",\"path\":\"\"}</INTERMIX_ACTION>",
                nativeToolCalls = listOf(call),
            ),
        )
        val multiple = NativeLiteRtToolTransport.decode(
            ToolTransportEnvelope("", listOf(call, call.copy(name = "workspace_read"))),
        )

        assertEquals(ControllerErrorCode.TOOL_SCHEMA_INVALID, mixed.controllerFailure?.code)
        assertEquals(ControllerErrorCode.TOOL_SCHEMA_INVALID, multiple.controllerFailure?.code)
        assertNull(mixed.workspaceAction)
        assertNull(multiple.workspaceAction)
    }

    @Test
    fun nativeModeDoesNotSilentlyFallBackToTaggedMutationText() {
        val parsed = ToolTransportRouter.decode(
            ToolTransportEnvelope(
                generatedText = "<INTERMIX_ACTION>{\"kind\":\"list_files\",\"path\":\"\"}</INTERMIX_ACTION>",
                nativeToolsAvailable = true,
            ),
        )

        assertNull(parsed.workspaceAction)
        assertEquals(ToolTransportKind.NativeLiteRt, parsed.transport)
        assertEquals(ControllerErrorCode.TOOL_SCHEMA_INVALID, parsed.controllerFailure?.code)
    }

    @Test
    fun nativeTransactionJsonStringsBecomeBoundedTypedOperations() {
        val parsed = NativeLiteRtToolTransport.decode(
            ToolTransportEnvelope(
                generatedText = "",
                nativeToolCalls = listOf(
                    RuntimeToolCall(
                        name = "workspace_transaction",
                        arguments = mapOf(
                            "reason" to "inspect both verified sources",
                            "operations" to listOf(
                                "{\"kind\":\"read_file\",\"path\":\"input/a.md\"}",
                                "{\"kind\":\"read_file\",\"path\":\"input/b.md\"}",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(parsed.workspaceTransaction)
        assertEquals(2, parsed.workspaceTransaction?.operations?.size)
        assertTrue(parsed.workspaceTransaction?.operations?.all {
            it.kind == WorkspaceActionKind.ReadFile
        } == true)
        assertNull(parsed.controllerFailure)
    }
}
