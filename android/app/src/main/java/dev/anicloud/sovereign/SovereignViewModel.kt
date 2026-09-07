package dev.anicloud.sovereign.prototype

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private class QuarantinedGeneration(val violation: IntegrityViolation) : RuntimeException()

private const val FirstTokenTimeoutMillis = 180_000L
private const val InterChunkTimeoutMillis = 60_000L
private const val StreamUiPublishMillis = 90L

/** Owns the one-resident native model across rotation and window resizing. */
class SovereignViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ModelRepository(application)
    private val conversationRepository = ConversationRepository(application)
    private val runtime = LiteRtModelRuntime(application)
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val activityManager = application.getSystemService(ActivityManager::class.java)
    private val restoredMessages = conversationRepository.load()
    private val _state = MutableStateFlow(
        CockpitState(
            messages = restoredMessages.ifEmpty { CockpitState().messages },
        ),
    )
    val state: StateFlow<CockpitState> = _state.asStateFlow()

    private var importJob: Job? = null
    private var generationJob: Job? = null
    private var generationSerial = 0L
    private var nextMessageId = (_state.value.messages.maxOfOrNull(ChatMessage::id) ?: 0L) + 1L

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        recordThermalStatus(status)
        if (isSevereThermalStatus(status) && _state.value.isGenerating) {
            stopGeneration("Paused because Android reported severe thermal pressure.")
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            state.map { it.messages }
                .distinctUntilChanged()
                .collect { messages -> runCatching { conversationRepository.save(messages) } }
        }
        refreshMemory()
        recordThermalStatus(powerManager.currentThermalStatus)
        powerManager.addThermalStatusListener(application.mainExecutor, thermalListener)
        repository.installedModel()?.let(::loadModel)
    }

    fun importModel(uri: Uri) {
        if (_state.value.isGenerating || importJob?.isActive == true) return
        if (isSevereThermalStatus(_state.value.thermalStatus)) {
            _state.update {
                it.copy(detail = "Model import paused while Android reports severe thermal pressure.")
            }
            return
        }
        importJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    stage = ModelStage.Importing,
                    detail = "Copying into app-private storage and computing SHA-256…",
                    importBytesCopied = 0,
                    importBytesTotal = null,
                    streamText = "",
                )
            }
            val imported = try {
                repository.importModel(uri) { copied, total ->
                    _state.update { state ->
                        state.copy(importBytesCopied = copied, importBytesTotal = total)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                showLoadFailure("Model import failed", failure)
                return@launch
            }
            loadModelNow(imported)
        }
    }

    fun retryModel() {
        if (_state.value.isGenerating || importJob?.isActive == true) return
        (_state.value.model ?: repository.installedModel())?.let(::loadModel)
    }

    fun send(prompt: String, mode: AnswerMode) {
        if (prompt.isBlank() || !_state.value.canSend || generationJob?.isActive == true) return
        val serial = ++generationSerial
        val route = routeLabel(mode)
        _state.update {
            val userMessage = ChatMessage(nextMessageId++, ChatSpeaker.User, prompt)
            it.copy(
                stage = ModelStage.Generating,
                detail = "$route · cancellable native stream",
                routeLabel = route,
                streamText = "",
                messages = it.messages + userMessage,
            )
        }
        runCatching {
            InferenceForegroundService.begin(getApplication()) {
                stopGeneration("Stopped from the Android generation notification.")
            }
        }.onFailure { failure ->
            _state.update { it.copy(detail = "$route · foreground notice unavailable: ${safeFailure(failure)}") }
        }

        generationJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastUiPublishAt = startedAt
            var receivedFirstChunk = false
            try {
                coroutineScope {
                    val chunks = Channel<String>(Channel.BUFFERED)
                    val collector = launch(Dispatchers.Default) {
                        try {
                            runtime.stream(prompt, mode).collect { chunk -> chunks.send(chunk) }
                            chunks.close()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            chunks.close(failure)
                        }
                    }
                    try {
                        while (true) {
                            val timeout = if (receivedFirstChunk) {
                                InterChunkTimeoutMillis
                            } else {
                                FirstTokenTimeoutMillis
                            }
                            val result = withTimeoutOrNull(timeout) {
                                chunks.receiveCatching()
                            } ?: run {
                                runtime.cancelProcess()
                                throw QuarantinedGeneration(
                                    IntegrityViolation(
                                        code = "stalled-stream",
                                        detail = if (receivedFirstChunk) {
                                            "Native generation stopped producing output for 60 seconds."
                                        } else {
                                            "The model produced no first token within 180 seconds."
                                        },
                                    ),
                                )
                            }
                            if (result.isClosed) {
                                result.exceptionOrNull()?.let { throw it }
                                break
                            }
                            val chunk = result.getOrThrow()
                            if (chunk.isNotEmpty()) {
                                lastProgressAt = SystemClock.elapsedRealtime()
                                if (!receivedFirstChunk) {
                                    receivedFirstChunk = true
                                    _state.update {
                                        it.copy(lastFirstTokenMillis = lastProgressAt - startedAt)
                                    }
                                }
                            }
                            accumulated.append(chunk)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiPublishAt >= StreamUiPublishMillis) {
                                val visibleSnapshot = accumulated.toString()
                                GenerationIntegrityGuard.inspectStreamingText(visibleSnapshot)?.let {
                                    throw QuarantinedGeneration(it)
                                }
                                if (serial == generationSerial) {
                                    _state.update { it.copy(streamText = visibleSnapshot) }
                                }
                                lastUiPublishAt = now
                            }
                        }
                    } finally {
                        collector.cancel()
                        chunks.cancel()
                    }
                }
                val completedResponse = accumulated.toString()
                if (completedResponse.isBlank()) {
                    throw QuarantinedGeneration(
                        IntegrityViolation("empty-output", "The native stream completed without text."),
                    )
                }
                GenerationIntegrityGuard.inspectStreamingText(completedResponse)?.let {
                    throw QuarantinedGeneration(it)
                }
                val missingNumbers = GenerationIntegrityGuard.missingExactNumericAnchors(
                    prompt = prompt,
                    response = completedResponse,
                )
                if (missingNumbers.isNotEmpty()) {
                    throw QuarantinedGeneration(
                        IntegrityViolation(
                            code = "numeric-drift",
                            detail = "Missing exact numeric anchors: ${missingNumbers.joinToString().take(160)}",
                        ),
                    )
                }

                if (serial == generationSerial) {
                    _state.update {
                        it.copy(
                            stage = ModelStage.Ready,
                            detail = "Native response complete · $route",
                            streamText = "",
                            lastResponseMillis = SystemClock.elapsedRealtime() - startedAt,
                            messages = it.messages + ChatMessage(
                                nextMessageId++,
                                ChatSpeaker.Core,
                                completedResponse,
                            ),
                        )
                    }
                    refreshMemory()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (quarantined: QuarantinedGeneration) {
                if (serial == generationSerial) {
                    recoverFromGeneration(
                        serial = serial,
                        message = "Response quarantined (${quarantined.violation.code}): " +
                            quarantined.violation.detail,
                    )
                }
            } catch (failure: Throwable) {
                if (serial == generationSerial) {
                    recoverFromGeneration(
                        serial = serial,
                        message = "Native generation failed: ${safeFailure(failure)}",
                    )
                }
            } finally {
                InferenceForegroundService.finish(getApplication())
                if (serial == generationSerial) generationJob = null
            }
        }
    }

    fun stopGeneration() {
        stopGeneration("Stopped by user. Partial output was discarded and not committed.")
    }

    private fun stopGeneration(reason: String) {
        val stoppedJob = generationJob ?: return
        val serial = ++generationSerial
        runtime.cancelProcess()
        stoppedJob.cancel(CancellationException(reason))
        generationJob = null
        _state.update {
            it.copy(
                stage = ModelStage.Recovering,
                detail = "Cancelling native inference and rebuilding conversation state…",
                streamText = "",
            )
        }
        viewModelScope.launch {
            stoppedJob.join()
            val recoveryFailure = resetConversationFailure()
            if (serial != generationSerial) return@launch
            _state.update {
                val notice = if (recoveryFailure == null) reason else
                    "$reason Recovery failed: ${safeFailure(recoveryFailure)}"
                it.copy(
                    stage = if (recoveryFailure == null) ModelStage.Ready else ModelStage.Error,
                    detail = if (recoveryFailure == null) {
                        "Stopped cleanly · native conversation reset"
                    } else {
                        "STOP completed, but the model must be reloaded"
                    },
                    messages = it.messages + ChatMessage(nextMessageId++, ChatSpeaker.System, notice),
                )
            }
            refreshMemory()
        }
    }

    private fun loadModel(model: ImportedModel) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch { loadModelNow(model) }
    }

    private suspend fun loadModelNow(model: ImportedModel) {
        refreshMemory()
        if (isSevereThermalStatus(_state.value.thermalStatus)) {
            _state.update {
                it.copy(
                    stage = ModelStage.Error,
                    detail = "Model load paused while Android reports severe thermal pressure.",
                    model = model,
                )
            }
            return
        }
        _state.update {
            it.copy(
                stage = ModelStage.Initializing,
                detail = "Initializing E4B with GPU first and measured CPU fallback…",
                model = model,
                backend = null,
                importBytesCopied = model.byteSize,
                importBytesTotal = model.byteSize,
                routeLabel = "E4B initialization",
            )
        }
        val loadStartedAt = SystemClock.elapsedRealtime()
        val loaded = try {
            runtime.load(model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            showLoadFailure("E4B initialization failed", failure, model)
            return
        }
        _state.update {
            it.copy(
                stage = ModelStage.Ready,
                detail = loaded.fallbackDetail ?: "E4B initialized on ${loaded.backend.name}",
                backend = loaded.backend,
                modelLoadMillis = SystemClock.elapsedRealtime() - loadStartedAt,
                routeLabel = "E4B · ${loaded.backend.name}",
                messages = it.messages + ChatMessage(
                    nextMessageId++,
                    ChatSpeaker.System,
                    "E4B connected on ${loaded.backend.name}. SHA-256 ${model.sha256.take(16)}…",
                ),
            )
        }
        refreshMemory()
    }

    private suspend fun recoverFromGeneration(serial: Long, message: String) {
        runtime.cancelProcess()
        _state.update {
            it.copy(
                stage = ModelStage.Recovering,
                detail = "Unsafe output blocked · rebuilding native conversation…",
                streamText = "",
            )
        }
        val recoveryFailure = resetConversationFailure()
        if (serial != generationSerial) return
        _state.update {
            it.copy(
                stage = if (recoveryFailure == null) ModelStage.Ready else ModelStage.Error,
                detail = if (recoveryFailure == null) {
                    "Recovered · unsafe partial output was not committed"
                } else {
                    "Output blocked, but the model must be reloaded"
                },
                messages = it.messages + ChatMessage(nextMessageId++, ChatSpeaker.System, message),
            )
        }
        refreshMemory()
    }

    private suspend fun resetConversationFailure(): Throwable? = try {
        runtime.resetConversation()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failure
    }

    private fun showLoadFailure(
        heading: String,
        failure: Throwable,
        model: ImportedModel? = _state.value.model,
    ) {
        runtime.close()
        _state.update {
            it.copy(
                stage = ModelStage.Error,
                detail = "$heading: ${safeFailure(failure)}",
                model = model,
                backend = null,
                routeLabel = "No active model route",
                streamText = "",
            )
        }
        refreshMemory()
    }

    private fun routeLabel(mode: AnswerMode): String {
        val backend = _state.value.backend?.name ?: "backend unavailable"
        return when (mode) {
            AnswerMode.Quality -> "E4B Quality · $backend"
            AnswerMode.Adaptive -> "E4B Adaptive fallback · E2B not installed · $backend"
            AnswerMode.Performance -> "E4B Performance fallback · E2B not installed · $backend"
        }
    }

    private fun refreshMemory() {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        _state.update { it.copy(availableMemoryBytes = info.availMem) }
    }

    private fun recordThermalStatus(status: Int) {
        _state.update {
            it.copy(
                thermalStatus = status,
                thermalHistory = (it.thermalHistory + status).takeLast(32),
            )
        }
    }

    private fun safeFailure(failure: Throwable): String {
        val application = getApplication<Application>()
        return (failure.message ?: failure::class.java.simpleName)
            .replace(application.filesDir.absolutePath, "[app storage]")
            .replace(application.noBackupFilesDir.absolutePath, "[app storage]")
            .replace(Regex("\\s+"), " ")
            .take(220)
    }

    override fun onCleared() {
        powerManager.removeThermalStatusListener(thermalListener)
        importJob?.cancel()
        generationJob?.cancel()
        runtime.cancelProcess()
        runtime.close()
        InferenceForegroundService.finish(getApplication())
        super.onCleared()
    }
}
