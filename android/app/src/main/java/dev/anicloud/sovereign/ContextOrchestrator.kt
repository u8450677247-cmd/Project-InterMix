package dev.anicloud.sovereign.prototype

import kotlin.math.ceil

const val ContextPhysicalTokens = 8_000
const val ContextStrategyPackSize = 20

private const val SystemInstructionReserveTokens = 1_280
private const val ContextSafetyHeadroomTokens = 640
private const val ConservativeCharactersPerToken = 3.0
private const val RecoveryPromptFraction = 0.62

enum class ContextLane(val wireName: String) {
    Chat("chat"),
    Workspace("workspace"),
    Story("story"),
}

/**
 * The context pack is deliberately explicit. Some policies activate only for one lane, but all
 * twenty are shipped, tested, and measured as one controller-owned system rather than a collection
 * of prompt tricks.
 */
enum class ContextStrategy(val label: String) {
    OutputReserve("output reserve"),
    SystemReserve("system reserve"),
    SafetyHeadroom("safety headroom"),
    ConservativeEstimate("conservative estimate"),
    HardPromptFit("hard prompt fit"),
    CurrentRequestPriority("current request priority"),
    RecentTurnReservation("recent turn reservation"),
    SemanticMatrixRecall("semantic Matrix recall"),
    DurableMissionObjective("durable mission objective"),
    ContinuityCapsule("continuity capsule"),
    GuidanceTail("guidance tail"),
    StoryProseTail("story prose tail"),
    LaneSpecificPrompt("lane-specific prompt"),
    ProtocolMinification("protocol minification"),
    WorkspaceContextElision("workspace context elision"),
    FreshControllerConversation("fresh controller conversation"),
    PrivatePayloadShield("private payload shield"),
    DurableChunkCheckpoint("durable chunk checkpoint"),
    IdempotentAppendRecovery("idempotent append recovery"),
    ContextLedgerTelemetry("context ledger telemetry"),
}

data class ContextPack(
    val prompt: String,
    val lane: ContextLane,
    val mode: AnswerMode,
    val promptCharacters: Int,
    val maxPromptCharacters: Int,
    val estimatedPrefillTokens: Int,
    val outputReserveTokens: Int,
    val estimatedHeadroomTokens: Int,
    val compacted: Boolean,
    val recovery: Boolean,
    val strategyCount: Int = ContextStrategyPackSize,
)

/** Conservative, tokenizer-independent guard around LiteRT-LM's physical conversation state. */
object ContextOrchestrator {
    init {
        check(ContextStrategy.entries.size == ContextStrategyPackSize)
    }

    fun outputReserve(mode: AnswerMode): Int = when (mode) {
        AnswerMode.Performance -> 1_024
        AnswerMode.Adaptive -> 1_536
        AnswerMode.Quality -> 2_048
    }

    fun estimateTokens(text: String): Int = ceil(
        text.length / ConservativeCharactersPerToken,
    ).toInt()

    fun pack(
        rawPrompt: String,
        mode: AnswerMode,
        lane: ContextLane,
        recovery: Boolean = false,
    ): ContextPack {
        val outputReserve = outputReserve(mode)
        val promptTokenBudget = (
            ContextPhysicalTokens -
                SystemInstructionReserveTokens -
                outputReserve -
                ContextSafetyHeadroomTokens
            ).coerceAtLeast(1_000)
        val ordinaryCharacterBudget = (promptTokenBudget * ConservativeCharactersPerToken).toInt()
        val characterBudget = if (recovery) {
            (ordinaryCharacterBudget * RecoveryPromptFraction).toInt()
        } else {
            ordinaryCharacterBudget
        }
        val clean = rawPrompt.replace("\u0000", "").trim()
        val fitted = fitPrompt(clean, characterBudget)
        val prefill = SystemInstructionReserveTokens + estimateTokens(fitted)
        return ContextPack(
            prompt = fitted,
            lane = lane,
            mode = mode,
            promptCharacters = fitted.length,
            maxPromptCharacters = characterBudget,
            estimatedPrefillTokens = prefill,
            outputReserveTokens = outputReserve,
            estimatedHeadroomTokens = (ContextPhysicalTokens - prefill - outputReserve).coerceAtLeast(0),
            compacted = fitted != clean,
            recovery = recovery,
        )
    }

    fun isCapacityFailure(failure: Throwable): Boolean {
        val detail = generateSequence(failure) { it.cause }
            .take(4)
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return "prefill input length" in detail ||
            "remaining capacity" in detail ||
            "input length exceeds" in detail ||
            ("context" in detail && "capacity" in detail)
    }

    private fun fitPrompt(raw: String, limit: Int): String {
        if (raw.length <= limit) return raw
        val notice = "\n\n[CONTEXT COMPACTED BY ANDROID · DURABLE MATRIX STATE RETAINED]\n\n"
        val currentMarker = "[CURRENT USER REQUEST]"
        val markerIndex = raw.lastIndexOf(currentMarker)
        if (markerIndex >= 0) {
            val available = (limit - notice.length).coerceAtLeast(0)
            val currentAndNewestState = raw.substring(markerIndex)
            val currentBudget = (available * 0.62).toInt()
            val retainedCurrent = fitHeadAndTail(currentAndNewestState, currentBudget)
            val prefixBudget = (available - retainedCurrent.length).coerceAtLeast(0)
            val retainedContract = fitHeadAndTail(raw.substring(0, markerIndex), prefixBudget)
            return retainedContract + notice + retainedCurrent
        }
        val available = (limit - notice.length).coerceAtLeast(0)
        val retained = fitHeadAndTail(raw, available)
        val split = (retained.length * 0.56).toInt()
        return retained.take(split) + notice + retained.drop(split)
    }

    /** Keeps controller contracts and the newest request/checkpoint instead of a one-sided cut. */
    private fun fitHeadAndTail(raw: String, limit: Int): String {
        if (raw.length <= limit) return raw
        if (limit <= 0) return ""
        val joiner = "\n[...bounded context elision...]\n"
        if (limit <= joiner.length) return raw.takeLast(limit)
        val available = limit - joiner.length
        val head = (available * 0.54).toInt()
        val tail = available - head
        return raw.take(head) + joiner + raw.takeLast(tail)
    }
}
