package dev.anicloud.sovereign.prototype

import org.json.JSONObject

const val WorkspaceActionOpenMarker = "<INTERMIX_ACTION>"
const val WorkspaceActionCloseMarker = "</INTERMIX_ACTION>"
const val MemoryUpdateOpenMarker = "<MEMORY_UPDATE>"
const val MemoryUpdateCloseMarker = "</MEMORY_UPDATE>"

enum class WorkspaceActionKind(
    val wireName: String,
    val requiresApproval: Boolean,
) {
    ListFiles("list_files", false),
    ReadFile("read_file", false),
    CreateFile("create_file", true),
    WriteFile("write_file", true),
    CreateDirectory("create_directory", true),
    ;

    companion object {
        fun fromWireName(raw: String): WorkspaceActionKind? = entries.firstOrNull {
            it.wireName == raw.trim().lowercase()
        }
    }
}

data class WorkspaceActionProposal(
    val kind: WorkspaceActionKind,
    val path: String,
    val content: String = "",
    val reason: String = "",
)

data class ControllerProtocolResult(
    val visibleText: String,
    val workspaceAction: WorkspaceActionProposal? = null,
    val memoryPayload: JSONObject? = null,
)

/**
 * Separates controller-owned JSON from model-visible prose. The streaming
 * helper also withholds partial marker prefixes so protocol text never flashes
 * in the cockpit when LiteRT splits a marker across callbacks.
 */
object ControllerProtocol {
    private val openMarkers = listOf(WorkspaceActionOpenMarker, MemoryUpdateOpenMarker)

    /** Exact wire contract injected into every native turn. */
    fun promptContract(): String = """
        [SOVEREIGN CONTROLLER PROTOCOL]
        You are connected to deterministic Android controllers. Never describe these tools as
        hypothetical, and never claim unrestricted filesystem access. Use only the connected
        workspace root described above.

        For a directory listing or file read, emit exactly one action and no speculative result:
        <INTERMIX_ACTION>{"kind":"list_files","path":""}</INTERMIX_ACTION>
        <INTERMIX_ACTION>{"kind":"read_file","path":"relative/file.kt"}</INTERMIX_ACTION>

        For a requested mutation, emit exactly one proposal. Android will show it in Agents and
        nothing is written until the user approves it:
        <INTERMIX_ACTION>{"kind":"create_file","path":"relative/file.md","content":"complete file text","reason":"short reason"}</INTERMIX_ACTION>
        <INTERMIX_ACTION>{"kind":"write_file","path":"existing/file.kt","content":"complete replacement text","reason":"short reason"}</INTERMIX_ACTION>
        <INTERMIX_ACTION>{"kind":"create_directory","path":"relative/folder","reason":"short reason"}</INTERMIX_ACTION>

        Emit raw protocol tags, never fenced protocol JSON. Use at most one workspace action
        per response. Do not invent tool results. After a verified read result, answer from that
        result or request one next read.

        When the current user message explicitly states a durable preference, goal, decision, or
        project fact, visible prose may be followed by one validated memory proposal:
        <MEMORY_UPDATE>{"memories":[{"kind":"user_preference","key":"short_stable_key","value":"classification only","explicit_quote":"exact words copied from the current user message","confidence":0.95,"salience":0.75}]}</MEMORY_UPDATE>
        The explicit_quote must be a verbatim substring of the current request. Never store a
        credential, secret, diagnosis, or inferred sensitive attribute. Omit MEMORY_UPDATE when
        nothing durable was explicitly stated.

        Visible responses support semantic Markdown: headings, lists, blockquotes, **emphasis**,
        inline code, and fenced code with a language name. Use [SUCCESS], [INFO], [ACTION],
        [WARNING], or [BLOCKED] sparingly when a status marker materially helps. Never emit HTML,
        terminal color escapes, or raw color values; Sovereign Glass maps semantics to its palette.
    """.trimIndent()

    fun visibleStreamingText(raw: String): String {
        val completeMarker = openMarkers.map(raw::indexOf).filter { it >= 0 }.minOrNull()
        if (completeMarker != null) return raw.substring(0, completeMarker)

        val withheld = openMarkers.maxOf { marker -> longestMarkerPrefixAtEnd(raw, marker) }
        return if (withheld == 0) raw else raw.dropLast(withheld)
    }

    fun parse(raw: String): ControllerProtocolResult {
        val firstMarker = openMarkers.map(raw::indexOf).filter { it >= 0 }.minOrNull()
        val visible = (firstMarker?.let(raw::substring) ?: raw).trim()
        return ControllerProtocolResult(
            visibleText = visible,
            workspaceAction = extractJson(raw, WorkspaceActionOpenMarker, WorkspaceActionCloseMarker)
                ?.let(::parseWorkspaceAction),
            memoryPayload = extractJson(raw, MemoryUpdateOpenMarker, MemoryUpdateCloseMarker),
        )
    }

    private fun parseWorkspaceAction(payload: JSONObject): WorkspaceActionProposal? {
        val kind = WorkspaceActionKind.fromWireName(payload.optString("kind")) ?: return null
        val path = payload.optString("path").trim()
        if (kind != WorkspaceActionKind.ListFiles && path.isBlank()) return null
        return WorkspaceActionProposal(
            kind = kind,
            path = path,
            content = payload.optString("content").take(64 * 1024),
            reason = payload.optString("reason").trim().take(280),
        )
    }

    private fun extractJson(raw: String, open: String, close: String): JSONObject? {
        val start = raw.indexOf(open)
        if (start < 0) return null
        val contentStart = start + open.length
        val end = raw.indexOf(close, contentStart).takeIf { it >= 0 } ?: raw.length
        val candidate = raw.substring(contentStart, end).trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val objectStart = candidate.indexOf('{')
        val objectEnd = candidate.lastIndexOf('}')
        if (objectStart < 0 || objectEnd <= objectStart) return null
        return runCatching { JSONObject(candidate.substring(objectStart, objectEnd + 1)) }.getOrNull()
    }

    private fun longestMarkerPrefixAtEnd(raw: String, marker: String): Int {
        val maximum = minOf(raw.length, marker.length - 1)
        for (length in maximum downTo 1) {
            if (raw.regionMatches(raw.length - length, marker, 0, length)) return length
        }
        return 0
    }
}
