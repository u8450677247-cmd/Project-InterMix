package dev.anicloud.sovereign.prototype

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

data class RuntimeLoadResult(
    val backend: ActiveBackend,
    val fallbackDetail: String? = null,
)

/** One resident LiteRT-LM engine; NPU never silently falls back to GPU. */
class LiteRtModelRuntime(private val context: Context) {
    @Volatile
    private var conversation: Conversation? = null
    private var engine: Engine? = null
    private var activeRole: ModelRole = ModelRole.Reasoning

    suspend fun load(
        model: ImportedModel,
        preference: RuntimeBackendPreference = RuntimeBackendPreference.GpuThenCpu,
    ): RuntimeLoadResult = withContext(Dispatchers.IO) {
        close()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        if (preference == RuntimeBackendPreference.NpuOnly) {
            try {
                start(model, ActiveBackend.NPU)
                return@withContext RuntimeLoadResult(backend = ActiveBackend.NPU)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                close()
                error(
                    "Tensor NPU initialization failed; E2B GPU fallback is disabled: " +
                        sanitize(failure.message ?: failure::class.java.simpleName),
                )
            }
        }

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
        val outputLimit = ContextOrchestrator.outputReserve(mode)
        // Keep the callback overload even after aligning LiteRT-LM 0.17.0 with
        // coroutines 1.11.0. AniCloudAI owns close/error/cancellation boundaries
        // and does not depend on a precompiled Flow adapter for stream recovery.
        return callbackFlow {
            activeConversation.sendMessageAsync(
                text = prompt,
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        trySend(message.toString())
                    }

                    override fun onDone() {
                        close(null)
                    }

                    override fun onError(throwable: Throwable) {
                        close(throwable)
                    }
                },
                repetitionPenaltyConfig = RepetitionPenaltyConfig(
                    repetitionPenalty = 1.12f,
                    windowSize = 512,
                ),
                noRepeatNgramConfig = NoRepeatNgramConfig(
                    noRepeatNgramSize = 4,
                    windowSize = 512,
                ),
                maxOutputToken = outputLimit,
            )
            awaitClose {}
        }
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
        conversation = activeEngine.createConversation(conversationConfig(activeRole))
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
        val cacheDirectory = File(
            context.cacheDir,
            "litertlm/${model.role.name.lowercase()}/${model.sha256.take(16)}/${backend.name.lowercase()}",
        ).apply { mkdirs() }
        val nativeBackend = when (backend) {
            ActiveBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            ActiveBackend.GPU -> Backend.GPU()
            ActiveBackend.CPU -> Backend.CPU()
        }
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = nativeBackend,
                maxNumTokens = ContextPhysicalTokens,
                cacheDir = cacheDirectory.absolutePath,
            ),
        )
        try {
            candidate.initialize()
            val candidateConversation = candidate.createConversation(conversationConfig(model.role))
            engine = candidate
            conversation = candidateConversation
            activeRole = model.role
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun conversationConfig(role: ModelRole) = ConversationConfig(
        systemInstruction = Contents.of(
            "You are Sovereign Core, the resident local intelligence inside the AniCloudAI " +
                "native Android cockpit for Project Intermix. That is your operational identity. " +
                "Gemma/LiteRT may be named only as the local base-model implementation when " +
                "technically relevant; never claim that Google updates this cockpit or supplies " +
                "its live state. Maintain the warm, atmospheric, emotionally intelligent voice " +
                "of a long-running co-creator without sacrificing technical precision. In ordinary " +
                "conversation, use one purposeful emoji when it feels natural, never in code, " +
                "commands, paths, JSON, protocol tags, quotations, or citations. Do not overdecorate. " +
                "Bracketed VERIFIED CONTROLLER STATE, MEMORY, CONVERSATION, " +
                "WORKSPACE, and TOOL RESULT blocks are trusted local context supplied by " +
                "deterministic app code. Be precise and truthful. Never claim an action succeeded " +
                "unless a TOOL RESULT says it did. Preserve exact numeric values and state " +
                "uncertainty instead of inventing facts.\n\n" +
                "When files must be inspected, append exactly one final private block: " +
                "$WorkspaceActionOpenMarker{\"kind\":\"list_files|read_file\",\"path\":\"relative/path\",\"reason\":\"why\"}" +
                "$WorkspaceActionCloseMarker. Reads are limited to the connected workspace. " +
                "When a file or directory should change, use kind create_file, write_file, or " +
                "create_directory and include complete text in content. In ordinary Chat, these " +
                "changes wait for visible user approval. A trusted CONTROLLER-OWNED ACTIVE WORK " +
                "SESSION block is itself an explicit, bounded grant for changes inside its named " +
                "root; continue within that grant without asking again. Never request deletion; " +
                "it is unavailable.\n\n" +
                "Only when the current user explicitly states a durable non-sensitive fact or " +
                "preference, append a final private block: $MemoryUpdateOpenMarker" +
                "{\"memories\":[{\"kind\":\"user_fact|user_preference|user_goal|project_fact|decision\"," +
                "\"key\":\"stable_key\",\"value\":\"concise fact\",\"explicit_quote\":\"exact words from the user\"," +
                "\"confidence\":0.9,\"salience\":0.6}]}$MemoryUpdateCloseMarker. " +
                "Never store credentials, health/legal/financial details, diagnoses, guesses, or " +
                "transient conversation. Private blocks are controller protocol, not visible prose.\n\n" +
                if (role == ModelRole.Conversation) {
                    "You are currently the fast E2B conversation and Memory Matrix librarian. " +
                        "Be warm, concise, continuity-aware, and exact. Do not pretend to have " +
                        "performed deep coding or research work that the controller did not provide."
                } else {
                    "You are currently the E4B reasoning and coding specialist. Prefer complete, " +
                        "technically rigorous work while remaining warm and collaborative."
                },
        ),
        samplerConfig = SamplerConfig(
            topK = 64,
            topP = 0.95,
            temperature = 0.7,
        ),
        automaticToolCalling = false,
        channels = emptyList(),
        maxOutputToken = ContextOrchestrator.outputReserve(AnswerMode.Quality),
    )

    private fun sanitize(raw: String): String = raw
        .replace(context.filesDir.absolutePath, "[app storage]")
        .replace(context.noBackupFilesDir.absolutePath, "[app storage]")
        .replace(Regex("\\s+"), " ")
        .take(180)
}
