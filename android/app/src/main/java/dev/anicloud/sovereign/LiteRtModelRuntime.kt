package dev.anicloud.sovereign.prototype

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
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
import com.google.ai.edge.litertlm.tool
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
    val nativeToolCalling: Boolean = false,
)

/** One resident LiteRT-LM engine; NPU never silently falls back to GPU. */
class LiteRtModelRuntime(private val context: Context) {
    @Volatile
    private var conversation: Conversation? = null
    private var engine: Engine? = null
    private var activeRole: ModelRole = ModelRole.Reasoning
    private val nativeToolProvider by lazy { tool(AniCloudToolSet()) }
    @Volatile
    private var nativeToolsEnabled: Boolean = false
    @Volatile
    private var nativeToolFallbackDetail: String? = null
    private val toolCallLock = Any()
    private var pendingNativeToolCalls: List<RuntimeToolCall> = emptyList()

    suspend fun load(
        model: ImportedModel,
        preference: RuntimeBackendPreference = RuntimeBackendPreference.GpuThenCpu,
    ): RuntimeLoadResult = withContext(Dispatchers.IO) {
        close()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        if (preference == RuntimeBackendPreference.NpuOnly) {
            try {
                start(model, ActiveBackend.NPU)
                return@withContext RuntimeLoadResult(
                    backend = ActiveBackend.NPU,
                    fallbackDetail = nativeToolFallbackDetail,
                    nativeToolCalling = nativeToolsEnabled,
                )
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
            return@withContext RuntimeLoadResult(
                backend = ActiveBackend.GPU,
                fallbackDetail = nativeToolFallbackDetail,
                nativeToolCalling = nativeToolsEnabled,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure
        }

        close()
        start(model, ActiveBackend.CPU)
        RuntimeLoadResult(
            backend = ActiveBackend.CPU,
            fallbackDetail = listOfNotNull(
                "GPU initialization failed; CPU fallback is active" +
                    gpuFailure.message?.takeIf(String::isNotBlank)?.let { ": ${sanitize(it)}" }.orEmpty(),
                nativeToolFallbackDetail,
            ).joinToString(" · "),
            nativeToolCalling = nativeToolsEnabled,
        )
    }

    fun stream(prompt: String, mode: AnswerMode): Flow<String> = streamInternal(
        outputLimit = ContextOrchestrator.outputLimit(prompt, mode),
    ) { active, callback, repetitionPenalty, noRepeatNgram, outputLimit ->
        active.sendMessageAsync(
            text = prompt,
            callback = callback,
            repetitionPenaltyConfig = repetitionPenalty,
            noRepeatNgramConfig = noRepeatNgram,
            maxOutputToken = outputLimit,
        )
    }

    /** Continues the same manual-tool conversation with controller-verified JSON evidence. */
    fun streamToolResponse(
        toolName: String,
        responseJson: String,
        mode: AnswerMode,
    ): Flow<String> {
        require(nativeToolsEnabled) { "Native tool responses require an active typed-tool conversation." }
        val message = Message.tool(
            Contents.of(Content.ToolResponse(toolName, responseJson)),
        )
        return streamInternal(
            outputLimit = ContextOrchestrator.outputLimit(responseJson, mode),
        ) { active, callback, repetitionPenalty, noRepeatNgram, outputLimit ->
            active.sendMessageAsync(
                message = message,
                callback = callback,
                repetitionPenaltyConfig = repetitionPenalty,
                noRepeatNgramConfig = noRepeatNgram,
                maxOutputToken = outputLimit,
            )
        }
    }

    private fun streamInternal(
        outputLimit: Int,
        send: (
            Conversation,
            MessageCallback,
            RepetitionPenaltyConfig,
            NoRepeatNgramConfig,
            Int,
        ) -> Unit,
    ): Flow<String> {
        val activeConversation = conversation
            ?: error("No initialized model conversation is available.")
        synchronized(toolCallLock) { pendingNativeToolCalls = emptyList() }
        // Keep the callback overload even after aligning LiteRT-LM 0.17.0 with
        // coroutines 1.11.0. AniCloudAI owns close/error/cancellation boundaries
        // and does not depend on a precompiled Flow adapter for stream recovery.
        return callbackFlow {
            val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (message.toolCalls.isNotEmpty()) {
                            val calls = message.toolCalls.map { call ->
                                RuntimeToolCall(call.name, call.arguments)
                            }
                            synchronized(toolCallLock) {
                                pendingNativeToolCalls = (pendingNativeToolCalls + calls).distinct()
                            }
                        } else {
                            trySend(message.toString())
                        }
                    }

                    override fun onDone() {
                        close(null)
                    }

                    override fun onError(throwable: Throwable) {
                        close(throwable)
                    }
                }
            send(
                activeConversation,
                callback,
                RepetitionPenaltyConfig(repetitionPenalty = 1.12f, windowSize = 512),
                NoRepeatNgramConfig(noRepeatNgramSize = 4, windowSize = 512),
                outputLimit,
            )
            awaitClose {}
        }
    }

    fun nativeToolCallingEnabled(): Boolean = nativeToolsEnabled

