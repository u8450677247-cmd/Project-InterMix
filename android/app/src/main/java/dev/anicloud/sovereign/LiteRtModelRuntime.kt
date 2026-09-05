package dev.anicloud.sovereign.prototype

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private const val PhysicalContextTokens = 8_000
private const val PerformanceOutputTokens = 1_024
private const val AdaptiveOutputTokens = 1_536
private const val QualityOutputTokens = 2_048

data class RuntimeLoadResult(
    val backend: ActiveBackend,
    val fallbackDetail: String? = null,
)

/** One resident LiteRT-LM engine with a measured GPU -> CPU fallback. */
class LiteRtModelRuntime(private val context: Context) {
    @Volatile
    private var conversation: Conversation? = null
    private var engine: Engine? = null

    suspend fun load(model: ImportedModel): RuntimeLoadResult = withContext(Dispatchers.IO) {
        close()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val gpuFailure = try {
            start(model, ActiveBackend.GPU)
            return@withContext RuntimeLoadResult(backend = ActiveBackend.GPU)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure
        }

        close()
        start(model, ActiveBackend.CPU)
        RuntimeLoadResult(
            backend = ActiveBackend.CPU,
            fallbackDetail = "GPU initialization failed; CPU fallback is active" +
                gpuFailure.message?.takeIf(String::isNotBlank)?.let { ": ${sanitize(it)}" }.orEmpty(),
        )
    }

    fun stream(prompt: String, mode: AnswerMode): Flow<String> {
        val activeConversation = conversation
            ?: error("No initialized model conversation is available.")
        val outputLimit = when (mode) {
            AnswerMode.Performance -> PerformanceOutputTokens
            AnswerMode.Adaptive -> AdaptiveOutputTokens
            AnswerMode.Quality -> QualityOutputTokens
        }
        return activeConversation.sendMessageAsync(
            text = prompt,
            repetitionPenaltyConfig = RepetitionPenaltyConfig(
                repetitionPenalty = 1.12f,
                windowSize = 512,
            ),
            noRepeatNgramConfig = NoRepeatNgramConfig(
                noRepeatNgramSize = 4,
                windowSize = 512,
            ),
            maxOutputToken = outputLimit,
        ).map { message -> message.toString() }
    }

    /** JNI cancellation is intentionally separate from coroutine cancellation. */
    fun cancelProcess() {
        runCatching { conversation?.cancelProcess() }
    }

    /**
     * LiteRT-LM cancellation cannot yet roll conversation state back. Recreate
     * the conversation after STOP before accepting another turn.
     */
    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        val activeEngine = engine ?: error("The model engine is not initialized.")
        runCatching { conversation?.close() }
        conversation = activeEngine.createConversation(conversationConfig())
    }

    fun close() {
        val oldConversation = conversation
        conversation = null
        runCatching { oldConversation?.close() }

        val oldEngine = engine
        engine = null
        runCatching { oldEngine?.close() }
    }

    private fun start(model: ImportedModel, backend: ActiveBackend) {
        val cacheDirectory = File(context.cacheDir, "litertlm").apply { mkdirs() }
        val nativeBackend = when (backend) {
            ActiveBackend.GPU -> Backend.GPU()
            ActiveBackend.CPU -> Backend.CPU()
        }
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = nativeBackend,
                maxNumTokens = PhysicalContextTokens,
                cacheDir = cacheDirectory.absolutePath,
            ),
        )
        try {
            candidate.initialize()
            val candidateConversation = candidate.createConversation(conversationConfig())
            engine = candidate
            conversation = candidateConversation
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(
            "You are Sovereign Core, the local intelligence in AniCloudAI. " +
                "Be precise and truthful. Never claim access to tools, memory, grounding, " +
                "files, or measurements unless they were actually supplied. Preserve exact " +
                "numeric values when requested. State uncertainty instead of inventing facts.",
        ),
        samplerConfig = SamplerConfig(
            topK = 64,
            topP = 0.95,
            temperature = 0.7,
        ),
        automaticToolCalling = false,
        channels = emptyList(),
        maxOutputToken = QualityOutputTokens,
    )

    private fun sanitize(raw: String): String = raw
        .replace(context.filesDir.absolutePath, "[app storage]")
        .replace(context.noBackupFilesDir.absolutePath, "[app storage]")
        .replace(Regex("\\s+"), " ")
        .take(180)
}
