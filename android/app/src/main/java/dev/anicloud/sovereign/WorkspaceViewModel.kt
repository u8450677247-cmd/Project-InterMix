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

data class WorkspaceLocation(
    val uri: String,
    val label: String,
)

data class WorkspaceState(
    val rootUri: String? = null,
    val rootLabel: String = "No workspace connected",
    val currentUri: String? = null,
    val currentLabel: String = "",
    val navigationTrail: List<WorkspaceLocation> = emptyList(),
    val entries: List<WorkspaceEntry> = emptyList(),
    val selected: WorkspaceEntry? = null,
    val editorText: String = "",
    val savedText: String = "",
    val pendingTrash: WorkspaceEntry? = null,
    val lastTrash: WorkspaceTrashReceipt? = null,
    val busy: Boolean = false,
    val detail: String = "Grant one project tree to begin. AniCloudAI receives no access outside it.",
) {
    val isDirty: Boolean get() = selected != null && editorText != savedText
    val canNavigateUp: Boolean get() = selected != null || navigationTrail.size > 1
    val breadcrumb: String get() = navigationTrail.joinToString(" / ") { it.label }
}

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WorkspaceRepository(application)
    private val _state = MutableStateFlow(
        WorkspaceState(lastTrash = repository.lastTrashReceipt()),
    )
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
                    navigationTrail = listOf(WorkspaceLocation(uri.toString(), label)),
                    lastTrash = repository.lastTrashReceipt(uri),
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
        if (_state.value.pendingTrash != null) {
            _state.update { it.copy(detail = "Confirm or cancel the reviewed trash request before navigating.") }
            return
        }
        if (_state.value.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before opening another file.") }
            return
        }
        if (entry.isDirectory) {
            _state.update {
                val rootLocation = WorkspaceLocation(it.rootUri.orEmpty(), it.rootLabel)
                val currentTrail = it.navigationTrail.ifEmpty { listOf(rootLocation) }
                it.copy(
                    currentUri = entry.uri,
                    currentLabel = entry.displayName,
                    navigationTrail = currentTrail + WorkspaceLocation(entry.uri, entry.displayName),
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
        if (_state.value.pendingTrash != null) {
            _state.update { it.copy(detail = "Confirm or cancel the reviewed trash request before navigating.") }
            return
        }
        if (_state.value.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before leaving this file.") }
            return
        }
        val root = _state.value.rootUri ?: return
        _state.update {
            it.copy(
                currentUri = root,
                currentLabel = it.rootLabel,
                navigationTrail = listOf(WorkspaceLocation(root, it.rootLabel)),
                selected = null,
                editorText = "",
                savedText = "",
            )
        }
        refresh()
    }

    fun navigateUp() {
        val state = _state.value
        if (state.pendingTrash != null) {
            _state.update { it.copy(detail = "Confirm or cancel the reviewed trash request before navigating.") }
            return
        }
        if (state.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before navigating up.") }
            return
        }
        if (state.selected != null) {
            _state.update {
                it.copy(
                    selected = null,
                    editorText = "",
                    savedText = "",
                    detail = "Closed file · ${it.currentLabel}",
                )
            }
            return
        }
        if (state.navigationTrail.size <= 1) return
        val trail = state.navigationTrail.dropLast(1)
        val parent = trail.last()
        _state.update {
            it.copy(
                currentUri = parent.uri,
                currentLabel = parent.label,
                navigationTrail = trail,
                detail = "Moved up to ${parent.label}.",
            )
        }
        refresh()
    }

    fun requestTrash(entry: WorkspaceEntry) {
        val state = _state.value
        if (state.busy) return
        if (state.lastTrash != null) {
            _state.update { it.copy(detail = "Undo or keep the previous trash move before reviewing another.") }
            return
        }
        if (state.isDirty) {
            _state.update { it.copy(detail = "Save or revert the current draft before moving an entry to trash.") }
            return
        }
        if (entry.displayName == ".anicloud-trash" || state.currentLabel == ".anicloud-trash") {
            _state.update { it.copy(detail = "Recoverable project trash is protected from recursive removal.") }
            return
        }
        _state.update {
            it.copy(
                pendingTrash = entry,
                detail = "Review ${entry.displayName}; nothing moves until MOVE TO TRASH is confirmed.",
            )
        }
    }

    fun cancelTrash() {
        _state.update { it.copy(pendingTrash = null, detail = "Trash request cancelled; no files changed.") }
    }

    fun confirmTrash() {
        val state = _state.value
        val root = state.rootUri?.let(Uri::parse) ?: return
        val parent = state.currentUri?.let(Uri::parse) ?: return
        val pending = state.pendingTrash ?: return
        if (state.busy || state.isDirty) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Moving ${pending.displayName} to recoverable trash…") }
            runCatching { repository.moveToTrash(root, parent, pending) }
                .onSuccess { receipt ->
                    _state.update {
                        it.copy(
                            selected = null,
                            editorText = "",
                            savedText = "",
                            pendingTrash = null,
                            lastTrash = receipt,
                            busy = false,
                            detail = "Moved ${receipt.displayName} to .anicloud-trash · UNDO is available.",
                        )
                    }
                    refresh()
                }
                .onFailure(::showFailure)
        }
    }

    fun undoTrash() {
        val state = _state.value
        val root = state.rootUri?.let(Uri::parse) ?: return
        val receipt = state.lastTrash ?: return
        if (state.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, detail = "Restoring ${receipt.displayName}…") }
            runCatching { repository.restoreFromTrash(root, receipt) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            lastTrash = null,
                            busy = false,
                            detail = "Restored ${receipt.displayName} to its original folder.",
                        )
                    }
                    refresh()
                }
                .onFailure(::showFailure)
        }
    }

    fun keepTrash() {
        repository.clearTrashReceipt()
        _state.update {
            it.copy(
                lastTrash = null,
                detail = "Entry kept in .anicloud-trash; the one-tap undo receipt was cleared.",
            )
        }
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
                    navigationTrail = listOf(WorkspaceLocation(root.toString(), label)),
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