    fun consumeNativeToolCalls(): List<RuntimeToolCall> = synchronized(toolCallLock) {
        pendingNativeToolCalls.also { pendingNativeToolCalls = emptyList() }
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
        conversation = try {
            activeEngine.createConversation(conversationConfig(activeRole, nativeToolsEnabled))
        } catch (failure: Throwable) {
            if (!nativeToolsEnabled) throw failure
            nativeToolsEnabled = false
            nativeToolFallbackDetail = "Native typed tools became unavailable; tagged fallback is active: " +
                sanitize(failure.message ?: failure::class.java.simpleName)
            activeEngine.createConversation(conversationConfig(activeRole, enableNativeTools = false))
        }
    }

    fun close() {
        val oldConversation = conversation
        conversation = null
        runCatching { oldConversation?.close() }

        val oldEngine = engine
        engine = null
        runCatching { oldEngine?.close() }
        nativeToolsEnabled = false
        nativeToolFallbackDetail = null
        synchronized(toolCallLock) { pendingNativeToolCalls = emptyList() }
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
            val candidateConversation = try {
                candidate.createConversation(conversationConfig(model.role, enableNativeTools = true)).also {
                    nativeToolsEnabled = true
                    nativeToolFallbackDetail = null
                }
            } catch (toolFailure: Throwable) {
                nativeToolsEnabled = false
                nativeToolFallbackDetail = "Native typed tools unavailable for this model; " +
                    "tagged fallback is active: " +
                    sanitize(toolFailure.message ?: toolFailure::class.java.simpleName)
                candidate.createConversation(conversationConfig(model.role, enableNativeTools = false))
            }
            engine = candidate
            conversation = candidateConversation
            activeRole = model.role
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun conversationConfig(
        role: ModelRole,
        enableNativeTools: Boolean,
    ) = ConversationConfig(
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
                "Default to visible prose for ordinary conversation, continuity recall, summaries, " +
                "explanations, and planning. Those turns never require a tool. Do not emit, quote, " +
                "imitate, or explain controller tags unless the current request genuinely requires " +
                "the corresponding typed operation.\n\n" +
                "When files must be inspected in ordinary Chat, append exactly one final private block: " +
                "$WorkspaceActionOpenMarker{\"kind\":\"list_files|read_file\",\"path\":\"relative/path\",\"reason\":\"why\"}" +
                "$WorkspaceActionCloseMarker. Reads are limited to the connected workspace. " +
                "When a file or directory should change, use kind create_file, write_file, or " +
                "create_directory and include complete text in content. In ordinary Chat, these " +
                "changes wait for visible user approval. A trusted CONTROLLER-OWNED ACTIVE WORK " +
                "SESSION block is itself an explicit, bounded grant for changes inside its named " +
                "root; its scoped protocol may additionally authorize one bounded typed transaction " +
                "or one logical checkpoint. Continue within that grant without asking again. Never " +
                "request deletion; " +
                "it is unavailable.\n\n" +
                "Only when the current user explicitly states a durable non-sensitive fact or " +
                "preference, append a final private block: $MemoryUpdateOpenMarker" +
                "{\"memories\":[{\"kind\":\"user_fact|user_preference|user_goal|project_fact|decision\"," +
                "\"key\":\"stable_key\",\"value\":\"concise fact\",\"explicit_quote\":\"exact words from the user\"," +
                "\"confidence\":0.9,\"salience\":0.6}]}$MemoryUpdateCloseMarker. " +
                "Never store credentials, health/legal/financial details, diagnoses, guesses, or " +
                "transient conversation. Private blocks are controller protocol, not visible prose.\n\n" +
                (if (role == ModelRole.Conversation) {
                    "You are currently the fast E2B conversation and Memory Matrix librarian. " +
                        "Be warm, concise, continuity-aware, and exact. Do not pretend to have " +
                        "performed deep coding or research work that the controller did not provide."
                } else {
                    "You are currently the E4B reasoning and coding specialist. Prefer complete, " +
                        "technically rigorous work while remaining warm and collaborative."
                }) + (if (enableNativeTools) {
                    "\n\n[NATIVE TOOL MODE]\nUse the registered semantic tools for workspace, artifact, " +
                        "checkpoint, execution-request, and numeric operations. Tool execution is manual: " +
                        "Android validates authority and returns verified evidence. Do not emit INTERMIX tags " +
                        "while native tools are available, and emit at most one semantic tool call per turn."
                } else {
                    "\n\n[TAGGED TOOL FALLBACK]\nNative tool schemas are unavailable for this model. " +
                        "Use the exact INTERMIX compatibility envelopes only when a controller operation is required."
                }),
        ),
        samplerConfig = SamplerConfig(
            topK = 64,
            topP = 0.95,
            temperature = 0.7,
        ),
        automaticToolCalling = false,
        tools = if (enableNativeTools) listOf(nativeToolProvider) else emptyList(),
        channels = emptyList(),
        maxOutputToken = ContextOrchestrator.guaranteedOutputReserve(AnswerMode.Quality),
    )

    private fun sanitize(raw: String): String = raw
        .replace(context.filesDir.absolutePath, "[app storage]")
        .replace(context.noBackupFilesDir.absolutePath, "[app storage]")
        .replace(Regex("\\s+"), " ")
        .take(180)
}
