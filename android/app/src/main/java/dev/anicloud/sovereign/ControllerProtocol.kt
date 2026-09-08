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
