package dev.anicloud.sovereign.prototype.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anicloud.sovereign.prototype.AnswerMode
import dev.anicloud.sovereign.prototype.AnswerModeSelection
import dev.anicloud.sovereign.prototype.AdaptiveRuntimePolicy
import dev.anicloud.sovereign.prototype.ActiveSessionCheckpoint
import dev.anicloud.sovereign.prototype.AgentMissionStatus
import dev.anicloud.sovereign.prototype.Appearance
import dev.anicloud.sovereign.prototype.BuildConfig
import dev.anicloud.sovereign.prototype.ChatMessage
import dev.anicloud.sovereign.prototype.ChatSpeaker
import dev.anicloud.sovereign.prototype.CockpitState
import dev.anicloud.sovereign.prototype.ComposerMaxVisibleLines
import dev.anicloud.sovereign.prototype.ContextPhysicalTokens
import dev.anicloud.sovereign.prototype.Destination
import dev.anicloud.sovereign.prototype.FoundationLayout
import dev.anicloud.sovereign.prototype.FoundationPreferenceStore
import dev.anicloud.sovereign.prototype.InteractionProfile
import dev.anicloud.sovereign.prototype.InteractionProfilePolicy
import dev.anicloud.sovereign.prototype.InteractionTrait
import dev.anicloud.sovereign.prototype.LayoutPreference
import dev.anicloud.sovereign.prototype.ModelStage
import dev.anicloud.sovereign.prototype.ModelRole
import dev.anicloud.sovereign.prototype.MatrixMemory
import dev.anicloud.sovereign.prototype.MemoryMatrixSnapshot
import dev.anicloud.sovereign.prototype.PendingWorkspaceAction
import dev.anicloud.sovereign.prototype.PendingExecutionAction
import dev.anicloud.sovereign.prototype.RuntimePhase
import dev.anicloud.sovereign.prototype.SovereignViewModel
import dev.anicloud.sovereign.prototype.StoryForgeBenchmarkFolder
import dev.anicloud.sovereign.prototype.StoryForgeBenchmarkPremise
import dev.anicloud.sovereign.prototype.StoryForgeMissionKind
import dev.anicloud.sovereign.prototype.TermuxRunCommandPermission
import dev.anicloud.sovereign.prototype.WorkspaceEntry
import dev.anicloud.sovereign.prototype.WorkspaceState
import dev.anicloud.sovereign.prototype.WorkspaceViewModel
import dev.anicloud.sovereign.prototype.allowsAmbientMotion
import dev.anicloud.sovereign.prototype.isReservedStoryForgeBenchmarkRoot
import dev.anicloud.sovereign.prototype.resolveFoundationLayout
import dev.anicloud.sovereign.prototype.thermalStatusLabel
import java.util.Locale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class CockpitActions(
    val onImportConversationModel: () -> Unit,
    val onImportReasoningModel: () -> Unit,
    val onRetryModel: () -> Unit,
    val onSend: (String, AnswerMode) -> Unit,
    val onGuideMission: (String) -> Unit,
    val onStop: () -> Unit,
    val onApproveWorkspaceAction: (Long) -> Unit,
    val onDenyWorkspaceAction: (Long) -> Unit,
    val onApproveExecutionAction: (Long) -> Unit,
    val onDenyExecutionAction: (Long) -> Unit,
    val onStopExecutionAction: (Long) -> Unit,
    val onRequestTermuxPermission: () -> Unit,
    val onSetTermuxPluginEnabled: (Boolean) -> Unit,
    val onForgetMemory: (Long) -> Unit,
    val onSetMemoryPinned: (Long, Boolean) -> Unit,
)

private data class SlashCommand(
    val command: String,
    val next: String,
    val description: String,
    val acceptsArgument: Boolean = false,
)

private val SlashCommands = listOf(
    SlashCommand("/help", "show the controller command map", "Open command discovery"),
    SlashCommand("/device", "inspect NPU readiness", "Run the local device check-up"),
    SlashCommand("/models", "inspect adaptive routing", "Show E2B/E4B roles and residency"),
    SlashCommand("/capabilities", "show verified controllers", "List what this APK can really do"),
    SlashCommand("/memory", "inspect Matrix state", "Show durable-memory health"),
    SlashCommand("/calc", "enter an arithmetic expression", "Use verified local decimal arithmetic", true),
    SlashCommand("/remember", "enter the durable fact", "Store an explicit safe memory", true),
    SlashCommand(
        "/sessions",
        "list, new, or open <reference>",
        "Create or reopen a conversation without deleting Matrix history",
        true,
    ),
    SlashCommand("/profile", "inspect interaction profile", "Show learned presentation traits"),
    SlashCommand("/why", "explain the last decision", "Show route and context-gate evidence"),
    SlashCommand("/adapt", "enter a trait or on/off", "Tune or pause reversible adaptation", true),
    SlashCommand("/undo-adaptation", "revert the latest revision", "Undo one profile change"),
    SlashCommand("/files", "enter an optional folder", "List the connected workspace", true),
    SlashCommand("/read", "enter a relative file path", "Read a workspace text file", true),
    SlashCommand(
        "/exec",
        "status, workdir, run, test, build, or deps",
        "Plan and explicitly approve a Termux project command",
        true,
    ),
    SlashCommand(
        "/mission",
        "run <folder> :: <objective>, or story <folder> :: <premise>",
        "Start scoped project work or the 120-chapter Story Forge",
        true,
    ),
    SlashCommand("/version", "show build provenance", "Display the installed build and backend"),
)

@Composable
fun AniCloudApp(
    onLock: () -> Unit,
    sovereignViewModel: SovereignViewModel = viewModel(),
) {
    val context = LocalContext.current
    val cockpit by sovereignViewModel.state.collectAsStateWithLifecycle()
    val conversationModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { sovereignViewModel.importModel(it, ModelRole.Conversation) }
    }
    val reasoningModelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { sovereignViewModel.importModel(it, ModelRole.Reasoning) }
    }
    val termuxPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        sovereignViewModel.refreshTermuxBridgeStatus()
    }
    val cockpitActions = CockpitActions(
        onImportConversationModel = {
            conversationModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onImportReasoningModel = {
            reasoningModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onRetryModel = sovereignViewModel::retryModel,
        onSend = sovereignViewModel::send,
        onGuideMission = sovereignViewModel::guideActiveMission,
        onStop = sovereignViewModel::stopGeneration,
        onApproveWorkspaceAction = sovereignViewModel::approveWorkspaceAction,
        onDenyWorkspaceAction = sovereignViewModel::denyWorkspaceAction,
        onApproveExecutionAction = sovereignViewModel::approveExecutionAction,
        onDenyExecutionAction = sovereignViewModel::denyExecutionAction,
        onStopExecutionAction = sovereignViewModel::stopExecutionAction,
        onRequestTermuxPermission = { termuxPermission.launch(TermuxRunCommandPermission) },
        onSetTermuxPluginEnabled = sovereignViewModel::setTermuxPluginEnabled,
        onForgetMemory = sovereignViewModel::forgetMemory,
        onSetMemoryPinned = sovereignViewModel::setMemoryPinned,
    )
    val displayId = context.display?.displayId ?: 0
    val preferenceStore = remember(context.applicationContext) {
        FoundationPreferenceStore(context.applicationContext)
    }
    var appearanceName by rememberSaveable {
        mutableStateOf(preferenceStore.appearance().name)
    }
    var layoutName by rememberSaveable(displayId) {
        mutableStateOf(preferenceStore.layoutForDisplay(displayId).name)
    }
    var defaultModeName by rememberSaveable {
        mutableStateOf(preferenceStore.defaultAnswerMode().name)
    }
    var destinationName by rememberSaveable { mutableStateOf(Destination.Home.name) }

    val appearance = enumOrDefault(appearanceName, Appearance.Obsidian)
    val layoutPreference = enumOrDefault(layoutName, LayoutPreference.Automatic)
    val defaultMode = enumOrDefault(defaultModeName, AnswerMode.Adaptive)
    val destination = enumOrDefault(destinationName, Destination.Home)
    val widthDp = LocalConfiguration.current.screenWidthDp
    val layout = resolveFoundationLayout(widthDp, layoutPreference)

    SovereignTheme(appearance) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LivingVoid(cockpit) {
                if (layout == FoundationLayout.Desktop) {
                    DesktopShell(
                        destination = destination,
                        defaultMode = defaultMode,
                        appearance = appearance,
                        layoutPreference = layoutPreference,
                        cockpit = cockpit,
                        cockpitActions = cockpitActions,
                        onDestination = { destinationName = it.name },
                        onDefaultMode = {
                            defaultModeName = it.name
                            preferenceStore.setDefaultAnswerMode(it)
                        },
                        onAppearance = {
                            appearanceName = it.name
                            preferenceStore.setAppearance(it)
                        },
                        onLayoutPreference = {
                            layoutName = it.name
                            preferenceStore.setLayoutForDisplay(displayId, it)
                        },
                        onLock = onLock,
                    )
                } else {
                    PhoneShell(
                        destination = destination,
                        defaultMode = defaultMode,
                        appearance = appearance,
                        layoutPreference = layoutPreference,
                        cockpit = cockpit,
                        cockpitActions = cockpitActions,
                        onDestination = { destinationName = it.name },
                        onDefaultMode = {
                            defaultModeName = it.name
                            preferenceStore.setDefaultAnswerMode(it)
                        },
                        onAppearance = {
                            appearanceName = it.name
                            preferenceStore.setAppearance(it)
                        },
                        onLayoutPreference = {
                            layoutName = it.name
                            preferenceStore.setLayoutForDisplay(displayId, it)
                        },
                        onLock = onLock,
                    )
                }
            }
        }
    }
}

@Composable
fun LockedSurface(
    isPrompting: Boolean,
    detail: String,
    onAuthenticate: () -> Unit,
) {
    SovereignTheme(Appearance.Obsidian) {
        Surface(
            color = Obsidian,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .padding(32.dp),
                ) {
                    Text("◈", color = HorizonCyan, fontSize = 44.sp)
                    Text(
                        "ANICLOUDAI",
                        color = PrimaryText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Sovereign Core is locked",
                        color = SoftViolet,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        detail,
                        color = MutedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onAuthenticate,
                        enabled = !isPrompting,
                    ) {
                        Text(if (isPrompting) "Waiting for Android…" else "Authenticate")
                    }
                }
            }
        }
    }
}

@Composable
private fun LivingVoid(cockpit: CockpitState, content: @Composable () -> Unit) {
    val motionAllowed = allowsAmbientMotion(cockpit.thermalStatus, cockpit.stage)
    val atmosphere = if (motionAllowed) {
        val transition = rememberInfiniteTransition(label = "living-void")
        val pulse by transition.animateFloat(
            initialValue = 0.060f,
            targetValue = 0.115f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 12_000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "void-breath",
        )
        val drift by transition.animateFloat(
            initialValue = -0.035f,
            targetValue = 0.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 16_000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "void-drift",
        )
        pulse to drift
    } else {
        0.060f to 0f
    }
    val pulse = atmosphere.first
    val drift = atmosphere.second
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Obsidian,
                        Color(0xFF090B18),
                        CognitionViolet.copy(alpha = 0.070f),
                        PulseMagenta.copy(alpha = 0.045f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        HorizonCyan.copy(alpha = pulse),
                        HorizonCyan.copy(alpha = pulse * 0.24f),
                        Color.Transparent,
                    ),
                    center = Offset(
                        size.width * (0.74f + drift),
                        size.height * (0.24f + drift * 0.35f),
                    ),
                    radius = size.minDimension * 0.70f,
                ),
                radius = size.minDimension * 0.70f,
                center = Offset(
                    size.width * (0.74f + drift),
                    size.height * (0.24f + drift * 0.35f),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CognitionViolet.copy(alpha = pulse * 0.92f),
                        CognitionViolet.copy(alpha = pulse * 0.18f),
                        Color.Transparent,
                    ),
                    center = Offset(
                        size.width * (0.20f - drift * 0.45f),
                        size.height * (0.80f - drift),
                    ),
                    radius = size.minDimension * 0.62f,
                ),
                radius = size.minDimension * 0.62f,
                center = Offset(
                    size.width * (0.20f - drift * 0.45f),
                    size.height * (0.80f - drift),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        PulseMagenta.copy(alpha = pulse * 0.72f),
                        CognitionViolet.copy(alpha = pulse * 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(
                        size.width * (0.54f - drift * 0.30f),
                        size.height * (0.64f + drift * 0.50f),
                    ),
                    radius = size.minDimension * 0.52f,
                ),
                radius = size.minDimension * 0.52f,
                center = Offset(
                    size.width * (0.54f - drift * 0.30f),
                    size.height * (0.64f + drift * 0.50f),
                ),
            )
            val grid = 72.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    HorizonCyan.copy(alpha = 0.020f),
                    Offset(x, 0f),
                    Offset(x, size.height),
                    1f,
                )
                x += grid
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(
                    PulseMagenta.copy(alpha = 0.016f),
                    Offset(0f, y),
                    Offset(size.width, y),
                    1f,
                )
                y += grid
            }
        }
        content()
    }
}

