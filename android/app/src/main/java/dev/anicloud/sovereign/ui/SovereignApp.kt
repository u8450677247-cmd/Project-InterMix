package dev.anicloud.sovereign.prototype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anicloud.sovereign.prototype.AnswerMode
import dev.anicloud.sovereign.prototype.AnswerModeSelection
import dev.anicloud.sovereign.prototype.Appearance
import dev.anicloud.sovereign.prototype.ComposerMaxVisibleLines
import dev.anicloud.sovereign.prototype.Destination
import dev.anicloud.sovereign.prototype.FoundationLayout
import dev.anicloud.sovereign.prototype.FoundationPreferenceStore
import dev.anicloud.sovereign.prototype.LayoutPreference
import dev.anicloud.sovereign.prototype.RuntimePhase
import dev.anicloud.sovereign.prototype.resolveFoundationLayout
import kotlinx.coroutines.delay

private enum class Speaker { User, Core }

private data class TranscriptMessage(
    val id: Int,
    val speaker: Speaker,
    val text: String,
)

@Composable
fun AniCloudApp(onLock: () -> Unit) {
    val context = LocalContext.current
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
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            if (layout == FoundationLayout.Desktop) {
                DesktopShell(
                    destination = destination,
                    defaultMode = defaultMode,
                    appearance = appearance,
                    layoutPreference = layoutPreference,
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
private fun PhoneShell(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    onLock: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopRail(destination = destination, onLock = onLock)
        Box(Modifier.weight(1f)) {
            DestinationContent(
                destination = destination,
                defaultMode = defaultMode,
                appearance = appearance,
                layoutPreference = layoutPreference,
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
    onDestination: (Destination) -> Unit,
    onDefaultMode: (AnswerMode) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onLayoutPreference: (LayoutPreference) -> Unit,
    onLock: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavigationPane(
            destination = destination,
            onDestination = onDestination,
            onLock = onLock,
            modifier = Modifier.width(224.dp),
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
        SystemLens(Modifier.width(304.dp))
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    defaultMode: AnswerMode,
    appearance: Appearance,
    layoutPreference: LayoutPreference,
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
            onOpenChat = { onDestination(Destination.Chat) },
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
            compact = compact,
        )

        Destination.Chat -> ChatSurface(defaultMode = defaultMode)
        Destination.Agents -> AgentSurface()
        Destination.System -> SystemSurface(
            defaultMode = defaultMode,
            appearance = appearance,
            layoutPreference = layoutPreference,
            onDefaultMode = onDefaultMode,
            onAppearance = onAppearance,
            onLayoutPreference = onLayoutPreference,
        )
    }
}

@Composable
private fun TopRail(destination: Destination, onLock: () -> Unit) {
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
            Text("ANICLOUDAI", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(destination.label.uppercase(), color = MutedText, fontSize = 10.sp)
        }
        Text("● READY", color = ResonanceMint, fontSize = 11.sp)
        TextButton(onClick = onLock) { Text("LOCK") }
    }
}

@Composable
private fun NavigationPane(
    destination: Destination,
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
        Text("SOVEREIGN CORE", color = SoftViolet, fontSize = 11.sp)
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
        Text("FOUNDATION · NO MODEL LOADED", color = WaitingAmber, fontSize = 10.sp)
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
        item { HealthStrip() }
        item {
            ResumeCard(onOpenChat = onOpenChat)
        }
        if (showChecklist) {
            item {
                SetupChecklist(onDismiss = { showChecklist = false })
            }
        }
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
                ActionCard("AGENTS", "0 active · 1 paused", CognitionViolet)
                ActionCard("MODELS", "Guided download pending", WaitingAmber)
            }
        }
        item {
            SectionTitle("Recent conversations")
            Spacer(Modifier.height(8.dp))
            ConversationRow("Native Android foundation", "Just now · local prototype")
            ConversationRow("Grounding quality review", "Paused · Termux history not imported")
        }
        item {
            StatusCard(
                title = "Downloads & updates",
                value = "No update channel connected",
                detail = "Checks will remain quiet and appear only when a verified update exists.",
                accent = WaitingAmber,
            )
        }
        item {
            Text(
                if (compact) "PHONE LAYOUT · DISPLAY-SPECIFIC" else "DESKTOP LAYOUT · FIXED THREE-PANE",
                color = MutedText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun HealthStrip() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        StatusCard(
            title = "Model",
            value = "Disconnected",
            detail = "UI foundation only",
            accent = WaitingAmber,
            modifier = Modifier.width(190.dp),
        )
        StatusCard(
            title = "Memory",
            value = "No user data",
            detail = "Migration not run",
            accent = CognitionViolet,
            modifier = Modifier.width(190.dp),
        )
        StatusCard(
            title = "Thermal",
            value = "Adapter pending",
            detail = "No invented reading",
            accent = ResonanceMint,
            modifier = Modifier.width(190.dp),
        )
    }
}

@Composable
private fun ResumeCard(onOpenChat: () -> Unit) {
    Card(
        onClick = onOpenChat,
        colors = CardDefaults.cardColors(containerColor = Smoked),
        border = BorderStroke(1.dp, HorizonCyan.copy(alpha = 0.55f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("RESUME LAST CONVERSATION", color = HorizonCyan, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("Native Android foundation", fontWeight = FontWeight.Bold)
                Text("Draft and transcript remain local", color = MutedText, fontSize = 12.sp)
            }
            Text("OPEN  →", color = ResonanceMint, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupChecklist(onDismiss: () -> Unit) {
    OutlinedCard(border = BorderStroke(1.dp, CognitionViolet.copy(alpha = 0.55f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PRIVATE DOGFOOD CHECKLIST", color = SoftViolet, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("DISMISS") }
            }
            Text("○ Verify model package and checksum")
            Text("○ Import a reviewed Termux archive")
            Text("○ Run the synthetic integrity benchmark")
            Text("○ Enable voice only when requested")
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
            Text("QUICK CONTROLS", color = HorizonCyan, fontSize = 11.sp)
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
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
private fun ChatSurface(defaultMode: AnswerMode) {
    val messages = remember {
        mutableStateListOf(
            TranscriptMessage(
                id = 1,
                speaker = Speaker.Core,
                text = "Native foundation ready. No model, memory, or network adapter is connected yet.",
            ),
        )
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var streamText by remember { mutableStateOf("") }
    var activePrompt by remember { mutableStateOf<String?>(null) }
    var generationSerial by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(RuntimePhase.Ready) }
    var phaseDetail by remember { mutableStateOf("Local UI ready") }
    var selection by remember(defaultMode) {
        mutableStateOf(AnswerModeSelection(defaultMode = defaultMode))
    }
    var nextId by remember { mutableIntStateOf(2) }

    LaunchedEffect(activePrompt, generationSerial) {
        val prompt = activePrompt ?: return@LaunchedEffect
        val mode = selection.modeForNextResponse()
        val response = syntheticResponse(prompt, mode)
        streamText = ""
        response.split(' ').forEachIndexed { index, token ->
            delay(34)
            streamText += if (index == 0) token else " $token"
        }
        messages += TranscriptMessage(nextId++, Speaker.Core, streamText)
        streamText = ""
        activePrompt = null
        selection = selection.afterResponse()
        phase = RuntimePhase.Ready
        phaseDetail = "Synthetic stream complete"
    }

    Column(Modifier.fillMaxSize()) {
        TruthThread(phase = phase, detail = phaseDetail, active = activePrompt != null)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(messages, key = { it.id }) { message -> MessageBlock(message) }
            if (streamText.isNotBlank()) {
                item { StreamingBlock(streamText) }
            }
        }
        Composer(
            draft = draft,
            selection = selection,
            isGenerating = activePrompt != null,
            onDraft = { draft = it },
            onMode = {
                selection = selection.copy(
                    oneResponseOverride = if (it == selection.defaultMode) null else it,
                )
            },
            onSend = {
                val submitted = draft
                if (submitted.isNotBlank() && activePrompt == null) {
                    messages += TranscriptMessage(nextId++, Speaker.User, submitted)
                    draft = ""
                    generationSerial++
                    activePrompt = submitted
                    phase = RuntimePhase.Reasoning
                    phaseDetail = "Synthetic stream · ${selection.modeForNextResponse().label}"
                }
            },
            onStop = {
                if (streamText.isNotBlank()) {
                    messages += TranscriptMessage(
                        nextId++,
                        Speaker.Core,
                        "$streamText\n\n[Stopped by user; synthetic output was not committed.]",
                    )
                }
                activePrompt = null
                streamText = ""
                selection = selection.afterResponse()
                phase = RuntimePhase.Recovering
                phaseDetail = "Stopped cleanly · partial output quarantined"
            },
        )
    }
}

private fun syntheticResponse(prompt: String, mode: AnswerMode): String {
    val lines = prompt.lines().size
    return "Synthetic ${mode.label} response: the native composer preserved $lines input line" +
        (if (lines == 1) "" else "s") +
        ". This stream exercises rendering and STOP only; no language model, memory, web search, " +
        "or factual claim pipeline has run."
}

@Composable
private fun TruthThread(phase: RuntimePhase, detail: String, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(SmokedDeep)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val color = when (phase) {
            RuntimePhase.Ready -> ResonanceMint
            RuntimePhase.Recovering, RuntimePhase.Degraded -> InterventionCoral
            RuntimePhase.Grounding, RuntimePhase.Verifying -> HorizonCyan
            else -> CognitionViolet
        }
        Text("● ${phase.label.uppercase()}", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text(
            detail,
            color = MutedText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) Text("CANCELLABLE", color = WaitingAmber, fontSize = 10.sp)
    }
}

@Composable
private fun MessageBlock(message: TranscriptMessage) {
    val isUser = message.speaker == Speaker.User
    val accent = if (isUser) CognitionViolet else HorizonCyan
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isUser) CognitionViolet.copy(alpha = 0.08f) else Smoked,
                RoundedCornerShape(4.dp),
            )
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Text(
            if (isUser) "YOU" else "SOVEREIGN CORE",
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(message.text, lineHeight = 21.sp)
    }
}

@Composable
private fun StreamingBlock(text: String) {
    MessageBlock(TranscriptMessage(-1, Speaker.Core, "$text▌"))
}

@Composable
private fun Composer(
    draft: String,
    selection: AnswerModeSelection,
    isGenerating: Boolean,
    onDraft: (String) -> Unit,
    onMode: (AnswerMode) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = SmokedDeep, tonalElevation = 3.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Text("NEXT", color = MutedText, fontSize = 10.sp)
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
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("SEND", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "RETURN NEWLINE · VISIBLE SEND ONLY · 1–7 LINES",
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
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
                "PAUSED AGENT",
                "Android foundation audit",
                "0 writes · no foreground service connected",
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
        item { HealthStrip() }
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
            ContractCard(
                "Privacy state",
                listOf(
                    "Authentication: Android biometric or device credential",
                    "Ordinary history: app-private storage adapter pending",
                    "Sanctuary: ciphertext vault adapter pending",
                    "Diagnostics: local export only",
                    "Screenshot privacy toggle: pending",
                ),
            )
        }
    }
}

@Composable
private fun SystemLens(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxHeight()
            .background(SmokedDeep)
            .padding(16.dp),
    ) {
        Text("SYSTEM LENS", color = HorizonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LensValue("MODEL", "NOT CONNECTED", WaitingAmber)
        LensValue("MEMORY", "NO USER DATA", CognitionViolet)
        LensValue("THERMAL", "ADAPTER PENDING", ResonanceMint)
        LensValue("GROUNDING", "OFFLINE", MutedText)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("Truthful placeholders replace invented telemetry in this foundation build.", color = MutedText, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("PIXEL 10 PRO REFERENCE\nOTHER DEVICES EXPERIMENTAL", color = SoftViolet, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun LensValue(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MutedText, fontSize = 9.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
            Text(title.uppercase(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
            Text(detail, color = MutedText, fontSize = 11.sp)
        }
        Text("→", color = HorizonCyan)
    }
}

@Composable
private fun ContractCard(title: String, lines: List<String>) {
    OutlinedCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), color = SoftViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            lines.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PageHeading(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title.uppercase(), color = SoftViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

private fun destinationGlyph(destination: Destination): String = when (destination) {
    Destination.Home -> "⌂"
    Destination.Chat -> "◈"
    Destination.Agents -> "▸"
    Destination.System -> "◎"
}

private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
    runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
