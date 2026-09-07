package dev.anicloud.sovereign.prototype.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import dev.anicloud.sovereign.prototype.Appearance
import dev.anicloud.sovereign.prototype.ChatMessage
import dev.anicloud.sovereign.prototype.ChatSpeaker
import dev.anicloud.sovereign.prototype.CockpitState
import dev.anicloud.sovereign.prototype.ComposerMaxVisibleLines
import dev.anicloud.sovereign.prototype.Destination
import dev.anicloud.sovereign.prototype.FoundationLayout
import dev.anicloud.sovereign.prototype.FoundationPreferenceStore
import dev.anicloud.sovereign.prototype.LayoutPreference
import dev.anicloud.sovereign.prototype.ModelStage
import dev.anicloud.sovereign.prototype.RuntimePhase
import dev.anicloud.sovereign.prototype.SovereignViewModel
import dev.anicloud.sovereign.prototype.WorkspaceEntry
import dev.anicloud.sovereign.prototype.WorkspaceState
import dev.anicloud.sovereign.prototype.WorkspaceViewModel
import dev.anicloud.sovereign.prototype.allowsAmbientMotion
import dev.anicloud.sovereign.prototype.resolveFoundationLayout
import dev.anicloud.sovereign.prototype.thermalStatusLabel
import java.util.Locale

private data class CockpitActions(
    val onImportModel: () -> Unit,
    val onRetryModel: () -> Unit,
    val onSend: (String, AnswerMode) -> Unit,
    val onStop: () -> Unit,
)

