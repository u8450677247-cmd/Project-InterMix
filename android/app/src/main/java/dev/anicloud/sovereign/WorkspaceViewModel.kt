package dev.anicloud.sovereign.prototype

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceState(
    val rootUri: String? = null,
    val rootLabel: String = "No workspace connected",
    val currentUri: String? = null,
    val currentLabel: String = "",
    val entries: List<WorkspaceEntry> = emptyList(),
    val selected: WorkspaceEntry? = null,
    val editorText: String = "",
    val savedText: String = "",
    val busy: Boolean = false,
    val detail: String = "Grant one project tree to begin. AniCloudAI receives no access outside it.",
) {
    val isDirty: Boolean get() = selected != null && editorText != savedText
}

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository(application)
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    init {
        repository.storedRoot()?.let { loadRoot(it) }
    }

    fun attachRoot(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Forging persistent workspace permission…") }
            runCatching {
                repository.attachRoot(uri)
                repository.rootLabel(uri)
            }.onSuccess { label ->
                _state.value = WorkspaceState(
                    rootUri = uri.toString(),
                    rootLabel = label,
                    currentUri = uri.toString(),
                    currentLabel = label,
                    detail = "Workspace connected with persistent read/write access.",
                )
                refresh()
            }.onFailure(::showFailure)
        }
    }

    fun refresh() {
        val root = _state.value.rootUri?.let(Uri::parse) ?: return
        val current = _state.value.currentUri?.let(Uri::parse) ?: root
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Reading workspace index…") }
            runCatching { repository.listChildren(root, current) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            entries = entries,
                            busy = false,
                            detail = "${entries.size} entries · writes create a private pre-write snapshot",
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun open(entry: WorkspaceEntry) {
        if (_state.value.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before opening another file.") }
            return
        }
        if (entry.isDirectory) {
            _state.update {
                it.copy(
                    currentUri = entry.uri,
                    currentLabel = entry.displayName,
                    selected = null,
                    editorText = "",
                    savedText = "",
                )
            }
            refresh()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Opening ${entry.displayName}…") }
            runCatching { repository.readText(entry) }
                .onSuccess { text ->
                    _state.update {
                        it.copy(
                            selected = entry,
                            editorText = text,
                            savedText = text,
                            busy = false,
                            detail = "Review mode · no write occurs until SAVE",
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    fun updateEditor(text: String) {
        _state.update { it.copy(editorText = text) }
    }

    fun returnToRoot() {
        if (_state.value.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before leaving this file.") }
            return
        }
        val root = _state.value.rootUri ?: return
        _state.update { it.copy(currentUri = root, currentLabel = it.rootLabel, selected = null) }
        refresh()
    }

    fun revert() {
        _state.update { it.copy(editorText = it.savedText, detail = "Draft reverted to the last saved version.") }
    }

    fun save() {
        val selected = _state.value.selected ?: return
        val text = _state.value.editorText
        if (!_state.value.isDirty || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Creating snapshot, then writing…") }
            runCatching { repository.writeText(selected, text) }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            savedText = text,
                            busy = false,
                            detail = "Saved · previous ${snapshot.byteSize} bytes retained as ${snapshot.snapshotName}",
                        )
                    }
                }
                .onFailure(::showFailure)
        }
    }

    private fun loadRoot(root: Uri) {
        viewModelScope.launch {
            val label = runCatching { repository.rootLabel(root) }.getOrDefault("Sovereign Workspace")
            _state.update {
                it.copy(
                    rootUri = root.toString(),
                    rootLabel = label,
                    currentUri = root.toString(),
                    currentLabel = label,
                )
            }
            refresh()
        }
    }

    private fun showFailure(failure: Throwable) {
        _state.update {
            it.copy(
                busy = false,
                detail = "Workspace stopped safely: ${(failure.message ?: failure::class.java.simpleName).take(180)}",
            )
        }
    }
}
