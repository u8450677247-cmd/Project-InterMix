package dev.anicloud.sovereign.prototype

private const val MaximumVisibleGenerationCharacters = 32_768

data class IntegrityViolation(
    val code: String,
    val detail: String,
)

/**
 * A deterministic last line of defence around native streaming.
 *
 * LiteRT-LM receives its own repetition controls as well. This guard exists so
 * a bad native stream can be cancelled before an endless loop becomes a chat
 * message, durable memory, or voice input.
 */
object GenerationIntegrityGuard {
    private val tokenPattern = Regex("[\\p{L}\\p{N}]+(?:[._'-][\\p{L}\\p{N}]+)*")
    private val exactNumberRequest = Regex(
        pattern = "(?:preserve|retain|repeat|copy).{0,48}(?:number|value).{0,24}exact",
        option = RegexOption.IGNORE_CASE,
    )
    private val numericAnchorPattern = Regex(
        "(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)*(?:\\s?(?:GiB|MiB|KiB|GB|MB|kB|tokens?|x|%))?",
        RegexOption.IGNORE_CASE,
    )

    fun inspectStreamingText(text: String): IntegrityViolation? {
        if ('\uFFFD' in text) {
            return IntegrityViolation(
                code = "invalid-unicode",
                detail = "The stream contained a Unicode replacement character.",
            )
        }
        if (text.length > MaximumVisibleGenerationCharacters) {
            return IntegrityViolation(
                code = "output-limit",
                detail = "The response exceeded the local display safety limit.",
            )
        }

        val tokens = tokenPattern.findAll(text.lowercase()).map { it.value }.toList()
        for (width in 1..8) {
            val repeatedLength = width * 4
            if (tokens.size < repeatedLength) continue
            val tail = tokens.takeLast(repeatedLength)
            val unit = tail.take(width)
            if (tail.chunked(width).all { it == unit }) {
                return IntegrityViolation(
                    code = "repetition-loop",
                    detail = "The same $width-token pattern repeated four times.",
                )
            }
        }
        return null
    }

    fun missingExactNumericAnchors(prompt: String, response: String): List<String> {
        if (!exactNumberRequest.containsMatchIn(prompt)) return emptyList()
        val requested = numericAnchorPattern.findAll(prompt)
            .map { normalizeAnchor(it.value) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val returned = numericAnchorPattern.findAll(response)
            .map { normalizeAnchor(it.value) }
            .toSet()
        return requested.filterNot(returned::contains)
    }

    private fun normalizeAnchor(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").lowercase()
}

enum class ModelStage(val label: String) {
    Empty("Not connected"),
    Importing("Importing"),
    Initializing("Initializing"),
    Ready("Ready"),
    Generating("Generating"),
    Recovering("Recovering"),
    Error("Needs attention"),
}

enum class ActiveBackend {
    GPU,
    CPU,
}

data class ImportedModel(
    val displayName: String,
    val absolutePath: String,
    val byteSize: Long,
    val sha256: String,
)

enum class ChatSpeaker {
    User,
    Core,
    System,
}

data class ChatMessage(
    val id: Long,
    val speaker: ChatSpeaker,
    val text: String,
)

data class CockpitState(
    val stage: ModelStage = ModelStage.Empty,
    val detail: String = "Choose a local .litertlm model to begin.",
    val model: ImportedModel? = null,
    val backend: ActiveBackend? = null,
    val importBytesCopied: Long = 0,
    val importBytesTotal: Long? = null,
    val availableMemoryBytes: Long? = null,
    val thermalStatus: Int? = null,
    val thermalHistory: List<Int> = emptyList(),
    val modelLoadMillis: Long? = null,
    val lastFirstTokenMillis: Long? = null,
    val lastResponseMillis: Long? = null,
    val routeLabel: String = "No model route",
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            id = 1,
            speaker = ChatSpeaker.Core,
            text = "Native cockpit ready. Import an E4B .litertlm package to connect Sovereign Core.",
        ),
    ),
    val streamText: String = "",
) {
    val canSend: Boolean
        get() = stage == ModelStage.Ready && !isSevereThermalStatus(thermalStatus)
    val isGenerating: Boolean get() = stage == ModelStage.Generating
}

fun isSevereThermalStatus(status: Int?): Boolean = status != null && status >= 3

fun allowsAmbientMotion(status: Int?, stage: ModelStage): Boolean =
    (status == null || status < 2) && stage != ModelStage.Generating &&
        stage != ModelStage.Initializing && stage != ModelStage.Importing

fun thermalStatusLabel(status: Int?): String = when (status) {
    0 -> "None"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unavailable"
}