@Composable
fun AniCloudApp(
    onLock: () -> Unit,
    sovereignViewModel: SovereignViewModel = viewModel(),
) {
    val context = LocalContext.current
    val cockpit by sovereignViewModel.state.collectAsStateWithLifecycle()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(sovereignViewModel::importModel)
    }
    val cockpitActions = CockpitActions(
        onImportModel = {
            modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onRetryModel = sovereignViewModel::retryModel,
        onSend = sovereignViewModel::send,
        onStop = sovereignViewModel::stopGeneration,
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (allowsAmbientMotion(cockpit.thermalStatus, cockpit.stage)) {
            val transition = rememberInfiniteTransition(label = "living-void")
            val pulse by transition.animateFloat(
                initialValue = 0.035f,
                targetValue = 0.085f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 9_000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "void-breath",
            )
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            HorizonCyan.copy(alpha = pulse),
                            CognitionViolet.copy(alpha = pulse * 0.35f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.62f, size.height * 0.42f),
                        radius = size.minDimension * 0.58f,
                    ),
                    radius = size.minDimension * 0.58f,
                    center = Offset(size.width * 0.62f, size.height * 0.42f),
                )
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
    Column(Modifier.fillMaxSize()) {
        TopRail(destination = destination, cockpit = cockpit, onLock = onLock)
        Box(Modifier.weight(1f)) {
            DestinationContent(
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
            )
        }
        NavigationBar(containerColor = SmokedDeep) {
            Destination.entries.forEach { item ->
                NavigationBarItem(
                    selected = destination == item,
                    onClick = { onDestination(item) },
                    icon = { Text(destinationGlyph(item)) },
                    label = { Text(item.label) },
                )
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
            DestinationContent(
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
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
            compact = compact,
        )

        Destination.Chat -> ChatSurface(
            defaultMode = defaultMode,
            cockpit = cockpit,
            cockpitActions = cockpitActions,
        )
        Destination.Workspace -> WorkspaceSurface()
        Destination.Agents -> AgentSurface()
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
            .background(SmokedDeep)
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
            .background(SmokedDeep)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("◈  ANICLOUDAI", color = HorizonCyan, fontWeight = FontWeight.Bold)
        Text("SOVEREIGN CORE", color = SoftViolet, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Destination.entries.forEach { item ->
            FilterChip(
                selected = destination == item,
                onClick = { onDestination(item) },
                label = { Text("${destinationGlyph(item)}  ${item.label}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (cockpit.model == null) "FOUNDATION · NO MODEL LOADED" else
                "E4B · ${cockpit.backend?.name ?: cockpit.stage.label.uppercase()}",
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
                SetupChecklist(cockpit = cockpit, onDismiss = { showChecklist = false })
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
                ActionCard("VOICE", "Conversation shortcut", HorizonCyan)
                ActionCard("AGENTS", "Foreground service pending", CognitionViolet)
                ActionCard(
                    "MODELS",
                    cockpit.model?.displayName ?: "Local import ready",
                    modelVitalityColor(cockpit),
                )
            }
        }
        item {
            SectionTitle("Recent conversations")
            Spacer(Modifier.height(8.dp))
            ConversationRow("Current native session", "In memory · persistence adapter pending")
            ConversationRow("Termux history", "Not imported")
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
            title = "Memory",
            value = cockpit.availableMemoryBytes?.let(::formatBytes) ?: "Unavailable",
            detail = "Android MemAvailable · measured now",
            accent = CognitionViolet,
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
                    "In-memory only · durable history adapter pending",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            Text("OPEN  →", color = ResonanceMint, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupChecklist(cockpit: CockpitState, onDismiss: () -> Unit) {
    OutlinedCard(border = BorderStroke(1.dp, CognitionViolet.copy(alpha = 0.55f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PRIVATE DOGFOOD CHECKLIST", color = SoftViolet, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("DISMISS") }
            }
            Text(
                if (cockpit.model == null) "○ Import model and record SHA-256" else
                    "● Model fingerprint recorded: ${cockpit.model.sha256.take(12)}…",
                color = if (cockpit.model == null) MaterialTheme.colorScheme.onSurface else ResonanceMint,
            )
            Text("○ Import a reviewed Termux archive")
            Text("○ Run the synthetic integrity benchmark")
            Text("○ Enable voice only when requested")
        }
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

            cockpit.model?.let { model ->
                Text(
                    "SHA-256  ${model.sha256}\nSIZE     ${formatBytes(model.byteSize)}\n" +
                        "BACKEND  ${cockpit.backend?.name ?: "NOT ACTIVE"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OutlinedButton(
                    onClick = actions.onImportModel,
                    enabled = cockpit.stage !in setOf(
                        ModelStage.Importing,
                        ModelStage.Initializing,
                        ModelStage.Generating,
                        ModelStage.Recovering,
                    ) && (cockpit.thermalStatus == null || cockpit.thermalStatus < 3),
                ) {
                    Text(if (cockpit.model == null) "CHOOSE .LITERTLM" else "CHOOSE DIFFERENT MODEL")
                }
                if (cockpit.stage == ModelStage.Error && cockpit.model != null) {
                    Button(onClick = actions.onRetryModel) { Text("RETRY LOAD") }
                }
            }
            Text(
                "The picker grants one file only. AniCloudAI copies it into app-private, " +
                    "no-backup storage; no broad storage permission is requested.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
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
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var selection by remember(defaultMode) {
        mutableStateOf(AnswerModeSelection(defaultMode = defaultMode))
    }

    Column(Modifier.fillMaxSize()) {
        TruthThread(
            phase = runtimePhase(cockpit.stage),
            detail = cockpit.detail,
            active = cockpit.isGenerating,
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (cockpit.model == null || cockpit.stage == ModelStage.Error) {
                item { ModelControlCard(cockpit = cockpit, actions = cockpitActions) }
            }
            items(cockpit.messages, key = { it.id }) { message -> MessageBlock(message) }
            if (cockpit.streamText.isNotBlank()) {
                item { StreamingBlock(cockpit.streamText) }
            }
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
        )
    }
}

@Composable
private fun TruthThread(phase: RuntimePhase, detail: String, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val color = when (phase) {
            RuntimePhase.Ready -> ResonanceMint
            RuntimePhase.Recovering, RuntimePhase.Degraded -> InterventionCoral
            RuntimePhase.Grounding, RuntimePhase.Verifying -> HorizonCyan
            else -> CognitionViolet
        }
        Text("● ${phase.label.uppercase()}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
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
    val accent = when (message.speaker) {
        ChatSpeaker.User -> CognitionViolet
        ChatSpeaker.Core -> HorizonCyan
        ChatSpeaker.System -> WaitingAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isUser) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else
                    MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp),
            )
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Text(
            when (message.speaker) {
                ChatSpeaker.User -> "YOU"
                ChatSpeaker.Core -> "SOVEREIGN CORE"
                ChatSpeaker.System -> "SYSTEM LENS"
            },
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(message.text, color = MaterialTheme.colorScheme.onSurface, lineHeight = 21.sp)
    }
}

@Composable
private fun StreamingBlock(text: String) {
    MessageBlock(ChatMessage(-1, ChatSpeaker.Core, "$text▌"))
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
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Text("NEXT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                AnswerMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selection.modeForNextResponse() == mode,
                        onClick = { onMode(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message Sovereign Core") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = ComposerMaxVisibleLines,
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
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("SEND", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                if (canSend || isGenerating) {
                    "RETURN NEWLINE · VISIBLE SEND ONLY · 1–7 LINES"
                } else {
                    blockedMessage
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun WorkspaceSurface(workspaceViewModel: WorkspaceViewModel = viewModel()) {
    val state by workspaceViewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(workspaceViewModel::attachRoot)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        PageHeading("Workspace Lens", "A user-granted project tree with reviewable, versioned writes")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Button(onClick = { picker.launch(null) }, enabled = !state.busy) {
                Text(if (state.rootUri == null) "CONNECT PROJECT" else "CHANGE PROJECT")
            }
            OutlinedButton(
                onClick = workspaceViewModel::returnToRoot,
                enabled = state.rootUri != null && state.currentUri != state.rootUri && !state.busy,
            ) { Text("ROOT") }
            OutlinedButton(
                onClick = workspaceViewModel::refresh,
                enabled = state.rootUri != null && !state.busy,
            ) { Text("REFRESH") }
            Text(
                state.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (state.rootUri == null) {
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
                        "Deletion is intentionally unavailable in this build. Every SAVE retains the previous bytes first.",
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
                        WorkspaceBrowser(state, workspaceViewModel::open, Modifier.width(300.dp))
                        WorkspaceEditor(state, workspaceViewModel, Modifier.weight(1f))
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        WorkspaceBrowser(state, workspaceViewModel::open, Modifier.weight(0.38f))
                        WorkspaceEditor(state, workspaceViewModel, Modifier.weight(0.62f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceBrowser(
    state: WorkspaceState,
    onOpen: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(state.currentLabel.uppercase(), color = HorizonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.uri }) { entry ->
                    TextButton(
                        onClick = { onOpen(entry) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            (if (entry.isDirectory) "▸  " else "·  ") + entry.displayName,
                            color = if (entry.isDirectory) SoftViolet else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
    OutlinedCard(modifier = modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.selected?.displayName ?: "SELECT A TEXT FILE",
                    color = if (state.isDirty) WaitingAmber else ResonanceMint,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
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
                placeholder = { Text("Open a project file to begin co-creation.") },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AgentSurface() {
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeading("Agents", "Checkpointed work that never hides its state") }
        item {
            StatusCard(
                "DESIGN PLACEHOLDER",
                "No agent runtime connected",
                "0 writes · foreground service adapter pending",
                CognitionViolet,
            )
        }
        item {
            ContractCard(
                "Execution contract",
                listOf(
                    "Runs in bounded, restartable cycles",
                    "Writes create versioned snapshots",
                    "Deletion always requires confirmation",
                    "External actions require batch preview and approval",
                    "Thermal severity can reduce or pause work",
                ),
            )
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
    }
}

@Composable
private fun SystemLens(cockpit: CockpitState, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxHeight()
            .background(SmokedDeep)
            .padding(16.dp),
    ) {
        Text("SYSTEM LENS", color = HorizonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        LensValue(
            "MODEL",
            cockpit.model?.let { "E4B · ${cockpit.backend?.name ?: cockpit.stage.label.uppercase()}" }
                ?: "NOT CONNECTED",
            modelVitalityColor(cockpit),
        )
        LensValue(
            "MEMORY",
            cockpit.availableMemoryBytes?.let(::formatBytes)?.uppercase() ?: "UNAVAILABLE",
            CognitionViolet,
        )
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
            "Memory is Android MemAvailable. Thermal history contains categorical Android " +
                "status events—not an invented temperature.",
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
        Triple("▣", "Ordinary history", "App-private persistence adapter pending"),
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
private fun StatusCard(
    title: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier, border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))) {
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
private fun ActionCard(title: String, detail: String, accent: Color) {
    StatusCard(title, "Available later", detail, accent, Modifier.width(210.dp))
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

private fun destinationGlyph(destination: Destination): String = when (destination) {
    Destination.Home -> "⌂"
    Destination.Chat -> "◈"
    Destination.Workspace -> "⌘"
    Destination.Agents -> "▸"
    Destination.System -> "◎"
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