@Composable
private fun PhoneShell(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    onLock: () -> Unit,
) {
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    Column(Modifier.fillMaxSize()) {
        TopRail(destination = destination, cockpit = cockpit, onLock = onLock)
        Box(Modifier.weight(1f)) {
            DestinationTransition(
                destination = destination,
                defaultMode = defaultMode,
                appearance = appearance,
                layoutPreference = layoutPreference,
                cockpit = cockpit,
                cockpitActions = cockpitActions,
                onDestination = onDestination,
                onDefaultMode = onDefaultMode,
                onAppearance = onAppearance,
                onLayoutPreference = onLayoutPreference,
                compact = true,
                label = "phone-destination",
            )
        }
        if (!keyboardVisible) {
            NavigationBar(
                containerColor = SmokedDeep.copy(alpha = 0.96f),
                tonalElevation = 0.dp,
            ) {
                Destination.entries.forEach { item ->
                    val accent = destinationAccent(item)
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { onDestination(item) },
                        icon = { DestinationIcon(item, selected = destination == item) },
                        label = { Text(phoneDestinationLabel(item)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = accent,
                            selectedTextColor = accent,
                            indicatorColor = accent.copy(alpha = 0.16f),
                            unselectedIconColor = MutedText,
                            unselectedTextColor = MutedText,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopShell(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    onLock: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavigationPane(
            destination = destination,
            cockpit = cockpit,
            onDestination = onDestination,
            onLock = onLock,
            modifier = Modifier.width(240.dp),
        )
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            DestinationTransition(
                destination = destination,
                defaultMode = defaultMode,
                appearance = appearance,
                layoutPreference = layoutPreference,
                cockpit = cockpit,
                cockpitActions = cockpitActions,
                onDestination = onDestination,
                onDefaultMode = onDefaultMode,
                onAppearance = onAppearance,
                onLayoutPreference = onLayoutPreference,
                compact = false,
                label = "desktop-destination",
            )
        }
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        SystemLens(cockpit = cockpit, modifier = Modifier.width(320.dp))
    }
}

@Composable
private fun DestinationTransition(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    compact: Boolean,
    label: String,
) {
    Crossfade(
        targetState = destination,
        animationSpec = tween(durationMillis = 180),
        label = label,
    ) { visibleDestination ->
        DestinationContent(
            destination = visibleDestination,
            defaultMode = defaultMode,
            appearance = appearance,
            layoutPreference = layoutPreference,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
            onDestination = onDestination,
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
            compact = compact,
        )
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    compact: Boolean,
) {
    when (destination) {
        Destination.Home -> HomeDashboard(
            defaultMode = defaultMode,
            appearance = appearance,
            layoutPreference = layoutPreference,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
            onOpenChat = { onDestination(Destination.Chat) },
            onOpenWorkspace = { onDestination(Destination.Workspace) },
            onOpenSystem = { onDestination(Destination.System) },
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
            compact = compact,
        )

        Destination.Chat -> ChatSurface(
            defaultMode = defaultMode,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
            compact = compact,
        )
        Destination.Memory -> MemoryMatrixSurface(
            cockpit = cockpit,
            actions = cockpitActions,
        )
        Destination.Workspace -> WorkspaceSurface(
            defaultMode = defaultMode,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
        )
        Destination.Agents -> AgentSurface(cockpit = cockpit, actions = cockpitActions)
        Destination.System -> SystemSurface(
            defaultMode = defaultMode,
            appearance = appearance,
            layoutPreference = layoutPreference,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
        )
    }
}

@Composable
private fun TopRail(destination: Destination, cockpit: CockpitState, onLock: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        SmokedDeep,
                        PulseMagenta.copy(alpha = 0.08f),
                        CognitionViolet.copy(alpha = 0.12f),
                        HorizonCyan.copy(alpha = 0.06f),
                        SmokedDeep,
                    ),
                ),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◈", color = HorizonCyan, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("ANICLOUDAI", color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(destination.label.uppercase(), color = MutedText, fontSize = 12.sp)
        }
        Text(
            "● ${cockpit.stage.label.uppercase()}",
            color = modelVitalityColor(cockpit),
            fontSize = 13.sp,
        )
        TextButton(onClick = onLock) { Text("LOCK") }
    }
}

@Composable
private fun NavigationPane(
    destination: Destination,
    cockpit: CockpitState,
    onDestination: (Destination) -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(SmokedDeep, CognitionViolet.copy(alpha = 0.07f), SmokedDeep),
                ),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("◈  ANICLOUDAI", color = HorizonCyan, fontWeight = FontWeight.Bold)
        Text("SOVEREIGN CORE", color = SoftViolet, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Destination.entries.forEach { item ->
            val accent = destinationAccent(item)
            FilterChip(
                selected = destination == item,
                onClick = { onDestination(item) },
                leadingIcon = { DestinationIcon(item, selected = destination == item) },
                label = { Text(item.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MutedText,
                    selectedContainerColor = accent.copy(alpha = 0.16f),
                    selectedLabelColor = accent,
                    selectedLeadingIconColor = accent,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (cockpit.model == null) "FOUNDATION · NO MODEL LOADED" else
                "${cockpit.activeModelRole?.shortLabel ?: "LOCAL"} · " +
                    (cockpit.backend?.name ?: cockpit.stage.label.uppercase()),
            color = modelVitalityColor(cockpit),
            fontSize = 12.sp,
        )
        OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) {
            Text("LOCK NOW")
        }
    }
}

@Composable
private fun HomeDashboard(
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onOpenChat: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSystem: () -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    compact: Boolean,
) {
    var showChecklist by rememberSaveable { mutableStateOf(true) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Good evening.",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Your local intelligence, conversations, and agents—one truthful surface.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { HealthStrip(cockpit) }
        item {
            ResumeCard(onOpenChat = onOpenChat)
        }
        if (showChecklist) {
            item {
                SetupChecklist(
                    cockpit = cockpit,
                    actions = cockpitActions,
                    onOpenChat = onOpenChat,
                    onOpenWorkspace = onOpenWorkspace,
                    onOpenSystem = onOpenSystem,
                    onDismiss = { showChecklist = false },
                )
            }
        }
        item { ModelControlCard(cockpit = cockpit, actions = cockpitActions) }
        item {
            QuickPreferences(
                defaultMode = defaultMode,
                appearance = appearance,
                layoutPreference = layoutPreference,
                onDefaultMode = onDefaultMode,
                onAppearance = onAppearance,
                onLayoutPreference = onLayoutPreference,
            )
        }
        item {
            SectionTitle("Actions")
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                ActionCard("VOICE", "Conversation shortcut", HorizonCyan, "PLANNED")
                ActionCard(
                    "AGENTS",
                    "${cockpit.pendingActions.size} approval${if (cockpit.pendingActions.size == 1) "" else "s"} waiting",
                    CognitionViolet,
                    if (cockpit.pendingActions.isEmpty()) "QUEUE CLEAR" else "REVIEW",
                )
                ActionCard(
                    "MEMORY MATRIX",
                    "${cockpit.memoryMatrix.memoryCount} durable memories",
                    SoftViolet,
                    "ACTIVE",
                )
                ActionCard(
                    "MODELS",
                    cockpit.model?.displayName ?: "Local import ready",
                    modelVitalityColor(cockpit),
                    if (cockpit.model == null) "DISCONNECTED" else "CONNECTED",
                )
            }
        }
        item {
            SectionTitle("Recent conversations")
            Spacer(Modifier.height(8.dp))
            ConversationRow(
                "Current native session",
                "${cockpit.memoryMatrix.messageCount} committed messages · SQLite WAL",
            )
            ConversationRow("Termux history", "Separate until reviewed bridge import")
        }
        item {
            StatusCard(
                title = "Downloads & updates",
                value = "Termux-assisted dogfood",
                detail = "Manual signed-artifact flow · no background check or silent install.",
                accent = WaitingAmber,
            )
        }
        item {
            Text(
                if (compact) "PHONE LAYOUT · DISPLAY-SPECIFIC" else "DESKTOP LAYOUT · FIXED THREE-PANE",
                color = MutedText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun HealthStrip(cockpit: CockpitState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        StatusCard(
            title = "Model",
            value = cockpit.model?.displayName ?: "Disconnected",
            detail = cockpit.detail,
            accent = modelVitalityColor(cockpit),
            modifier = Modifier.width(190.dp),
        )
        StatusCard(
            title = "Device RAM",
            value = cockpit.availableMemoryBytes?.let(::formatBytes) ?: "Unavailable",
            detail = "Android MemAvailable · measured now",
            accent = CognitionViolet,
            modifier = Modifier.width(190.dp),
        )
        StatusCard(
            title = "Memory Matrix",
            value = "${cockpit.memoryMatrix.memoryCount} memories",
            detail = "${cockpit.memoryMatrix.messageCount} messages · " +
                if (cockpit.memoryMatrix.ftsAvailable) "FTS5 ready" else "salience fallback",
            accent = SoftViolet,
            modifier = Modifier.width(190.dp),
        )
        StatusCard(
            title = "Thermal",
            value = thermalStatusLabel(cockpit.thermalStatus),
            detail = "Android thermal pressure · categorical",
            accent = thermalVitalityColor(cockpit.thermalStatus),
            modifier = Modifier.width(190.dp),
        )
    }
}

@Composable
private fun DeviceCheckupCard(cockpit: CockpitState) {
    val verdictColor = when {
        cockpit.npuEligible -> ResonanceMint
        cockpit.conversationModel == null -> MutedText
        else -> WaitingAmber
    }
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(verdictColor, radius = 16.dp, depth = 0.76f, elevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "DEVICE CHECK-UP",
                    color = HorizonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (cockpit.npuEligible) "NPU READY" else "REVIEW",
                    color = verdictColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            CheckupLine("SoC", cockpit.socModel, cockpit.socModel != "Unavailable")
            CheckupLine("Hardware", cockpit.hardware, cockpit.hardware != "Unavailable")
            CheckupLine(
                "Dispatcher",
                if (cockpit.tensorDispatcherPackaged) {
                    "Google Tensor ${BuildConfig.TENSOR_DISPATCH_VERSION} · checksum pinned"
                } else {
                    "Not packaged in this APK"
                },
                cockpit.tensorDispatcherPackaged,
            )
            CheckupLine(
                "E2B fingerprint",
                cockpit.conversationModel?.sha256?.let {
                    val match = it.equals(AdaptiveRuntimePolicy.TensorG5E2BSha256, ignoreCase = true)
                    "${it.take(16)}… · ${if (match) "reviewed match" else "unrecognized"}"
                } ?: "Not installed",
                cockpit.conversationModel?.sha256?.equals(
                    AdaptiveRuntimePolicy.TensorG5E2BSha256,
                    ignoreCase = true,
                ) == true,
            )
            CheckupLine(
                "Memory / thermal",
                "${cockpit.availableMemoryBytes?.let(::formatBytes) ?: "Unavailable"} available · " +
                    thermalStatusLabel(cockpit.thermalStatus),
                !dev.anicloud.sovereign.prototype.isSevereThermalStatus(cockpit.thermalStatus),
            )
            Text(cockpit.npuStatus, color = verdictColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CheckupLine(label: String, value: String, passed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(if (passed) "●" else "○", color = if (passed) ResonanceMint else WaitingAmber)
        Text(label, color = MutedText, fontSize = 12.sp, modifier = Modifier.width(112.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ResumeCard(onOpenChat: () -> Unit) {
    Card(
        onClick = onOpenChat,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, HorizonCyan.copy(alpha = 0.55f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("RESUME LAST CONVERSATION", color = HorizonCyan, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Current native session", fontWeight = FontWeight.Bold)
                Text(
                    "App-private SQLite continuity · bounded recall on every turn",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            Text("OPEN  →", color = ResonanceMint, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupChecklist(
    cockpit: CockpitState,
    actions: CockpitActions,
    onOpenChat: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSystem: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reasoningReady = cockpit.reasoningModel != null
    OutlinedCard(border = BorderStroke(1.dp, CognitionViolet.copy(alpha = 0.55f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FIRST-USE FLIGHT CHECKLIST", color = SoftViolet, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("DISMISS") }
            }
            Text(
                "WHAT ANICLOUDAI ASKS · WHY IT ASKS · WHAT STILL WORKS IF YOU DECLINE",
                color = HorizonCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            FirstUseStep(
                marker = "01 · READY",
                title = "Android credential accepted",
                detail = "Fingerprint, PIN, or password unlocks the local cockpit; AniCloudAI stores no separate password.",
                accent = ResonanceMint,
            )
            FirstUseStep(
                marker = "02 · REVIEW",
                title = "Notification permission",
                detail = "Allows visible background generation progress and STOP. Declining must not block local chat.",
                accent = WaitingAmber,
            )
            FirstUseStep(
                marker = if (reasoningReady) "03 · READY" else "03 · REQUIRED",
                title = if (reasoningReady) {
                    "E4B reasoning model fingerprinted"
                } else {
                    "Choose the reviewed E4B .litertlm file"
                },
                detail = if (reasoningReady) {
                    "${cockpit.reasoningModel?.sha256?.take(16)}… · copied into app-private no-backup storage."
                } else {
                    "The Android picker grants only that file; keep the app foregrounded through its first import."
                },
                accent = if (reasoningReady) ResonanceMint else PulseMagenta,
            )
            FirstUseStep(
                marker = "04 · USER GRANT",
                title = "Connect one disposable project folder",
                detail = "The Android tree picker grants only that tree, not device-wide storage. Cancel is safe and retryable.",
                accent = HorizonCyan,
            )
            FirstUseStep(
                marker = "05 · PROVE",
                title = "Run the ordinary-use gates before autonomy",
                detail = "Check three-turn recall, sentence selection, COPY ALL, /calc, STOP, restart, and folder Up/Root.",
                accent = SoftViolet,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Button(onClick = actions.onImportReasoningModel) {
                    Text(if (reasoningReady) "REVIEW / REPLACE E4B" else "IMPORT E4B")
                }
                OutlinedButton(onClick = onOpenChat, enabled = reasoningReady) { Text("OPEN CHAT") }
                OutlinedButton(onClick = onOpenWorkspace) { Text("CONNECT PROJECT") }
                OutlinedButton(onClick = onOpenSystem) { Text("BOUNDARIES") }
            }
            Text(
                "OPTIONAL LATER · E2B/NPU and the Termux developer plugin are separate acceptance lanes. " +
                    "This build has no INTERNET permission and must not ask for an API token.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FirstUseStep(
    marker: String,
    title: String,
    detail: String,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(marker, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun ModelControlCard(cockpit: CockpitState, actions: CockpitActions) {
    val accent = modelVitalityColor(cockpit)
    OutlinedCard(border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("LOCAL MODEL", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        cockpit.model?.displayName ?: "Connect Sovereign Core",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(cockpit.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Text(cockpit.stage.label.uppercase(), color = accent, fontSize = 12.sp)
            }

            if (cockpit.stage == ModelStage.Importing) {
                val total = cockpit.importBytesTotal
                if (total != null && total > 0) {
                    val progress =
                        (cockpit.importBytesCopied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        color = HorizonCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(
                        color = HorizonCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (total == null) formatBytes(cockpit.importBytesCopied) else
                        "${formatBytes(cockpit.importBytesCopied)} / ${formatBytes(total)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }

            ModelSlotSummary(
                label = "E2B · CONVERSATION / MEMORY",
                model = cockpit.conversationModel,
                status = cockpit.npuStatus,
                active = cockpit.activeModelRole == ModelRole.Conversation,
            )
            ModelSlotSummary(
                label = "E4B · REASONING / CODING",
                model = cockpit.reasoningModel,
                status = if (cockpit.activeModelRole == ModelRole.Reasoning) {
                    "Active on ${cockpit.backend?.name ?: "initializing"}"
                } else {
                    "GPU first · measured CPU fallback"
                },
                active = cockpit.activeModelRole == ModelRole.Reasoning,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OutlinedButton(
                    onClick = actions.onImportConversationModel,
                    enabled = cockpit.stage !in setOf(
                        ModelStage.Importing,
                        ModelStage.Initializing,
                        ModelStage.Generating,
                        ModelStage.Recovering,
                    ) && (cockpit.thermalStatus == null || cockpit.thermalStatus < 3),
                ) {
                    Text(if (cockpit.conversationModel == null) "IMPORT E2B · TENSOR G5" else "REPLACE E2B")
                }
                OutlinedButton(
                    onClick = actions.onImportReasoningModel,
                    enabled = cockpit.stage !in setOf(
                        ModelStage.Importing,
                        ModelStage.Initializing,
                        ModelStage.Generating,
                        ModelStage.Recovering,
                    ) && (cockpit.thermalStatus == null || cockpit.thermalStatus < 3),
                ) {
                    Text(if (cockpit.reasoningModel == null) "IMPORT E4B" else "REPLACE E4B")
                }
                if (cockpit.stage == ModelStage.Error && cockpit.model != null) {
                    Button(onClick = actions.onRetryModel) { Text("RETRY LOAD") }
                }
            }
            Text(
                "Each picker grants one file only. AniCloudAI copies it into app-private, " +
                    "no-backup storage. E2B must match the reviewed Tensor G5 fingerprint and " +
                    "never silently falls back to GPU.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ModelSlotSummary(
    label: String,
    model: dev.anicloud.sovereign.prototype.ImportedModel?,
    status: String,
    active: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(
                if (active) ResonanceMint else CognitionViolet,
                radius = 12.dp,
                depth = 0.62f,
                elevation = 1.dp,
            )
            .padding(12.dp),
    ) {
        Text(
            label + if (active) " · RESIDENT" else "",
            color = if (active) ResonanceMint else SoftViolet,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            model?.displayName ?: "Not installed",
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (model != null) {
            Text(
                "${formatBytes(model.byteSize)} · SHA-256 ${model.sha256.take(16)}…",
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            status,
            color = if (active) ResonanceMint else MutedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun QuickPreferences(
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
) {
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("QUICK CONTROLS", color = HorizonCyan, fontSize = 13.sp)
            ChoiceRow("Default answer", AnswerMode.entries, defaultMode, { it.label }, onDefaultMode)
            ChoiceRow("Appearance", Appearance.entries, appearance, { it.label }, onAppearance)
            ChoiceRow("This display", LayoutPreference.entries, layoutPreference, { it.label }, onLayoutPreference)
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(label(value)) },
                )
            }
        }
    }
}

@Composable
private fun ChatSurface(
    defaultMode: AnswerMode,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    compact: Boolean,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var selection by remember(defaultMode) {
        mutableStateOf(AnswerModeSelection(defaultMode = defaultMode))
    }
    val threadState = rememberLazyListState()
    val leadingItems = if (cockpit.model == null || cockpit.stage == ModelStage.Error) 1 else 0
    val tailIndex = leadingItems + cockpit.messages.size +
        (if (cockpit.streamText.isNotBlank()) 1 else 0)

    AutoFollowTail(threadState, tailIndex, cockpit.streamText.length / 128)

    Column(Modifier.fillMaxSize()) {
        TruthThread(
            phase = runtimePhase(cockpit.stage),
            detail = cockpit.detail,
            active = cockpit.isGenerating,
            compact = compact,
        )
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = threadState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = if (compact) 8.dp else 12.dp,
                    end = 16.dp,
                    bottom = if (compact) 12.dp else 72.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (cockpit.model == null || cockpit.stage == ModelStage.Error) {
                    item { ModelControlCard(cockpit = cockpit, actions = cockpitActions) }
                }
                items(cockpit.messages, key = { it.id }) { message -> MessageBlock(message) }
                if (cockpit.streamText.isNotBlank()) {
                    item { StreamingBlock(cockpit.streamText, active = cockpit.isGenerating) }
                }
                item(key = "chat-transcript-tail") { Spacer(Modifier.height(1.dp)) }
            }
            LatestTranscriptButton(
                listState = threadState,
                tailIndex = tailIndex,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }
        Composer(
            draft = draft,
            selection = selection,
            canSend = cockpit.canSend,
            isGenerating = cockpit.isGenerating,
            blockedMessage = when {
                cockpit.thermalStatus != null && cockpit.thermalStatus >= 3 ->
                    "INFERENCE PAUSED BY ANDROID THERMAL SAFETY"
                cockpit.model == null ->
                    "IMPORT AND INITIALIZE A .LITERTLM MODEL TO ENABLE SEND"
                else -> cockpit.detail.uppercase()
            },
            onDraft = { draft = it },
            onMode = {
                selection = selection.copy(
                    oneResponseOverride = if (it == selection.defaultMode) null else it,
                )
            },
            onSend = {
                val submitted = draft
                if (submitted.isNotBlank() && cockpit.canSend) {
                    val mode = selection.modeForNextResponse()
                    draft = ""
                    selection = selection.afterResponse()
                    cockpitActions.onSend(submitted, mode)
                }
            },
            onStop = cockpitActions.onStop,
            compact = compact,
        )
    }
}

/** Follow live output only while the reader remains at the end of the transcript. */
@Composable
private fun AutoFollowTail(
    listState: androidx.compose.foundation.lazy.LazyListState,
    tailIndex: Int,
    streamRevision: Int,
) {
    var initialRevealPending by remember(listState) { mutableStateOf(true) }
    var followTail by remember(listState) { mutableStateOf(true) }

    LaunchedEffect(listState, tailIndex) {
        if (initialRevealPending && tailIndex >= 0) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > tailIndex }
            listState.scrollToItem(tailIndex)
            initialRevealPending = false
            followTail = true
        }
    }
    LaunchedEffect(listState, initialRevealPending) {
        if (initialRevealPending) return@LaunchedEffect
        snapshotFlow {
            listState.isScrollInProgress to !listState.canScrollForward
        }.collect { (scrolling, atEnd) ->
            if (scrolling) followTail = atEnd
            else if (atEnd) followTail = true
        }
    }
    LaunchedEffect(tailIndex, streamRevision, initialRevealPending) {
        if (!initialRevealPending && followTail && tailIndex >= 0) {
            listState.scrollToItem(tailIndex)
        }
    }
}

@Composable
private fun LatestTranscriptButton(
    listState: androidx.compose.foundation.lazy.LazyListState,
    tailIndex: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    if (tailIndex >= 0 && listState.canScrollForward) {
        Surface(
            onClick = {
                scope.launch {
                    listState.scrollToItem(tailIndex)
                }
            },
            color = SmokedDeep.copy(alpha = 0.94f),
            contentColor = HorizonCyan,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, HorizonCyan),
            shadowElevation = 8.dp,
            modifier = modifier
                .size(46.dp)
                .semantics { contentDescription = "Jump to latest message" },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("↓", color = HorizonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TruthThread(
    phase: RuntimePhase,
    detail: String,
    active: Boolean,
    compact: Boolean = false,
) {
    val color = when (phase) {
        RuntimePhase.Ready -> ResonanceMint
        RuntimePhase.Recovering, RuntimePhase.Degraded -> InterventionCoral
        RuntimePhase.Grounding, RuntimePhase.Verifying -> HorizonCyan
        else -> CognitionViolet
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(color, radius = 12.dp, depth = 0.64f, elevation = 2.dp)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 10.dp),
    ) {
        Text(
            "● ${phase.label.uppercase()}",
            color = color,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (compact) 11.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) Text("CANCELLABLE", color = WaitingAmber, fontSize = 12.sp)
    }
}

@Composable
private fun MessageBlock(message: ChatMessage) {
    val isUser = message.speaker == ChatSpeaker.User
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id, message.text) { mutableStateOf(false) }
    val accent = when (message.speaker) {
        ChatSpeaker.User -> PulseMagenta
        ChatSpeaker.Core -> HorizonCyan
        ChatSpeaker.System -> WaitingAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(
                accent = accent,
                radius = 16.dp,
                depth = if (isUser) 0.69f else 0.80f,
                elevation = 2.dp,
            )
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (message.speaker) {
                    ChatSpeaker.User -> "YOU"
                    ChatSpeaker.Core -> "SOVEREIGN CORE"
                    ChatSpeaker.System -> "SYSTEM LENS"
                },
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(message.text))
                    copied = true
                },
            ) {
                Text(if (copied) "COPIED" else "COPY ALL", color = accent, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            SovereignMarkdown(message.text)
        }
    }
}

@Composable
private fun StreamingBlock(text: String, active: Boolean = true) {
    val visible = if (active) {
        "$text▌"
    } else {
        "$text\n\n[INTERRUPTED DRAFT · NOT A VERIFIED FINAL ANSWER]"
    }
    MessageBlock(ChatMessage(-1, ChatSpeaker.Core, visible))
}

@Composable
private fun Composer(
    draft: String,
    selection: AnswerModeSelection,
    canSend: Boolean,
    isGenerating: Boolean,
    blockedMessage: String,
    onDraft: (String) -> Unit,
    onMode: (AnswerMode) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    compact: Boolean,
) {
    val modeAccent = answerModeAccent(selection.modeForNextResponse())
    val density = LocalDensity.current
    val keyboardVisible = compact && WindowInsets.ime.getBottom(density) > 0
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp)
            .sovereignGlass(
                modeAccent,
                radius = if (compact) 16.dp else 18.dp,
                depth = 0.88f,
                elevation = 10.dp,
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            modifier = Modifier.padding(if (compact) 8.dp else 12.dp),
        ) {
            if (!keyboardVisible) {
                if (compact) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AnswerMode.entries.forEach { mode ->
                            FluorescentChip(
                                selected = selection.modeForNextResponse() == mode,
                                onClick = { onMode(mode) },
                                label = when (mode) {
                                    AnswerMode.Performance -> "FAST · 1K"
                                    AnswerMode.Adaptive -> "AUTO · 1.5K"
                                    AnswerMode.Quality -> "DEEP · 2K"
                                },
                                accent = answerModeAccent(mode),
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Text("NEXT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        AnswerMode.entries.forEach { mode ->
                            FluorescentChip(
                                selected = selection.modeForNextResponse() == mode,
                                onClick = { onMode(mode) },
                                label = when (mode) {
                                    AnswerMode.Performance -> "Performance · 1K"
                                    AnswerMode.Adaptive -> "Adaptive · 1.5K"
                                    AnswerMode.Quality -> "Quality · 2K"
                                },
                                accent = answerModeAccent(mode),
                            )
                        }
                    }
                }
            }
            Text(
                if (compact) {
                    "${selection.modeForNextResponse().label.uppercase()} · " +
                        answerModeTokenBudget(selection.modeForNextResponse())
                } else {
                    "${selection.modeForNextResponse().description.uppercase()} · " +
                        answerModeTokenBudget(selection.modeForNextResponse())
                },
                color = modeAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 9.sp else 10.sp,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    "PER-CALL OUTPUT, NOT A DOCUMENT LIMIT · LONG WORK CONTINUES THROUGH FRESH " +
                        "CONTROLLER CALLS AND MATRIX CHECKPOINTS",
                    color = MutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            if (draft.startsWith("/") && !draft.contains('\n')) {
                SlashCommandPalette(
                    query = draft.substringBefore(' '),
                    onSelect = { selected ->
                        onDraft(selected.command + if (selected.acceptsArgument) " " else "")
                    },
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message Sovereign Core") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = if (compact) 4 else ComposerMaxVisibleLines,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
                    modifier = Modifier.weight(1f),
                )
                if (isGenerating) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = InterventionCoral),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("STOP", color = Obsidian, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && canSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = modeAccent,
                            contentColor = Obsidian,
                        ),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("SEND", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                if (canSend || isGenerating) {
                    if (compact) "RETURN = NEW LINE · TAP SEND TO SUBMIT" else
                        "RETURN NEWLINE · VISIBLE SEND ONLY · 1–7 LINES"
                } else {
                    blockedMessage
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 9.sp else 11.sp,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FluorescentChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    accent: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = if (compact) 9.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = MutedText,
            selectedContainerColor = accent.copy(alpha = 0.18f),
            selectedLabelColor = accent,
        ),
        modifier = modifier.heightIn(min = if (compact) 40.dp else 48.dp),
    )
}

@Composable
private fun SlashCommandPalette(
    query: String,
    onSelect: (SlashCommand) -> Unit,
) {
    val normalized = query.lowercase(Locale.ROOT)
    val matches = SlashCommands.filter { it.command.startsWith(normalized) }.take(6)
    if (matches.isEmpty()) return
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(CognitionViolet, radius = 12.dp, depth = 0.92f, elevation = 6.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "CONTROLLER COMMANDS · TAP TO INSERT",
                color = SoftViolet,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            matches.forEach { item ->
                TextButton(onClick = { onSelect(item) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "${item.command}  →  ${item.next}",
                            color = HorizonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(item.description, color = MutedText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryMatrixSurface(cockpit: CockpitState, actions: CockpitActions) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            PageHeading(
                "Memory Matrix",
                "App-private continuity with explicit provenance and bounded recall",
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                StatusCard(
                    "Committed history",
                    "${cockpit.memoryMatrix.messageCount} messages",
                    "SQLite WAL · restored after process death",
                    HorizonCyan,
                    Modifier.width(220.dp),
                )
                StatusCard(
                    "Durable memory",
                    "${cockpit.memoryMatrix.memoryCount} active",
                    if (cockpit.memoryMatrix.ftsAvailable) "FTS5 recall ready" else "Salience fallback active",
                    SoftViolet,
                    Modifier.width(220.dp),
                )
                StatusCard(
                    "Local footprint",
                    formatBytes(cockpit.memoryMatrix.databaseBytes),
                    "App-private database · never shared live",
                    ResonanceMint,
                    Modifier.width(220.dp),
                )
            }
        }
        item {
            ContractCard(
                "Memory controls",
                listOf(
                    "Use /remember <fact> for an exact durable memory",
                    "Model suggestions require an exact quote from your current message",
                    "Credentials and sensitive inferred traits are rejected",
                    "Pin or forget any active memory below",
                ),
            )
        }
        item { ActiveSessionCheckpointCard(cockpit.memoryMatrix.activeSessionCheckpoint) }
        item {
            InteractionProfileCard(
                profile = cockpit.memoryMatrix.interactionProfile,
                contextDecision = cockpit.lastContextDecision,
            )
        }
        if (cockpit.memoryMatrix.recentMemories.isEmpty()) {
            item {
                StatusCard(
                    "MATRIX READY",
                    "No durable memories yet",
                    "Conversation history is already committed. Add a fact with /remember when you want durable recall.",
                    ResonanceMint,
                )
            }
        } else {
            items(cockpit.memoryMatrix.recentMemories, key = { it.id }) { memory ->
                MemoryCard(memory = memory, actions = actions)
            }
        }
    }
}

@Composable
private fun ActiveSessionCheckpointCard(checkpoint: ActiveSessionCheckpoint) {
    OutlinedCard(
        border = BorderStroke(1.dp, PulseMagenta.copy(alpha = 0.58f)),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(PulseMagenta, radius = 16.dp, depth = 0.72f, elevation = 3.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ACTIVE SESSION CAPSULE", color = PulseMagenta, fontWeight = FontWeight.Bold)
                    Text(
                        "Extractive · user-sourced · bounded · never tool authority",
                        color = MutedText,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    if (checkpoint.populated) "ACTIVE" else "EMPTY",
                    color = if (checkpoint.populated) ResonanceMint else WaitingAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            if (!checkpoint.populated) {
                Text(
                    "Explicit goals, preferences, project facts, decisions, and unresolved loops will appear here after they are captured verbatim.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            } else {
                checkpoint.currentTask.takeIf(String::isNotBlank)?.let { task ->
                    Text("CURRENT TASK", color = HorizonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    SelectionContainer { Text(task, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp) }
                }
                if (checkpoint.openLoops.isNotEmpty()) {
                    Text(
                        "OPEN LOOPS · ${checkpoint.openLoops.size}",
                        color = WaitingAmber,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    SelectionContainer {
                        Text(
                            checkpoint.openLoops.takeLast(3).joinToString("\n") { "• $it" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (checkpoint.decisions.isNotEmpty()) {
                    Text(
                        "DECISIONS · ${checkpoint.decisions.size}",
                        color = SoftViolet,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    SelectionContainer {
                        Text(
                            checkpoint.decisions.takeLast(3).joinToString("\n") { "• $it" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (checkpoint.summary.isNotBlank()) {
                    Text("LATEST CAPTURED SIGNALS", color = HorizonCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    SelectionContainer {
                        Text(
                            checkpoint.summary.lines().takeLast(6).joinToString("\n"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "Forget a captured Matrix memory to remove its exact session-capsule copy too.",
                    color = ResonanceMint,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun InteractionProfileCard(
    profile: InteractionProfile,
    contextDecision: dev.anicloud.sovereign.prototype.ContextDecision?,
) {
    OutlinedCard(
        border = BorderStroke(1.dp, HorizonCyan.copy(alpha = 0.52f)),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(HorizonCyan, radius = 16.dp, depth = 0.72f, elevation = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("INTERACTION PROFILE", color = HorizonCyan, fontWeight = FontWeight.Bold)
                    Text(
                        "Presentation learning only · identity and permissions remain immutable",
                        color = MutedText,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "R${profile.revision} · ${if (profile.automaticAdaptation) "AUTO" else "MANUAL"}",
                    color = if (profile.automaticAdaptation) ResonanceMint else WaitingAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            InteractionTrait.entries.forEach { trait ->
                InteractionTraitMeter(trait, profile.valueOf(trait))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                "LAST CHANGE · ${profile.lastReason}",
                color = SoftViolet,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                contextDecision?.let {
                    "CONTEXT GATE · ${it.scope.label.uppercase()} · ${it.relevanceScore}/${it.threshold} · ${it.reason}"
                } ?: "CONTEXT GATE · waiting for the first generated turn",
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                "Use /profile, /why, /adapt <trait> <value>, or /undo-adaptation.",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun InteractionTraitMeter(trait: InteractionTrait, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(
                trait.label.uppercase(),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${InteractionProfilePolicy.percent(value)}%",
                color = SoftViolet,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0.0, 1.0).toFloat())
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(listOf(HorizonCyan, CognitionViolet)),
                        RoundedCornerShape(999.dp),
                    ),
            )
        }
    }
}

@Composable
private fun MemoryCard(memory: MatrixMemory, actions: CockpitActions) {
    OutlinedCard(
        border = BorderStroke(
            1.dp,
            if (memory.pinned) ResonanceMint.copy(alpha = 0.75f) else SoftViolet.copy(alpha = 0.38f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (memory.pinned) "PINNED · " else ""}${memory.kind.uppercase()} · #${memory.id}",
                    color = if (memory.pinned) ResonanceMint else SoftViolet,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(memory.confidence * 100).toInt()}% confidence",
                    color = MutedText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(memory.value, color = MaterialTheme.colorScheme.onSurface, lineHeight = 21.sp)
            Text(
                "KEY ${memory.key} · UPDATED ${memory.updatedAt}",
                color = MutedText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onSetMemoryPinned(memory.id, !memory.pinned) }) {
                    Text(if (memory.pinned) "UNPIN" else "PIN")
                }
                TextButton(onClick = { actions.onForgetMemory(memory.id) }) {
                    Text("FORGET", color = InterventionCoral)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSurface(
    defaultMode: AnswerMode,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    workspaceViewModel: WorkspaceViewModel = viewModel(),
) {
    val state by workspaceViewModel.state.collectAsStateWithLifecycle()
    var workSessionOpen by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(workspaceViewModel::attachRoot)
    }
    BackHandler(
        enabled = !workSessionOpen && (state.newFolderOpen || state.canNavigateUp) &&
            !state.busy && state.pendingTrash == null,
    ) {
        if (state.newFolderOpen) workspaceViewModel.cancelNewFolder() else workspaceViewModel.navigateUp()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        PageHeading("Workspace Lens", "A user-granted project tree with controller-mediated co-creation")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Button(onClick = { picker.launch(null) }, enabled = !state.busy) {
                Text(if (state.rootUri == null) "CONNECT PROJECT" else "CHANGE PROJECT")
            }
            OutlinedButton(
                onClick = workspaceViewModel::navigateUp,
                enabled = state.canNavigateUp && !state.busy && state.pendingTrash == null,
            ) { Text("UP") }
            OutlinedButton(
                onClick = workspaceViewModel::returnToRoot,
                enabled = state.rootUri != null && state.currentUri != state.rootUri && !state.busy &&
                    state.pendingTrash == null,
            ) { Text("ROOT") }
            OutlinedButton(
                onClick = workspaceViewModel::refresh,
                enabled = state.rootUri != null && !state.busy,
            ) { Text("REFRESH") }
            OutlinedButton(
                onClick = workspaceViewModel::requestNewFolder,
                enabled = state.rootUri != null && !workSessionOpen && !state.busy &&
                    !state.newFolderOpen && state.pendingTrash == null && state.lastTrash == null,
            ) { Text("NEW FOLDER") }
            FluorescentChip(
                selected = !workSessionOpen,
                onClick = { workSessionOpen = false },
                label = "EDITOR",
                accent = HorizonCyan,
            )
            FluorescentChip(
                selected = workSessionOpen,
                onClick = { workSessionOpen = true },
                label = "WORK SESSION",
                accent = PulseMagenta,
            )
            Text(
                state.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (state.rootUri != null) {
            Text(
                "CHAT WRITES REVIEW EACH · WORK SESSION USES ONE SCOPED GRANT · UI TRASH IS RECOVERABLE",
                color = ResonanceMint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "PROJECT TREE · ${state.breadcrumb.ifBlank { state.rootLabel }}",
                color = HorizonCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.newFolderOpen) {
            OutlinedCard(
                border = BorderStroke(1.dp, HorizonCyan.copy(alpha = 0.72f)),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CREATE FOLDER", color = HorizonCyan, fontWeight = FontWeight.Bold)
                    Text(
                        "Location: ${state.breadcrumb.ifBlank { state.rootLabel }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedTextField(
                        value = state.newFolderName,
                        onValueChange = workspaceViewModel::updateNewFolderName,
                        label = { Text("FOLDER NAME") },
                        placeholder = { Text("new-component") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = workspaceViewModel::confirmNewFolder,
                            enabled = state.newFolderName.isNotBlank() && !state.busy,
                        ) { Text("CREATE", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = workspaceViewModel::cancelNewFolder,
                            enabled = !state.busy,
                        ) { Text("CANCEL") }
                    }
                    Text(
                        "Uses the project’s existing Android folder permission; no additional device-wide permission is requested.",
                        color = ResonanceMint,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        state.pendingTrash?.let { pending ->
            OutlinedCard(
                border = BorderStroke(1.dp, InterventionCoral.copy(alpha = 0.72f)),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MOVE TO RECOVERABLE TRASH?", color = InterventionCoral, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (pending.isDirectory) "Folder" else "File"}: ${pending.displayName}",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "This moves the entry into .anicloud-trash inside the granted project. " +
                            "It does not permanently delete data.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = workspaceViewModel::confirmTrash,
                            colors = ButtonDefaults.buttonColors(containerColor = InterventionCoral),
                        ) { Text("MOVE TO TRASH", color = Obsidian, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = workspaceViewModel::cancelTrash) { Text("CANCEL") }
                    }
                }
            }
        } ?: state.lastTrash?.let { receipt ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "${receipt.displayName} is in recoverable trash.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = workspaceViewModel::undoTrash, enabled = !state.busy) {
                        Text("UNDO")
                    }
                    TextButton(onClick = workspaceViewModel::keepTrash, enabled = !state.busy) {
                        Text("KEEP IN TRASH", color = InterventionCoral)
                    }
                }
            }
        }

        if (workSessionOpen) {
            WorkSessionSurface(
                defaultMode = defaultMode,
                cockpit = cockpit,
                actions = cockpitActions,
                workspaceConnected = state.rootUri != null,
                modifier = Modifier.weight(1f),
            )
        } else if (state.rootUri == null) {
            OutlinedCard(
                border = BorderStroke(1.dp, CognitionViolet.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("THE OPEN FORGE", color = SoftViolet, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose a project folder. AniCloudAI receives persistent read/write access " +
                            "inside that tree only; Android keeps every other location sealed.",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Every SAVE retains the previous bytes first. Removing an entry uses an explicit, " +
                            "recoverable move to project-local trash.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                if (wide) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        WorkspaceBrowser(
                            state,
                            workspaceViewModel::open,
                            workspaceViewModel::requestTrash,
                            Modifier.width(300.dp),
                        )
                        WorkspaceEditor(state, workspaceViewModel, Modifier.weight(1f))
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        WorkspaceBrowser(
                            state,
                            workspaceViewModel::open,
                            workspaceViewModel::requestTrash,
                            Modifier.weight(if (state.selected == null) 0.58f else 0.30f),
                        )
                        WorkspaceEditor(
                            state,
                            workspaceViewModel,
                            Modifier.weight(if (state.selected == null) 0.42f else 0.70f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkSessionSurface(
    defaultMode: AnswerMode,
    cockpit: CockpitState,
    actions: CockpitActions,
    workspaceConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    var rootPath by rememberSaveable { mutableStateOf("") }
    var objective by rememberSaveable { mutableStateOf("") }
    var guidance by rememberSaveable { mutableStateOf("") }
    var modeName by rememberSaveable(defaultMode.name) { mutableStateOf(defaultMode.name) }
    val selectedMode = enumOrDefault(modeName, defaultMode)
    val mission = cockpit.activeMission
    val missionMessages = cockpit.messages.filter {
        it.source.startsWith("mission") &&
            (mission == null || it.id >= mission.startedMessageId)
    }
    val threadState = rememberLazyListState()
    val streamVisible = mission?.active == true && cockpit.streamText.isNotBlank()
    val threadTail = if (missionMessages.isEmpty() && !streamVisible) {
        -1
    } else {
        missionMessages.size + if (streamVisible) 1 else 0
    }
    val updateRootPath: (String) -> Unit = { next ->
        val presetWasLoaded = isReservedStoryForgeBenchmarkRoot(rootPath) &&
            objective.trim() == StoryForgeBenchmarkPremise
        rootPath = next
        if (presetWasLoaded && !isReservedStoryForgeBenchmarkRoot(next)) objective = ""
    }
    val updateObjective: (String) -> Unit = { next ->
        val presetWasLoaded = isReservedStoryForgeBenchmarkRoot(rootPath) &&
            objective.trim() == StoryForgeBenchmarkPremise
        objective = next
        if (presetWasLoaded && next.trim() != StoryForgeBenchmarkPremise) rootPath = ""
    }

    AutoFollowTail(threadState, threadTail, cockpit.streamText.length / 128)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                WorkSessionControlPanel(
                    rootPath = rootPath,
                    objective = objective,
                    guidance = guidance,
                    selectedMode = selectedMode,
                    cockpit = cockpit,
                    workspaceConnected = workspaceConnected,
                    onRootPath = updateRootPath,
                    onObjective = updateObjective,
                    onGuidance = { guidance = it },
                    onMode = { modeName = it.name },
                    onLoadStoryBenchmark = {
                        rootPath = StoryForgeBenchmarkFolder
                        objective = StoryForgeBenchmarkPremise
                        modeName = AnswerMode.Quality.name
                    },
                    onStart = {
                        actions.onSend(
                            "/mission run ${rootPath.trim()} :: ${objective.trim()}",
                            selectedMode,
                        )
                    },
                    onStoryStart = {
                        actions.onSend(
                            "/mission story ${rootPath.trim()} :: ${objective.trim()}",
                            selectedMode,
                        )
                    },
                    onGuide = {
                        val submitted = guidance.trim()
                        if (submitted.isNotEmpty()) {
                            guidance = ""
                            actions.onGuideMission(submitted)
                        }
                    },
                    onCommand = { command -> actions.onSend(command, selectedMode) },
                    onStop = actions.onStop,
                    modifier = Modifier.width(390.dp).fillMaxHeight(),
                )
                WorkSessionThread(
                    missionMessages = missionMessages,
                    streamText = if (streamVisible) cockpit.streamText else "",
                    streamActive = cockpit.isGenerating,
                    listState = threadState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                WorkSessionControlPanel(
                    rootPath = rootPath,
                    objective = objective,
                    guidance = guidance,
                    selectedMode = selectedMode,
                    cockpit = cockpit,
                    workspaceConnected = workspaceConnected,
                    onRootPath = updateRootPath,
                    onObjective = updateObjective,
                    onGuidance = { guidance = it },
                    onMode = { modeName = it.name },
                    onLoadStoryBenchmark = {
                        rootPath = StoryForgeBenchmarkFolder
                        objective = StoryForgeBenchmarkPremise
                        modeName = AnswerMode.Quality.name
                    },
                    onStart = {
                        actions.onSend(
                            "/mission run ${rootPath.trim()} :: ${objective.trim()}",
                            selectedMode,
                        )
                    },
                    onStoryStart = {
                        actions.onSend(
                            "/mission story ${rootPath.trim()} :: ${objective.trim()}",
                            selectedMode,
                        )
                    },
                    onGuide = {
                        val submitted = guidance.trim()
                        if (submitted.isNotEmpty()) {
                            guidance = ""
                            actions.onGuideMission(submitted)
                        }
                    },
                    onCommand = { command -> actions.onSend(command, selectedMode) },
                    onStop = actions.onStop,
                    modifier = Modifier
                        .weight(if (mission?.active == true) 0.40f else 0.68f)
                        .fillMaxWidth(),
                )
                WorkSessionThread(
                    missionMessages = missionMessages,
                    streamText = if (streamVisible) cockpit.streamText else "",
                    streamActive = cockpit.isGenerating,
                    listState = threadState,
                    modifier = Modifier
                        .weight(if (mission?.active == true) 0.60f else 0.32f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun WorkSessionControlPanel(
    rootPath: String,
    objective: String,
    guidance: String,
    selectedMode: AnswerMode,
    cockpit: CockpitState,
    workspaceConnected: Boolean,
    onRootPath: (String) -> Unit,
    onObjective: (String) -> Unit,
    onGuidance: (String) -> Unit,
    onMode: (AnswerMode) -> Unit,
    onLoadStoryBenchmark: () -> Unit,
    onStart: () -> Unit,
    onStoryStart: () -> Unit,
    onGuide: () -> Unit,
    onCommand: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mission = cockpit.activeMission
    val active = mission?.active == true
    val statusColor = when (mission?.status) {
        AgentMissionStatus.Running -> PulseMagenta
        AgentMissionStatus.Paused -> WaitingAmber
        AgentMissionStatus.Completed -> ResonanceMint
        AgentMissionStatus.Failed, AgentMissionStatus.Cancelled -> InterventionCoral
        null -> PulseMagenta
    }
    val benchmarkLoaded = rootPath.trim() == StoryForgeBenchmarkFolder &&
        objective.trim() == StoryForgeBenchmarkPremise
    val reservedStoryRoot = isReservedStoryForgeBenchmarkRoot(rootPath)

    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = modifier.sovereignGlass(statusColor, radius = 16.dp, depth = 0.82f, elevation = 3.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("THE LONG FORGE", color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "A focused studio for one objective, controller-owned continuity, and bounded autonomous work.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            if (mission == null || !active) {
                OutlinedTextField(
                    value = rootPath,
                    onValueChange = onRootPath,
                    label = { Text("MISSION FOLDER") },
                    placeholder = { Text("sovereign-studio-lf01") },
                    supportingText = { Text("Relative to the connected project; ASCII paths only") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (reservedStoryRoot && !benchmarkLoaded) {
                    Text(
                        "$StoryForgeBenchmarkFolder is reserved for the reviewed benchmark. " +
                            "Choose another mission folder or reload the complete preset.",
                        color = InterventionCoral,
                        fontSize = 11.sp,
                    )
                }
                OutlinedTextField(
                    value = objective,
                    onValueChange = onObjective,
                    label = { Text("OBJECTIVE / STORY PREMISE") },
                    placeholder = {
                        Text("Describe the finished artifact, constraints, checks, and completion standard.")
                    },
                    minLines = 4,
                    maxLines = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnswerMode.entries.forEach { mode ->
                    FluorescentChip(
                        selected = selectedMode == mode,
                        onClick = { onMode(mode) },
                        label = "${mode.label} · ${answerModeTokenBudget(mode)}",
                        accent = answerModeAccent(mode),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    answerModeRouteHint(selectedMode, cockpit),
                    color = if (selectedMode == AnswerMode.Performance && !cockpit.npuEligible) {
                        WaitingAmber
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                OutlinedButton(
                    onClick = onLoadStoryBenchmark,
                    border = BorderStroke(1.dp, PulseMagenta),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "LOAD 120-CHAPTER BENCHMARK",
                        color = PulseMagenta,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "Fills the reviewed folder, full premise, and Quality mode. Nothing runs until " +
                        "START STORY FORGE is pressed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
                if (!benchmarkLoaded) {
                    Button(
                        onClick = onStart,
                        enabled = workspaceConnected && cockpit.canSend && rootPath.isNotBlank() &&
                            objective.isNotBlank() && !reservedStoryRoot,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PulseMagenta,
                            contentColor = Obsidian,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (cockpit.isGenerating) "REQUEST ACCEPTED…" else
                                "GRANT SCOPED AUTONOMY & START",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = onStoryStart,
                        enabled = workspaceConnected && cockpit.canSend && rootPath.isNotBlank() &&
                            objective.isNotBlank() && !reservedStoryRoot,
                        border = BorderStroke(1.dp, HorizonCyan),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (cockpit.isGenerating) "REQUEST ACCEPTED…" else
                                "START STORY FORGE · ONE STORY.MD",
                            color = HorizonCyan,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        "BENCHMARK LANE LOCKED · GENERIC WORKSPACE ROUTING DISABLED",
                        color = ResonanceMint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Button(
                        onClick = onStoryStart,
                        enabled = workspaceConnected && cockpit.canSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HorizonCyan,
                            contentColor = Obsidian,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (cockpit.isGenerating) "STORY FORGE STARTING…" else
                                "START 120-CHAPTER STORY FORGE",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    "ONE START TAP GRANTS CREATE / WRITE / MKDIR INSIDE THE MISSION FOLDER · " +
                        "NO PER-FILE CLICKS · EXECUTION AND NETWORK STAY SEPARATE",
                    color = ResonanceMint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
                Text(
                    "LONG FORM · CONTROLLER-CHUNKED · MATRIX-CHECKPOINTED · OUTPUT LIMIT APPLIES PER CALL",
                    color = SoftViolet,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
                if (!workspaceConnected) {
                    Text("Connect a project before starting a work session.", color = WaitingAmber, fontSize = 11.sp)
                }
            }

            if (mission != null) {
                HorizontalDivider(color = statusColor.copy(alpha = 0.35f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${mission.id} · ${mission.status.name.uppercase()}",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(mission.mode.label.uppercase(), color = SoftViolet, fontSize = 10.sp)
                }
                Text(
                    mission.objective,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
                Text(
                    "SCOPE ${mission.rootPath} · " +
                        "${if (mission.planKind == StoryForgeMissionKind) "CHAPTERS" else "ACTIONS"} " +
                        "${mission.completedActions}/${mission.maxActions}",
                    color = MutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LongForgePhaseRail(
                    completedActions = mission.completedActions,
                    maxActions = mission.maxActions,
                    status = mission.status,
                    story = mission.planKind == StoryForgeMissionKind,
                )
                FluorescentProgress(
                    progress = (mission.completedActions.toFloat() / mission.maxActions.toFloat())
                        .coerceIn(0f, 1f),
                    accent = statusColor,
                    motionEnabled = cockpit.thermalStatus == null || cockpit.thermalStatus < 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "WRITE ${formatBytes(mission.writtenBytes)} / ${formatBytes(mission.maxWriteBytes)}",
                    color = MutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                if (mission.lastAction.isNotBlank()) {
                    Text(
                        "LAST ${mission.lastAction}",
                        color = HorizonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (mission.guidance.isNotEmpty()) {
                    Text(
                        "GUIDANCE ${mission.guidance.size} · ${mission.guidance.last()}",
                        color = SoftViolet,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (mission.lastResult.isNotBlank()) {
                    Text(
                        mission.lastResult,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    if (cockpit.isGenerating) {
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = InterventionCoral),
                        ) { Text("STOP", color = Obsidian, fontWeight = FontWeight.Bold) }
                    } else if (mission.status == AgentMissionStatus.Paused && cockpit.canSend) {
                        Button(onClick = { onCommand("/mission resume") }) { Text("RESUME") }
                    } else if (mission.status == AgentMissionStatus.Running && cockpit.canSend) {
                        OutlinedButton(onClick = { onCommand("/mission pause") }) { Text("PAUSE") }
                    }
                    OutlinedButton(
                        onClick = { onCommand("/mission status") },
                        enabled = cockpit.canSend && !cockpit.isGenerating,
                    ) { Text("STATUS") }
                    OutlinedButton(
                        onClick = { onCommand("/mission cancel") },
                        enabled = mission.active && cockpit.canSend && !cockpit.isGenerating,
                    ) { Text("CANCEL") }
                }

                if (mission.active) {
                    OutlinedTextField(
                        value = guidance,
                        onValueChange = onGuidance,
                        label = { Text("GUIDANCE / INTERRUPTION") },
                        placeholder = { Text("Add a constraint without erasing the mission checkpoint") },
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onGuide,
                        enabled = guidance.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (cockpit.isGenerating) "QUEUE GUIDANCE" else "GUIDE NEXT RUN")
                    }
                }
            }

            Text(
                if (mission?.planKind == StoryForgeMissionKind) {
                    "STORY GRANT · 120 CONTROLLER COMMITS · ONE FILE · POST-SYNC RECOVERY MARKERS · EXACT AUTO-STOP"
                } else {
                    "GRANT · 120 ACTIONS · MATRIX OFFLOAD · RECURSION GUARD · 1 MiB WRITES · AGENT DELETE OFF · EXEC/NET NEED AGENTS APPROVAL"
                },
                color = ResonanceMint,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun WorkSessionThread(
    missionMessages: List<ChatMessage>,
    streamText: String,
    streamActive: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = modifier.sovereignGlass(PulseMagenta, radius = 16.dp, depth = 0.76f, elevation = 2.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("WORK SESSION TRANSCRIPT", color = PulseMagenta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("LATEST FOCUSED", color = HorizonCyan, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            HorizontalDivider(color = PulseMagenta.copy(alpha = 0.24f))
            if (missionMessages.isEmpty() && streamText.isBlank()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(18.dp),
                ) {
                    Text("No work-session handoff yet.", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "The initial objective stays in the control panel, so opening this view never lands at " +
                            "the top of a giant prompt. Verified handoffs and blockers appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            } else {
                val tailIndex = missionMessages.size + (if (streamText.isNotBlank()) 1 else 0)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 64.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(missionMessages, key = { it.id }) { message -> MessageBlock(message) }
                        if (streamText.isNotBlank()) {
                            item { StreamingBlock(streamText, active = streamActive) }
                        }
                        item(key = "work-session-transcript-tail") { Spacer(Modifier.height(1.dp)) }
                    }
                    LatestTranscriptButton(
                        listState = listState,
                        tailIndex = tailIndex,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

private val LongForgePhases = listOf("BRIEF", "PLAN", "BUILD", "VERIFY", "HANDOFF")
private val StoryForgePhases = listOf("SEED", "RISING", "TURN", "CLIMAX", "CODA")

@Composable
private fun LongForgePhaseRail(
    completedActions: Int,
    maxActions: Int,
    status: AgentMissionStatus,
    story: Boolean = false,
) {
    val phases = if (story) StoryForgePhases else LongForgePhases
    val fraction = (completedActions.toFloat() / maxActions.coerceAtLeast(1).toFloat())
        .coerceIn(0f, 1f)
    val activeIndex = when {
        status == AgentMissionStatus.Completed -> phases.lastIndex
        completedActions == 0 -> 0
        fraction < 0.08f -> 1
        fraction < 0.78f -> 2
        fraction < 0.94f -> 3
        else -> 4
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        phases.forEachIndexed { index, label ->
            val complete = index < activeIndex || status == AgentMissionStatus.Completed
            val current = index == activeIndex && status != AgentMissionStatus.Completed
            val accent = when {
                current -> PulseMagenta
                complete -> HorizonCyan
                else -> CognitionViolet.copy(alpha = 0.62f)
            }
            Surface(
                color = accent.copy(alpha = if (current) 0.16f else 0.055f),
                contentColor = accent,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, accent.copy(alpha = if (current) 0.82f else 0.30f)),
            ) {
                Text(
                    "${if (complete) "✓" else if (current) "◆" else "·"} ${index + 1} $label",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FluorescentProgress(
    progress: Float,
    accent: Color,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val renderedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (motionEnabled) 220 else 0),
        label = "long-forge-progress",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(9.dp)
            .clip(shape)
            .background(SmokedDeep)
            .border(1.dp, accent.copy(alpha = 0.40f), shape),
    ) {
        if (renderedProgress > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(renderedProgress)
                    .background(
                        Brush.horizontalGradient(
                            listOf(HorizonCyan, PulseMagenta, CognitionViolet),
                        ),
                        shape,
                    ),
            )
        }
    }
}

private fun answerModeAccent(mode: AnswerMode): Color = when (mode) {
    AnswerMode.Performance -> HorizonCyan
    AnswerMode.Adaptive -> PulseMagenta
    AnswerMode.Quality -> CognitionViolet
}

private fun answerModeTokenBudget(mode: AnswerMode): String = when (mode) {
    AnswerMode.Performance -> "1,024 TOKENS/CALL"
    AnswerMode.Adaptive -> "1,536 TOKENS/CALL"
    AnswerMode.Quality -> "2,048 TOKENS/CALL"
}

private fun answerModeRouteHint(mode: AnswerMode, cockpit: CockpitState): String = when (mode) {
    AnswerMode.Performance -> if (cockpit.npuEligible) {
        "ROUTE · E2B/NPU PREFERRED · FALLS TO E4B ONLY WHEN THE CONTROLLER REQUIRES REASONING"
    } else {
        "ROUTE · E4B FALLBACK · E2B/NPU UNAVAILABLE: ${cockpit.npuStatus}"
    }
    AnswerMode.Adaptive -> if (cockpit.npuEligible) {
        "ROUTE · CONTROLLER CHOOSES E2B/NPU FOR CHAT/MEMORY OR E4B FOR DEEP WORK"
    } else {
        "ROUTE · E4B ONLY UNTIL E2B/NPU IS READY"
    }
    AnswerMode.Quality -> "ROUTE · E4B REASONING/CODING PREFERRED"
}

@Composable
private fun WorkspaceBrowser(
    state: WorkspaceState,
    onOpen: (WorkspaceEntry) -> Unit,
    onTrash: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = modifier
            .fillMaxHeight()
            .sovereignGlass(HorizonCyan, radius = 16.dp, depth = 0.78f, elevation = 2.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(state.currentLabel.uppercase(), color = HorizonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.uri }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { onOpen(entry) },
                            enabled = !state.busy && state.pendingTrash == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                (if (entry.isDirectory) "▸  " else "·  ") + entry.displayName,
                                color = if (entry.isDirectory) SoftViolet else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (state.currentLabel != ".anicloud-trash" && entry.displayName != ".anicloud-trash") {
                            TextButton(
                                onClick = { onTrash(entry) },
                                enabled = !state.busy && state.pendingTrash == null && state.lastTrash == null,
                            ) {
                                Text("TRASH", color = InterventionCoral, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEditor(
    state: WorkspaceState,
    workspaceViewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val editorLanguage = remember(state.selected?.displayName) {
        languageForFile(state.selected?.displayName)
    }
    val codeTransformation = remember(editorLanguage) {
        SovereignCodeTransformation(editorLanguage)
    }
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = modifier
            .fillMaxHeight()
            .sovereignGlass(CognitionViolet, radius = 16.dp, depth = 0.78f, elevation = 2.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selected?.displayName ?: "SELECT A TEXT FILE",
                    color = if (state.isDirty) WaitingAmber else ResonanceMint,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (state.selected != null) {
                    Text(
                        editorLanguage.uppercase(),
                        color = HorizonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                OutlinedButton(onClick = workspaceViewModel::revert, enabled = state.isDirty && !state.busy) {
                    Text("REVERT")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = workspaceViewModel::save, enabled = state.isDirty && !state.busy) {
                    Text("SAVE")
                }
            }
            OutlinedTextField(
                value = state.editorText,
                onValueChange = workspaceViewModel::updateEditor,
                enabled = state.selected != null && !state.busy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = codeTransformation,
                placeholder = { Text("Open a project file to begin co-creation.") },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AgentSurface(cockpit: CockpitState, actions: CockpitActions) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeading("Agents", "Checkpointed work that never hides its state") }
        item { AgentMemoryContext(cockpit.memoryMatrix) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCard(
                    "DEVELOPER PLUGIN · TERMUX",
                    when {
                        !cockpit.termuxBridge.enabled -> "Disabled"
                        cockpit.termuxBridge.ready -> "Ready"
                        else -> "Setup required"
                    },
                    cockpit.termuxBridge.detail,
                    when {
                        !cockpit.termuxBridge.enabled -> MutedText
                        cockpit.termuxBridge.ready -> ResonanceMint
                        else -> WaitingAmber
                    },
                )
                OutlinedButton(
                    onClick = {
                        actions.onSetTermuxPluginEnabled(!cockpit.termuxBridge.enabled)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (cockpit.termuxBridge.enabled) "DISABLE DEVELOPER PLUGIN" else "ENABLE DEVELOPER PLUGIN")
                }
                if (
                    cockpit.termuxBridge.enabled &&
                    cockpit.termuxBridge.installed && cockpit.termuxBridge.serviceAvailable &&
                    !cockpit.termuxBridge.permissionGranted
                ) {
                    Button(
                        onClick = actions.onRequestTermuxPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("GRANT TERMUX COMMAND PERMISSION") }
                }
            }
        }
        if (cockpit.pendingExecutions.isEmpty()) {
            item {
                StatusCard(
                    "EXECUTION QUEUE",
                    "No pending commands",
                    "Sovereign Core may prepare run, test, build, or dependency plans. " +
                        "Every exact command waits here for approval.",
                    ResonanceMint,
                )
            }
        } else {
            items(cockpit.pendingExecutions, key = { "execution-${it.id}" }) { pending ->
                PendingExecutionCard(
                    pending = pending,
                    bridgeReady = cockpit.termuxBridge.ready,
                    bridgeRoot = cockpit.termuxBridge.workdir,
                    anotherActionBusy = cockpit.activeAgentActionId != null ||
                        cockpit.pendingExecutions.any {
                            it.id != pending.id && it.status in setOf("running", "cancel_requested")
                        },
                    actions = actions,
                )
            }
        }
        if (cockpit.pendingActions.isEmpty()) {
            item {
                StatusCard(
                    "APPROVAL QUEUE",
                    "No pending writes",
                    "Workspace list/read tools run automatically. Mutating actions stop here for your decision.",
                    ResonanceMint,
                )
            }
        } else {
            item {
                StatusCard(
                    "APPROVAL QUEUE",
                    "${cockpit.pendingActions.size} action${if (cockpit.pendingActions.size == 1) "" else "s"} waiting",
                    "Nothing below has executed yet.",
                    WaitingAmber,
                )
            }
            items(cockpit.pendingActions, key = { it.id }) { pending ->
                PendingActionCard(
                    pending = pending,
                    waitingForResponse = cockpit.isGenerating,
                    busy = cockpit.isGenerating || cockpit.activeAgentActionId != null ||
                        cockpit.pendingExecutions.any {
                            it.status in setOf("running", "cancel_requested")
                        },
                    actions = actions,
                )
            }
        }
        item {
            ContractCard(
                "Execution contract",
                listOf(
                    "Runs in bounded, restartable cycles",
                    "Relevant Matrix context guides planning without granting authority",
                    "Writes create versioned snapshots",
                    "Scripts and dependency changes require exact command approval",
                    "Dependency plans declare packages and network use",
                    "Deletion remains unavailable in this build",
                    "External actions require batch preview and approval",
                    "Thermal severity can reduce or pause work",
                ),
            )
        }
    }
}

@Composable
private fun PendingExecutionCard(
    pending: PendingExecutionAction,
    bridgeReady: Boolean,
    bridgeRoot: String,
    anotherActionBusy: Boolean,
    actions: CockpitActions,
) {
    val running = pending.status in setOf("running", "cancel_requested")
    val accent = if (running) HorizonCyan else WaitingAmber
    val resolvedWorkdir = when {
        bridgeRoot.isBlank() -> pending.workdir
        pending.workdir == "." -> bridgeRoot
        else -> "$bridgeRoot/${pending.workdir}"
    }
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier.sovereignGlass(accent, radius = 16.dp, depth = 0.82f, elevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "EXECUTION #${pending.id} · ${pending.kind.label}",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pending.status.uppercase(),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            Text(
                "WORKDIR $resolvedWorkdir · TIMEOUT ${pending.timeoutSeconds}s",
                color = HorizonCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    pending.command,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (pending.dependencies.isNotEmpty()) {
                Text(
                    "PACKAGES · ${pending.dependencies.joinToString()}",
                    color = SoftViolet,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            Text(
                if (pending.networkRequired) {
                    "NETWORK · REQUIRED AND INCLUDED IN THIS APPROVAL"
                } else {
                    "NETWORK · NOT DECLARED"
                },
                color = if (pending.networkRequired) InterventionCoral else ResonanceMint,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "BOUNDARY · RUNS AS TERMUX · WORKDIR IS NOT AN OS SANDBOX",
                color = InterventionCoral,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            if (pending.reason.isNotBlank()) {
                Text(pending.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (pending.status) {
                    "pending" -> {
                        Button(
                            onClick = { actions.onApproveExecutionAction(pending.id) },
                            enabled = bridgeReady && !anotherActionBusy,
                        ) { Text("APPROVE & RUN") }
                        OutlinedButton(
                            onClick = { actions.onDenyExecutionAction(pending.id) },
                            enabled = !anotherActionBusy,
                        ) { Text("DENY") }
                    }
                    "running" -> Button(
                        onClick = { actions.onStopExecutionAction(pending.id) },
                        enabled = !anotherActionBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = InterventionCoral),
                    ) { Text("STOP", color = Obsidian, fontWeight = FontWeight.Bold) }
                    "cancel_requested" -> Text(
                        "STOP SENT · WAITING FOR TERMUX EXIT RECORD",
                        color = InterventionCoral,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentMemoryContext(matrix: MemoryMatrixSnapshot) {
    val planningContext = matrix.recentMemories
        .filter {
            it.pinned || it.kind in setOf(
                "user_goal",
                "project_fact",
                "decision",
                "user_preference",
            )
        }
        .take(5)
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(SoftViolet, radius = 16.dp, depth = 0.75f, elevation = 2.dp)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MEMORY CONTEXT",
                color = SoftViolet,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${matrix.memoryCount} ACTIVE · ${if (matrix.ftsAvailable) "FTS" else "FALLBACK"}",
                color = ResonanceMint,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            "Relevant Matrix entries inform each plan; they never approve a write or expand workspace access.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        if (matrix.activeSessionCheckpoint.populated) {
            Text(
                "SESSION CAPSULE · ${matrix.activeSessionCheckpoint.openLoops.size} OPEN · " +
                    "${matrix.activeSessionCheckpoint.decisions.size} DECISIONS",
                color = PulseMagenta,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        matrix.latestContextWindow?.let { context ->
            Text(
                "CONTEXT LEDGER · ${context.lane.wireName.uppercase()} · " +
                    "${context.estimatedPrefillTokens}+${context.outputReserveTokens}/$ContextPhysicalTokens · " +
                    "${context.strategyCount} POLICIES · " +
                    if (context.recovery) "RECOVERY" else if (context.compacted) "COMPACTED" else "FIT",
                color = if (context.recovery) InterventionCoral else HorizonCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        if (planningContext.isEmpty()) {
            Text(
                "No pinned goals, project facts, decisions, or communication preferences yet.",
                color = MutedText,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
        } else {
            planningContext.forEach { memory ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (memory.pinned) "◆" else "·",
                        color = if (memory.pinned) ResonanceMint else HorizonCyan,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${memory.kind.replace('_', ' ').uppercase()} · #${memory.id}",
                            color = if (memory.pinned) ResonanceMint else HorizonCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                        Text(
                            memory.value,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (matrix.recentCalculations.isNotEmpty()) {
            HorizontalDivider(color = HorizonCyan.copy(alpha = 0.24f))
            Text(
                "NUMERIC MATRIX · ${matrix.numericCalculationCount} VERIFIED",
                color = HorizonCyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            matrix.recentCalculations.take(3).forEach { calculation ->
                Text(
                    "#${calculation.id}  ${calculation.expression} = ${calculation.result}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PendingActionCard(
    pending: PendingWorkspaceAction,
    waitingForResponse: Boolean,
    busy: Boolean,
    actions: CockpitActions,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = Modifier.sovereignGlass(
            WaitingAmber,
            radius = 16.dp,
            depth = 0.80f,
            elevation = 2.dp,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ACTION #${pending.id} · ${pending.kind.wireName.uppercase()}",
                    color = WaitingAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("PENDING", color = WaitingAmber, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Text(pending.path, color = HorizonCyan, fontFamily = FontFamily.Monospace)
            if (pending.reason.isNotBlank()) {
                Text(pending.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pending.content.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = highlightCode(
                            pending.content.take(1_500) +
                                if (pending.content.length > 1_500) "\n…preview clipped" else "",
                            languageForFile(pending.path),
                        ),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { actions.onApproveWorkspaceAction(pending.id) },
                    enabled = !busy,
                ) {
                    Text(
                        when {
                            waitingForResponse -> "WAIT FOR SAFE BOUNDARY"
                            busy -> "ANOTHER ACTION ACTIVE"
                            else -> "APPROVE & WRITE"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { actions.onDenyWorkspaceAction(pending.id) },
                    enabled = !busy,
                ) {
                    Text("DENY")
                }
            }
        }
    }
}

@Composable
private fun SystemSurface(
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    cockpit: CockpitState,
    cockpitActions: CockpitActions,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeading("System Lens", "Measured truth, capability, and control") }
        item { HealthStrip(cockpit) }
        item { DeviceCheckupCard(cockpit) }
        item { ModelControlCard(cockpit = cockpit, actions = cockpitActions) }
        item {
            QuickPreferences(
                defaultMode,
                appearance,
                layoutPreference,
                onDefaultMode,
                onAppearance,
                onLayoutPreference,
            )
        }
        item {
            PrivacyStateCard()
        }
        item {
            DataBoundaryCard()
        }
    }
}

@Composable
private fun SystemLens(cockpit: CockpitState, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(SmokedDeep, HorizonCyan.copy(alpha = 0.045f), SmokedDeep),
                ),
            )
            .padding(16.dp),
    ) {
        Text("SYSTEM LENS", color = HorizonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        LensValue(
            "MODEL",
            cockpit.model?.let {
                "${cockpit.activeModelRole?.shortLabel ?: "LOCAL"} · " +
                    (cockpit.backend?.name ?: cockpit.stage.label.uppercase())
            }
                ?: "NOT CONNECTED",
            modelVitalityColor(cockpit),
        )
        LensValue("BUILD", BuildConfig.VERSION_NAME.uppercase(), HorizonCyan)
        LensValue(
            "TENSOR G5 NPU",
            if (cockpit.npuEligible) "READY" else cockpit.npuStatus.uppercase(),
            if (cockpit.npuEligible) ResonanceMint else WaitingAmber,
        )
        LensValue(
            "DEVICE RAM",
            cockpit.availableMemoryBytes?.let(::formatBytes)?.uppercase() ?: "UNAVAILABLE",
            CognitionViolet,
        )
        LensValue(
            "MEMORY MATRIX",
            "${cockpit.memoryMatrix.memoryCount} MEMORIES · ${cockpit.memoryMatrix.messageCount} MESSAGES",
            if (cockpit.memoryMatrix.ftsAvailable) ResonanceMint else WaitingAmber,
        )
        LensValue(
            "INTERACTION PROFILE",
            "R${cockpit.memoryMatrix.interactionProfile.revision} · " +
                (if (cockpit.memoryMatrix.interactionProfile.automaticAdaptation) "AUTO" else "MANUAL"),
            if (cockpit.memoryMatrix.interactionProfile.automaticAdaptation) ResonanceMint else WaitingAmber,
        )
        LensValue(
            "CONTEXT GATE",
            cockpit.lastContextDecision?.let {
                "${it.scope.label.uppercase()} · ${it.relevanceScore}/${it.threshold}"
            } ?: "NOT MEASURED",
            cockpit.lastContextDecision?.let { HorizonCyan } ?: MutedText,
        )
        val latestContext = cockpit.memoryMatrix.latestContextWindow
        LensValue(
            "CONTEXT ORCHESTRATOR",
            latestContext?.let {
                "${it.lane.wireName.uppercase()} · ${it.estimatedPrefillTokens}+" +
                    "${it.outputReserveTokens}/$ContextPhysicalTokens · " +
                    "${it.estimatedHeadroomTokens} HEADROOM"
            } ?: "NOT MEASURED · 20-POLICY PACK READY",
            latestContext?.let {
                if (it.recovery) InterventionCoral else if (it.compacted) WaitingAmber else ResonanceMint
            } ?: MutedText,
        )
        if (latestContext != null) {
            LensValue(
                "CONTEXT LEDGER",
                "${cockpit.memoryMatrix.contextWindowCount} CALLS · ${latestContext.strategyCount} POLICIES · " +
                    when {
                        latestContext.recovery -> "RECOVERY PACK"
                        latestContext.compacted -> "BOUNDED COMPACTION"
                        else -> "NATIVE FIT"
                    },
                if (latestContext.recovery) InterventionCoral else SoftViolet,
            )
        }
        LensValue(
            "THERMAL",
            thermalStatusLabel(cockpit.thermalStatus).uppercase(),
            thermalVitalityColor(cockpit.thermalStatus),
        )
        ThermalSparkline(cockpit.thermalHistory, cockpit.thermalStatus)
        LensValue(
            "LAST RESPONSE",
            cockpit.lastFirstTokenMillis?.let { ttft ->
                "TTFT ${formatDuration(ttft)} · TOTAL " +
                    (cockpit.lastResponseMillis?.let(::formatDuration) ?: "IN PROGRESS")
            } ?: "NOT MEASURED",
            if (cockpit.lastFirstTokenMillis == null) MutedText else HorizonCyan,
        )
        LensValue(
            "MODEL LOAD",
            cockpit.modelLoadMillis?.let(::formatDuration) ?: "NOT MEASURED",
            if (cockpit.modelLoadMillis == null) MutedText else CognitionViolet,
        )
        LensValue("GROUNDING", "OFFLINE", MutedText)
        LensValue("ROUTE", cockpit.routeLabel.uppercase(), modelVitalityColor(cockpit))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            "Device RAM is Android MemAvailable. Memory Matrix is the app-private SQLite store. " +
                "Thermal history contains categorical Android status events—not an invented temperature.",
            color = MutedText,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("PIXEL 10 PRO REFERENCE\nOTHER DEVICES EXPERIMENTAL", color = SoftViolet, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun LensValue(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MutedText, fontSize = 11.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
private fun ThermalSparkline(history: List<Int>, current: Int?) {
    val points = if (history.isEmpty()) current?.let(::listOf).orEmpty() else history
    val color = thermalVitalityColor(current)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        drawLine(
            color = MutedText.copy(alpha = 0.22f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
        )
        if (points.isEmpty()) return@Canvas
        val xStep = if (points.size == 1) 0f else size.width / (points.size - 1)
        val path = Path()
        var singlePoint = Offset.Zero
        points.forEachIndexed { index, raw ->
            val x = if (points.size == 1) size.width - 4f else index * xStep
            val y = size.height - (raw.coerceIn(0, 6) / 6f * (size.height - 4f)) - 2f
            singlePoint = Offset(x, y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (points.size == 1) {
            drawCircle(color = color, radius = 3.5f, center = singlePoint)
        } else {
            drawPath(path, color = color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun PrivacyStateCard() {
    val entries = listOf(
        Triple("⌁", "Authentication", "Android biometric or device credential"),
        Triple("▣", "Ordinary history", "App-private SQLite WAL + FTS5 recall"),
        Triple("◇", "Sanctuary", "Ciphertext vault adapter pending"),
        Triple("↗", "Diagnostics", "Manual local export only"),
        Triple("◉", "Screen privacy", "User-controlled concealment toggle pending"),
    )
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PRIVACY STATE", color = SoftViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            entries.forEach { (glyph, title, detail) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(glyph, color = CognitionViolet, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DataBoundaryCard() {
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "EXTERNAL DATA BOUNDARY",
                    color = HorizonCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("OFFLINE", color = ResonanceMint, fontFamily = FontFamily.Monospace)
            }
            Text(
                "This APK does not declare Android INTERNET access and cannot send prompts to an " +
                    "external provider.",
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Android does not show a runtime Internet permission dialog. Before a future API " +
                    "request, AniCloudAI must show its own review with provider, purpose, outbound " +
                    "data preview, duration, spending scope, retention, and revoke controls.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Text(
                "API TOKEN VAULT · NOT ENABLED",
                color = PulseMagenta,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.Transparent),
        modifier = modifier.sovereignGlass(accent, radius = 14.dp, depth = 0.76f, elevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActionCard(title: String, detail: String, accent: Color, value: String) {
    StatusCard(title, value, detail, accent, Modifier.width(210.dp))
}

@Composable
private fun ConversationRow(title: String, detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
    ) {
        Box(Modifier.size(7.dp).background(CognitionViolet, RoundedCornerShape(50)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Text("→", color = HorizonCyan)
    }
}

@Composable
private fun ContractCard(title: String, lines: List<String>) {
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = SoftViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            lines.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PageHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title.uppercase(), color = SoftViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

private fun destinationAccent(destination: Destination): Color = when (destination) {
    Destination.Home, Destination.Workspace -> HorizonCyan
    Destination.Chat, Destination.Agents -> PulseMagenta
    Destination.Memory, Destination.System -> CognitionViolet
}

private fun phoneDestinationLabel(destination: Destination): String = when (destination) {
    Destination.Workspace -> "Files"
    else -> destination.label
}

@Composable
private fun DestinationIcon(destination: Destination, selected: Boolean) {
    val color = if (selected) destinationAccent(destination) else MutedText.copy(alpha = 0.82f)
    Canvas(Modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val pad = 3.dp.toPx()
        when (destination) {
            Destination.Home -> {
                val roof = Path().apply {
                    moveTo(pad, cy)
                    lineTo(cx, pad)
                    lineTo(size.width - pad, cy)
                }
                drawPath(roof, color, style = stroke)
                drawLine(color, Offset(pad + 2f, cy - 1f), Offset(pad + 2f, size.height - pad), stroke.width)
                drawLine(color, Offset(size.width - pad - 2f, cy - 1f), Offset(size.width - pad - 2f, size.height - pad), stroke.width)
                drawLine(color, Offset(pad + 2f, size.height - pad), Offset(size.width - pad - 2f, size.height - pad), stroke.width)
            }
            Destination.Chat -> {
                drawCircle(color, radius = size.minDimension * 0.34f, center = Offset(cx, cy - 1f), style = stroke)
                drawLine(color, Offset(cx - 1f, cy + size.height * 0.3f), Offset(cx - size.width * 0.24f, size.height - pad), stroke.width)
                drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(cx - 4.dp.toPx(), cy - 1f))
                drawCircle(color, radius = 1.2.dp.toPx(), center = Offset(cx + 4.dp.toPx(), cy - 1f))
            }
            Destination.Memory -> {
                drawCircle(color, radius = size.minDimension * 0.37f, center = Offset(cx, cy), style = stroke)
                drawCircle(color, radius = size.minDimension * 0.19f, center = Offset(cx, cy), style = stroke)
                drawCircle(color, radius = 1.7.dp.toPx(), center = Offset(cx, cy))
                drawLine(color, Offset(cx, pad), Offset(cx, cy - size.minDimension * 0.19f), stroke.width)
            }
            Destination.Workspace -> {
                drawLine(color, Offset(pad, pad), Offset(pad, size.height - pad), stroke.width)
                drawLine(color, Offset(pad, pad), Offset(cx - 2f, pad), stroke.width)
                drawLine(color, Offset(pad, size.height - pad), Offset(cx - 2f, size.height - pad), stroke.width)
                drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad, size.height - pad), stroke.width)
                drawLine(color, Offset(cx + 2f, pad), Offset(size.width - pad, pad), stroke.width)
                drawLine(color, Offset(cx + 2f, size.height - pad), Offset(size.width - pad, size.height - pad), stroke.width)
                drawLine(color, Offset(cx, cy - 4.dp.toPx()), Offset(cx, cy + 4.dp.toPx()), stroke.width)
                drawLine(color, Offset(cx - 4.dp.toPx(), cy), Offset(cx + 4.dp.toPx(), cy), stroke.width)
            }
            Destination.Agents -> {
                val top = Offset(cx, pad)
                val left = Offset(pad, size.height - pad)
                val right = Offset(size.width - pad, size.height - pad)
                drawLine(color, top, left, stroke.width)
                drawLine(color, top, right, stroke.width)
                drawLine(color, left, right, stroke.width)
                drawCircle(color, radius = 2.dp.toPx(), center = top)
                drawCircle(color, radius = 2.dp.toPx(), center = left)
                drawCircle(color, radius = 2.dp.toPx(), center = right)
            }
            Destination.System -> {
                drawCircle(color, radius = size.minDimension * 0.31f, center = Offset(cx, cy), style = stroke)
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(cx, cy))
                listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                    val radians = Math.toRadians(degrees.toDouble())
                    val inner = size.minDimension * 0.35f
                    val outer = size.minDimension * 0.47f
                    drawLine(
                        color,
                        Offset(
                            cx + kotlin.math.cos(radians).toFloat() * inner,
                            cy + kotlin.math.sin(radians).toFloat() * inner,
                        ),
                        Offset(
                            cx + kotlin.math.cos(radians).toFloat() * outer,
                            cy + kotlin.math.sin(radians).toFloat() * outer,
                        ),
                        stroke.width,
                    )
                }
            }
        }
    }
}

private fun runtimePhase(stage: ModelStage): RuntimePhase = when (stage) {
    ModelStage.Empty -> RuntimePhase.Offline
    ModelStage.Importing -> RuntimePhase.Recalling
    ModelStage.Initializing -> RuntimePhase.Verifying
    ModelStage.Ready -> RuntimePhase.Ready
    ModelStage.Generating -> RuntimePhase.Reasoning
    ModelStage.Recovering -> RuntimePhase.Recovering
    ModelStage.Error -> RuntimePhase.Degraded
}

private fun modelVitalityColor(cockpit: CockpitState): Color = when (cockpit.stage) {
    ModelStage.Empty -> WaitingAmber
    ModelStage.Importing, ModelStage.Initializing -> CognitionViolet
    ModelStage.Ready -> ResonanceMint
    ModelStage.Generating -> HorizonCyan
    ModelStage.Recovering -> WaitingAmber
    ModelStage.Error -> InterventionCoral
}

private fun thermalVitalityColor(status: Int?): Color = when {
    status == null -> WaitingAmber
    status < 2 -> ResonanceMint
    status < 3 -> WaitingAmber
    else -> InterventionCoral
}

private fun formatBytes(bytes: Long): String {
    val gib = 1024.0 * 1024.0 * 1024.0
    val mib = 1024.0 * 1024.0
    return if (bytes >= gib) {
        String.format(Locale.US, "%.2f GiB", bytes / gib)
    } else {
        String.format(Locale.US, "%.1f MiB", bytes / mib)
    }
}

private fun formatDuration(millis: Long): String = if (millis < 1_000L) {
    "$millis ms"
} else {
    String.format(Locale.US, "%.2f s", millis / 1_000.0)
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
    runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
