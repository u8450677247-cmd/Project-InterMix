package dev.anicloud.anytime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.anicloud.anytime.domain.AnytimeCalendar
import dev.anicloud.anytime.domain.MoonCalculator
import dev.anicloud.anytime.domain.Numerology
import dev.anicloud.anytime.domain.OccurrenceResolver
import dev.anicloud.anytime.domain.Recurrence
import dev.anicloud.anytime.domain.SolarCalculator
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

private val Ink = Color.Black
private val Violet = Color(0xFF9B73DD)
private val Magenta = Color(0xFFDF7CC9)
private val Cyan = Color(0xFF65DFE8)
private val Lunar = Color(0xFFECEAF6)
private val Muted = Color(0xFFBAB7CC)
private val Smoke = Color(0xF4161420)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AnytimeStore(this)
        ReminderScheduler.reschedule(this, store)
        setContent { MaterialTheme(colorScheme = darkColorScheme(
            primary = Cyan, secondary = Violet, tertiary = Magenta,
            background = Ink, surface = Smoke, onBackground = Lunar, onSurface = Lunar
        )) { AnytimeApp(store) } }
    }
}

@Composable
private fun AnytimeApp(store: AnytimeStore) {
    val context = LocalContext.current
    var page by remember { mutableStateOf("Today") }
    var revision by remember { mutableIntStateOf(0) }
    var moment by remember { mutableStateOf(Instant.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var onboarding by remember { mutableStateOf(!store.flag("onboarded")) }
    var error by remember { mutableStateOf("") }
    var notificationAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted ->
        notificationAllowed = granted
        if (granted) ReminderScheduler.reschedule(context, store)
    }
    val lifecycle = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            moment = Instant.now()
            delay(30_000)
        }
    }
    val calendar = remember(revision) { store.calendar() }
    val days = remember(revision, moment) { calendar.fromInstant(moment) }
    val moon = remember(moment) { MoonCalculator.at(moment) }
    val entries = remember(revision) { store.entries() }
    val events = remember(revision) { store.events() }
    val cycles = remember(revision) { store.cycles() }
    fun update(block: () -> Unit) {
        try { block(); revision++; error = "" } catch (e: Exception) { error = e.message ?: "Could not save" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
                ?: error("Unable to open export destination")
        } catch (e: Exception) { error = e.message ?: "Export failed" }
    }

    val background = if (store.flag("oled", true)) Ink else Color(0xFF0A0812)
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        val wide = maxWidth >= 980.dp
        val medium = maxWidth >= 670.dp
        val content: @Composable () -> Unit = {
            when (page) {
                "Today" -> TodayPage(store, calendar, days, moon, moment, events, entries, cycles,
                    selected, { selected = it; page = "Year" }, { page = it }, resumed)
                "Year" -> YearPage(calendar, selected, { selected = it })
                "Moon" -> MoonPage(moon, moment, entries)
                "Events" -> EventsPage(store, calendar, events, selected, notificationAllowed,
                    { if (Build.VERSION.SDK_INT >= 33) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    { update(it) })
                "Cycles" -> CyclesPage(store, cycles, selected, { update(it) })
                "Journal" -> JournalPage(store, calendar, entries, { update(it) })
                else -> SettingsPage(store, { update(it) }, { export.launch("Anytime-export.json") })
            }
        }
        if (medium) {
            Row(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
                Navigation(store, page, { page = it }, Modifier.width(if (wide) 180.dp else 128.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                if (wide && page != "Settings") {
                    Box(Modifier.width(300.dp).fillMaxHeight().padding(top = 12.dp, end = 12.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            MoonPanel(moon, compact = true)
                            Spacer(Modifier.height(12.dp))
                            SeasonPanel(store, moment)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(WindowInsets.safeDrawing.asPaddingValues())) {
                Box(Modifier.weight(1f)) { content() }
                Navigation(store, page, { page = it }, Modifier.fillMaxWidth(), horizontal = true)
            }
        }
        if (onboarding) Onboarding(store, { update(it) }, { onboarding = false })
        if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" },
            title = { Text("Something needs attention") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
    }
}

@Composable
private fun Navigation(store: AnytimeStore, page: String, go: (String) -> Unit,
                       modifier: Modifier, horizontal: Boolean = false) {
    val labels = listOf("Today", "Year", "Moon", "Events", "Cycles", "Journal", "Settings")
    val selectedColor = if (store.flag("oled", true)) Ink else Smoke
    if (horizontal) Row(modifier.background(selectedColor).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEach { label -> TextButton(onClick = { go(label) }) {
            Text(label, color = if (label == page) Cyan else Muted, fontSize = 12.sp,
                maxLines = 1)
        } }
    } else Column(modifier.background(selectedColor).padding(10.dp)) {
        Text("◌  ANYTIME", color = Lunar, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(vertical = 24.dp))
        labels.forEach { label -> TextButton(onClick = { go(label) }, Modifier.fillMaxWidth()) {
            Text(label, color = if (label == page) Cyan else Muted, maxLines = 1)
        } }
    }
}

@Composable
private fun TodayPage(store: AnytimeStore, calendar: AnytimeCalendar, day: AnytimeCalendar.Day,
    moon: MoonCalculator.State, moment: Instant, events: List<AnytimeEvent>, entries: List<AnytimeEntry>,
    cycles: List<AnytimeCycle>, selected: LocalDate, select: (LocalDate) -> Unit,
    go: (String) -> Unit, resumed: Boolean) {
    val primary = store.string("primary", "Anytime")
    val local = moment.atZone(ZoneId.systemDefault()).toLocalDate()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("WHAT IS TODAY FOR ME?", color = Muted, letterSpacing = 2.sp, fontSize = 11.sp)
        GlassPanel(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 168.dp)) {
                AmbientParticles(store.string("motion", "Balanced"), resumed,
                    Modifier.matchParentSize())
                Column(Modifier.padding(20.dp)) {
                    Text(if (primary == "Gregorian") local.toString() else if (primary == "Moon")
                        moon.phase().name.replace('_', ' ') else dayLabel(day),
                        color = Lunar, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    Text(if (primary == "Anytime") "CHAMBER ${day.chamber().takeIf { day.counted() } ?: "—"}  ·  YEAR ${day.year()}"
                        else dayLabel(day), color = Cyan, letterSpacing = 1.sp)
                    Spacer(Modifier.height(15.dp))
                    Text("Civil bridge · ${day.civilDate()} · ${calendar.rules().originZone().id}",
                        color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { select(day.civilDate()) }) { Text("Open this day →") }
                }
            }
        }
        if (store.flag("moon", true)) MoonPanel(moon, compact = true, onOpen = { go("Moon") })
        SeasonPanel(store, moment)
        GlassPanel {
            Section("Coming into view", "EVENTS")
            val upcoming = events.mapNotNull { event ->
                val next = OccurrenceResolver.nextAfter(event.instant, event.zone, event.recurrence,
                    calendar, moment.minusSeconds(1))
                if (next == null) null else next to event
            }.sortedBy { it.first }.take(3)
            if (upcoming.isEmpty()) Text("No upcoming events yet.", color = Muted)
            upcoming.forEach { (instant, event) ->
                Text("${instant.atZone(event.zone).toLocalDate()}  ·  ${event.title}", color = Lunar,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            TextButton(onClick = { go("Events") }) { Text("Plan an event →") }
        }
        if (store.flag("personal", true)) GlassPanel {
            Section("Your cycles", "PERSONAL")
            if (cycles.isEmpty()) Text("Add a cycle when you're ready.", color = Muted)
            cycles.take(3).forEach { Text("${it.title} · day ${
                Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(it.origin, local), it.period) + 1
            } of ${it.period}", color = Lunar, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        if (store.flag("numerology")) GlassPanel {
            Section("Civil date digit reduction", "OPTIONAL SYMBOLIC SYSTEM")
            val result = Numerology.civilDateDigitReduction(local)
            Text("${result.value()} · ${result.derivation()}", color = Magenta)
            Text("Symbolic computation · one named rule, no astronomical claim", color = Muted, fontSize = 11.sp)
        }
        GlassPanel {
            Section("Reflection", "JOURNAL")
            Text(entries.firstOrNull()?.text ?: "Your timeline begins here.", color = Muted,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { go("Journal") }) { Text("Write a reflection →") }
        }
    }
}

private fun dayLabel(day: AnytimeCalendar.Day): String = when (day.kind()) {
    AnytimeCalendar.Kind.COUNTED_DAY -> "Day ${day.day()} · Chamber ${day.chamber()}"
    AnytimeCalendar.Kind.THRESHOLD_DAY -> "Day Outside Time"
    AnytimeCalendar.Kind.DRIFT_DAY -> "Drift Day ${day.outsideIndex()}"
}

@Composable
private fun SeasonPanel(store: AnytimeStore, moment: Instant) {
    val solar = SolarCalculator.longitude(moment)
    val stage = when { solar < 90 -> "Spring"; solar < 180 -> "Summer";
        solar < 270 -> "Autumn"; else -> "Winter" }
    val origin = store.string("originZone", ZoneId.systemDefault().id)
    val present = ZoneId.systemDefault().id
    fun daylight(key: String): String = store.string(key).toDoubleOrNull()?.let { lat ->
        if (lat in -90.0..90.0) " · ~%.1f h daylight".format(SolarCalculator.daylightHours(lat, moment)) else ""
    } ?: ""
    fun stageAt(key: String): String = if ((store.string(key).toDoubleOrNull() ?: 1.0) < 0)
        when (stage) { "Spring" -> "Autumn"; "Summer" -> "Winter";
            "Autumn" -> "Spring"; else -> "Summer" } else stage
    GlassPanel {
        Section("Two horizons", "ASTRONOMICAL SEASON")
        Text("Origin · ${stageAt("originLatitude")} · $origin${daylight("originLatitude")}", color = Lunar)
        Spacer(Modifier.height(5.dp))
        Text("Present · ${stageAt("presentLatitude")} · $present${daylight("presentLatitude")}", color = Cyan)
        Text("Daylight estimates need a latitude. Present place is set manually.",
            color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun MoonPanel(state: MoonCalculator.State, compact: Boolean, onOpen: (() -> Unit)? = null) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Section(if (compact) "The living Moon" else "The lunar current", "ASTRONOMY")
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonDisc(state.longitudeDifference(), Modifier.size(if (compact) 84.dp else 174.dp))
            Column(Modifier.padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(state.phase().name.replace('_', ' '), color = Lunar, fontWeight = FontWeight.Medium)
                Text("%.0f%% illuminated · %s".format(state.illumination() * 100,
                    if (state.waxing()) "waxing" else "waning"), color = Cyan)
                Text("Age ~%.1f days".format(state.ageDays()), color = Muted, fontSize = 12.sp)
            }
        }
        Text("Next ${state.next().phase().name.replace('_', ' ')} · ${
            state.next().instant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
        }", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        if (onOpen != null) TextButton(onClick = onOpen) { Text("Explore Moon →") }
    }
}

@Composable
private fun MoonPage(state: MoonCalculator.State, now: Instant, entries: List<AnytimeEntry>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MOON", color = Lunar, fontSize = 28.sp, letterSpacing = 2.sp)
        MoonPanel(state, compact = false)
        GlassPanel {
            Section("Phase passage", "UTC CALCULATION · LOCAL DISPLAY")
            Text("Previous · ${state.previous().phase().name.replace('_', ' ')} · ${
                state.previous().instant().atZone(ZoneId.systemDefault())}", color = Muted)
            Text("Next · ${state.next().phase().name.replace('_', ' ')} · ${
                state.next().instant().atZone(ZoneId.systemDefault())}", color = Cyan)
            Text("Approximate geocentric phase calculation; major events within a four-hour reference tolerance.",
                fontSize = 11.sp, color = Muted)
        }
        GlassPanel {
            Section("Reflections nearby", "PERSONAL")
            val nearby = entries.filter { abs(Duration.between(it.instant, now).toDays()) <= 15 }
            if (nearby.isEmpty()) Text("No reflections near this lunar passage yet.", color = Muted)
            nearby.take(4).forEach { Text(it.text, maxLines = 2, color = Lunar,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
}

@Composable
private fun MoonDisc(angle: Double, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Astronomical Moon, %.0f percent illuminated".format(
        (1 - cos(Math.toRadians(angle))) * 50) }) {
        val r = size.minDimension * .43f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF211C2D), r, center)
        val c = cos(Math.toRadians(angle)).toFloat()
        var y = -r
        while (y <= r) {
            val span = sqrt(max(0f, r * r - y * y))
            val left = if (angle < 180) span * c else -span
            val right = if (angle < 180) span else -span * c
            if (right > left) drawLine(Lunar, Offset(center.x + left, center.y + y),
                Offset(center.x + right, center.y + y), strokeWidth = 1.5f)
            y += 1.5f
        }
        drawCircle(Violet.copy(alpha = .8f), r, center, style = Stroke(1.5f))
    }
}

@Composable
private fun YearPage(calendar: AnytimeCalendar, date: LocalDate, select: (LocalDate) -> Unit) {
    val current = remember(calendar, date) { calendar.fromCivil(date) }
    val days = remember(calendar, date) { calendar.year(current.year()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { select(calendar.start(current.year() - 1)) }) { Text("←") }
            Text("YEAR ${current.year()}", color = Lunar, fontSize = 25.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { select(calendar.start(current.year() + 1)) }) { Text("→") }
        }
        GlassPanel {
            YearRing(current, { index -> select(days.filter(AnytimeCalendar.Day::counted)[index].civilDate()) })
            Text("${dayLabel(current)} · ${date}", color = Cyan,
                modifier = Modifier.padding(top = 10.dp))
        }
        Text("13 CHAMBERS · 28 COUNTED DAYS EACH", color = Muted, fontSize = 11.sp)
        for (chamber in 1..13) {
            GlassPanel {
                Text("CHAMBER $chamber", color = Violet, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                for (week in 0..3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (weekday in 1..7) {
                            val n = week * 7 + weekday
                            val civil = calendar.toCivil(current.year(), chamber, n)
                            val chosen = civil == date
                            Box(Modifier.sizeIn(minWidth = 39.dp, minHeight = 42.dp).clip(CircleShape)
                                .background(if (chosen) Violet.copy(alpha = .35f) else Color.Transparent)
                                .clickable { select(civil) }, contentAlignment = Alignment.Center) {
                                Text(n.toString(), color = if (chosen) Lunar else Muted)
                            }
                        }
                    }
                }
            }
        }
        days.filter { !it.counted() }.forEach { outside -> GlassPanel {
            Text("${dayLabel(outside)} · ${outside.civilDate()}", color = Magenta)
        } }
    }
}

@Composable
private fun YearRing(day: AnytimeCalendar.Day, selectIndex: (Int) -> Unit) {
    Canvas(Modifier.fillMaxWidth().height(280.dp)
        .semantics { contentDescription = "13-chamber radial year; accessible day grid follows" }
        .pointerInput(day.year()) {
            detectTapGestures { point ->
                val dx = point.x - size.width / 2f
                val dy = point.y - size.height / 2f
                val radius = min(size.width, size.height) * .43f
                val distance = hypot(dx, dy)
                if (distance in radius * .58f..radius * 1.08f) {
                    val fraction = ((atan2(dy, dx) + PI / 2 + 2 * PI) % (2 * PI)) / (2 * PI)
                    selectIndex((fraction * 364).toInt().coerceIn(0, 363))
                }
            }
        }) {
        val radius = min(size.width, size.height) * .43f
        val topLeft = Offset(size.width / 2 - radius, size.height / 2 - radius)
        val diameter = Size(radius * 2, radius * 2)
        for (i in 0..12) {
            drawArc(Violet.copy(alpha = .35f), -90f + i * 360f / 13, 360f / 13 - 1.5f,
                false, topLeft, diameter, style = Stroke(18.dp.toPx(), cap = StrokeCap.Round))
        }
        val progress = if (day.counted()) ((day.chamber() - 1) * 28 + day.day()) / 364f else 1f
        drawArc(Cyan, -90f, progress * 360f, false, topLeft, diameter,
            style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Magenta, 4.dp.toPx(), Offset(size.width / 2, size.height / 2 - radius))
        drawCircle(Color(0xFF191224), radius * .51f)
    }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, body: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Smoke)
        .border(1.dp, Violet.copy(alpha = .23f), RoundedCornerShape(20.dp)).padding(16.dp),
        content = body)
}

@Composable
private fun Section(title: String, eyebrow: String) {
    Text(eyebrow, color = Magenta, fontSize = 10.sp, letterSpacing = 2.sp)
    Text(title, color = Lunar, fontSize = 18.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun AmbientParticles(motion: String, resumed: Boolean, modifier: Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    val enabled = resumed && motion != "Reduced Motion" && motion != "Battery Saver"
    LaunchedEffect(enabled) {
        while (enabled) { time += 0.002f; delay(90) }
    }
    Canvas(modifier) {
        val count = if (motion == "Maximum") 32 else 13
        repeat(count) { index ->
            val x = ((index * .6180339f + time * (1 + index % 3)) % 1f) * size.width
            val y = ((index * .371f + time * .25f) % 1f) * size.height
            drawCircle(if (index % 3 == 0) Cyan else Violet,
                radius = if (index % 5 == 0) 1.8.dp.toPx() else 1.dp.toPx(),
                center = Offset(x, y), alpha = .32f)
        }
    }
}

@Composable
private fun EventsPage(store: AnytimeStore, calendar: AnytimeCalendar, events: List<AnytimeEvent>,
                       selected: LocalDate, notificationAllowed: Boolean, askPermission: () -> Unit,
                       update: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AnytimeEvent?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("EVENTS", color = Lunar, fontSize = 28.sp, letterSpacing = 2.sp)
        Text("Plans live on an instant; each time system gives them a different reading.", color = Muted)
        Button(onClick = { editing = true }) { Text("+  Create event") }
        if (events.isEmpty()) GlassPanel { Text("No events yet. Choose a moment to begin.", color = Muted) }
        events.forEach { event ->
            GlassPanel {
                val civil = event.instant.atZone(event.zone).toLocalDate()
                val sacred = calendar.fromInstant(event.instant)
                Text(event.title, color = Lunar, fontSize = 18.sp)
                Text("${civil} · ${if (event.allDay) "all day" else event.instant.atZone(event.zone).toLocalTime()} · ${event.zone.id}",
                    color = Cyan, fontSize = 12.sp)
                Text("${dayLabel(sacred)} · ${event.recurrence.name.replace('_', ' ')}", color = Muted, fontSize = 12.sp)
                if (event.reminderMinutes > 0) Text("Reminder · ${event.reminderMinutes} minutes before (inexact)",
                    color = Muted, fontSize = 12.sp)
                if (event.description.isNotBlank()) Text(event.description, color = Lunar)
                TextButton(onClick = { removing = event }) { Text("Remove", color = Magenta) }
            }
        }
    }
    if (editing) EventDialog(store, selected, notificationAllowed, askPermission,
        { editing = false }, update)
    removing?.let { target -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove event?") }, text = { Text(target.title) },
        confirmButton = { TextButton(onClick = {
            update { ReminderScheduler.cancel(context, target.id); store.removeEvent(target.id) }; removing = null
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

@Composable
private fun EventDialog(store: AnytimeStore, selected: LocalDate, notificationAllowed: Boolean,
                        askPermission: () -> Unit, close: () -> Unit,
                        update: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(selected.toString()) }
    var time by remember { mutableStateOf("18:00") }
    var allDay by remember { mutableStateOf(false) }
    var rule by remember { mutableStateOf(Recurrence.Rule.NONE) }
    var reminder by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("New event") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(description, { description = it }, label = { Text("Details (optional)") },
                minLines = 2, maxLines = 4)
            OutlinedTextField(date, { date = it }, label = { Text("Civil date · YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(time, { time = it }, label = { Text("Time · HH:mm") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allDay, { allDay = it }); Text("All day")
            }
            Text("Recurrence", color = Muted)
            listOf(Recurrence.Rule.NONE, Recurrence.Rule.ANYTIME_MONTHLY,
                Recurrence.Rule.ANYTIME_YEARLY, Recurrence.Rule.GREGORIAN_YEARLY).forEach { item ->
                TextButton(onClick = { rule = item }) {
                    Text("${if (rule == item) "◉" else "○"}  ${item.name.replace('_', ' ')}")
                }
            }
            Text("Reminder · may be delayed by Android battery policy", color = Muted)
            OptionRow(listOf("None", "10 min", "30 min", "1 hour", "1 day"),
                when (reminder) { 10 -> "10 min"; 30 -> "30 min"; 60 -> "1 hour";
                    1440 -> "1 day"; else -> "None" }) { option ->
                reminder = when (option) { "10 min" -> 10; "30 min" -> 30;
                    "1 hour" -> 60; "1 day" -> 1440; else -> 0 }
                if (reminder > 0 && !notificationAllowed) askPermission()
            }
            if (problem.isNotBlank()) Text(problem, color = Magenta)
        } },
        confirmButton = { TextButton(onClick = {
            try {
                require(reminder == 0 || notificationAllowed) { "Allow notifications or select no reminder" }
                val zone = ZoneId.systemDefault()
                val day = LocalDate.parse(date)
                val clock = if (allDay) LocalTime.NOON else LocalTime.parse(time)
                update {
                    store.addEvent(title, description, day.atTime(clock).atZone(zone), allDay, rule, reminder)
                    ReminderScheduler.reschedule(context, store)
                }
                close()
            } catch (e: Exception) { problem = e.message ?: "Check the date and time" }
        }) { Text("Save event") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun JournalPage(store: AnytimeStore, calendar: AnytimeCalendar,
                        entries: List<AnytimeEntry>, update: (() -> Unit) -> Unit) {
    var writing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AnytimeEntry?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("JOURNAL", color = Lunar, fontSize = 28.sp, letterSpacing = 2.sp)
        Button(onClick = { writing = true }) { Text("+  Write a reflection") }
        if (entries.isEmpty()) GlassPanel { Text("Your timeline begins here.", color = Muted) }
        entries.forEach { entry -> GlassPanel {
            Text("${dayLabel(calendar.fromInstant(entry.instant))} · ${
                entry.instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"))
            }", color = Cyan, fontSize = 12.sp)
            Text(entry.text, color = Lunar, modifier = Modifier.padding(vertical = 8.dp))
            if (entry.tags.isNotBlank()) Text(entry.tags, color = Violet)
            TextButton(onClick = { removing = entry }) { Text("Remove", color = Magenta) }
        } }
    }
    if (writing) {
        var text by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { writing = false }, title = { Text("A moment in time") },
            text = { Column {
                OutlinedTextField(text, { text = it }, label = { Text("Reflection") }, minLines = 5)
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (optional)") })
            } }, confirmButton = { TextButton(onClick = {
                update { store.addEntry(text, tags) }; writing = false
            }, enabled = text.isNotBlank()) { Text("Keep this moment") } },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("Cancel") } })
    }
    removing?.let { target -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove reflection?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            update { store.removeEntry(target.id) }; removing = null
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

@Composable
private fun CyclesPage(store: AnytimeStore, cycles: List<AnytimeCycle>, day: LocalDate,
                       update: (() -> Unit) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AnytimeCycle?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CYCLES", color = Lunar, fontSize = 28.sp, letterSpacing = 2.sp)
        Text("Your observations give a cycle meaning. Its position is simply calculated.", color = Muted)
        Button(onClick = { editing = true }) { Text("+  Create personal cycle") }
        if (cycles.isEmpty()) GlassPanel { Text("Add a cycle when you're ready.", color = Muted) }
        cycles.forEach { cycle -> GlassPanel {
            val position = Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(cycle.origin, day), cycle.period) + 1
            Text(cycle.title, color = Lunar, fontSize = 19.sp)
            LinearProgressIndicator(progress = { position.toFloat() / cycle.period }, Modifier.fillMaxWidth(),
                color = Cyan, trackColor = Violet.copy(alpha = .18f))
            Text("Day $position of ${cycle.period} · origin ${cycle.origin}", color = Cyan)
            TextButton(onClick = { removing = cycle }) { Text("Remove", color = Magenta) }
        } }
    }
    if (editing) {
        var title by remember { mutableStateOf("") }
        var origin by remember { mutableStateOf(day.toString()) }
        var period by remember { mutableStateOf("28") }
        var problem by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text("New personal cycle") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") })
                OutlinedTextField(origin, { origin = it }, label = { Text("Starting civil date · YYYY-MM-DD") })
                OutlinedTextField(period, { period = it }, label = { Text("Length in days") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (problem.isNotBlank()) Text(problem, color = Magenta)
            } }, confirmButton = { TextButton(onClick = {
                try { update { store.addCycle(title, LocalDate.parse(origin), period.toInt()) }; editing = false }
                catch (e: Exception) { problem = e.message ?: "Check cycle values" }
            }) { Text("Save cycle") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    }
    removing?.let { target -> AlertDialog(onDismissRequest = { removing = null },
        title = { Text("Remove cycle?") }, text = { Text(target.title) },
        confirmButton = { TextButton(onClick = {
            update { store.removeCycle(target.id) }; removing = null
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

@Composable
private fun SettingsPage(store: AnytimeStore, update: (() -> Unit) -> Unit, export: () -> Unit) {
    var origin by remember { mutableStateOf(store.string("originZone", ZoneId.systemDefault().id)) }
    var birth by remember { mutableStateOf(store.birthMonthDay()?.let {
        "%02d-%02d".format(it.monthValue, it.dayOfMonth)
    } ?: "") }
    var fixed by remember { mutableStateOf(store.string("fixedBoundary", "--03-20").removePrefix("--")) }
    var originLat by remember { mutableStateOf(store.string("originLatitude")) }
    var presentLat by remember { mutableStateOf(store.string("presentLatitude")) }
    var issue by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("SETTINGS", color = Lunar, fontSize = 28.sp, letterSpacing = 2.sp)
        GlassPanel {
            Section("How time appears", "CALENDAR SYSTEMS")
            Text("Primary representation", color = Muted)
            OptionRow(listOf("Anytime", "Gregorian", "Moon"), store.string("primary", "Anytime")) {
                update { store.set("primary", it) }
            }
            ToggleSetting("Moon layer", store.flag("moon", true)) { update { store.set("moon", it) } }
            ToggleSetting("Personal cycles", store.flag("personal", true)) { update { store.set("personal", it) } }
            ToggleSetting("Date digit reduction · symbolic", store.flag("numerology")) {
                update { store.set("numerology", it) }
            }
            Text("Astrological interpretations can be added as a separately sourced system.",
                color = Muted, fontSize = 11.sp)
        }
        GlassPanel {
            Section("Where your year begins", "ORIGIN & BOUNDARY")
            OutlinedTextField(origin, { origin = it }, label = { Text("Origin IANA zone") },
                supportingText = { Text("For example: Europe/Vienna") })
            Text("Year boundary", color = Muted)
            OptionRow(listOf("NORTHERN_SPRING_EQUINOX", "FIXED_DATE"),
                store.string("boundary", "NORTHERN_SPRING_EQUINOX")) { update { store.set("boundary", it) } }
            OutlinedTextField(fixed, { fixed = it }, label = { Text("Fixed boundary · MM-DD") })
            OutlinedTextField(birth, { birth = it }, label = { Text("Day Outside Time · MM-DD (optional)") },
                supportingText = { Text("Your date is encrypted on this device; blank uses the last day of the year.") })
            Text("February 29 anniversary in ordinary years", color = Muted)
            OptionRow(listOf("FEBRUARY_28", "MARCH_1"), store.string("leapBirthday", "FEBRUARY_28")) {
                update { store.set("leapBirthday", it) }
            }
            Button(onClick = {
                try {
                    val validZone = ZoneId.of(origin.trim())
                    val validFixed = MonthDay.parse("--${fixed.trim()}")
                    require(validFixed != MonthDay.of(2, 29)) { "Choose a fixed date other than February 29" }
                    val validBirthday = birth.trim().takeIf(String::isNotBlank)?.let { MonthDay.parse("--$it") }
                    update {
                        store.set("originZone", validZone.id)
                        store.set("fixedBoundary", validFixed.toString())
                        store.setBirthMonthDay(validBirthday)
                    }
                    issue = ""
                } catch (e: Exception) { issue = e.message ?: "Check your date and time zone" }
            }) { Text("Save origin") }
            if (issue.isNotBlank()) Text(issue, color = Magenta)
        }
        GlassPanel {
            Section("Two horizons", "MANUAL LOCATION · OPTIONAL")
            Text("Latitude allows approximate daylight duration. A zone alone gives seasonal timing.",
                color = Muted, fontSize = 12.sp)
            OutlinedTextField(originLat, { originLat = it }, label = { Text("Origin latitude") })
            OutlinedTextField(presentLat, { presentLat = it }, label = { Text("Present latitude") })
            Button(onClick = {
                try {
                    require(listOf(originLat, presentLat).all { raw ->
                        raw.isBlank() || raw.toDoubleOrNull()?.let { it in -90.0..90.0 } == true
                    }) {
                        "Latitude must be between −90 and 90"
                    }
                    update { store.set("originLatitude", originLat.trim()); store.set("presentLatitude", presentLat.trim()) }
                } catch (e: Exception) { issue = e.message ?: "Check latitude" }
            }) { Text("Save horizons") }
        }
        GlassPanel {
            Section("Light and stillness", "APPEARANCE")
            ToggleSetting("True OLED black", store.flag("oled", true)) { update { store.set("oled", it) } }
            Text("Ambient motion", color = Muted)
            OptionRow(listOf("Maximum", "Balanced", "Battery Saver", "Reduced Motion"),
                store.string("motion", "Balanced")) { update { store.set("motion", it) } }
        }
        GlassPanel {
            Section("Take your time with you", "DATA & PRIVACY")
            Text("Export a readable JSON archive to a location you select. It contains plaintext events, reflections and any birth date you provided.",
                color = Muted)
            Button(onClick = export) { Text("Export my data") }
            Text("Local-only · No analytics · No AniCloud access", color = Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Lunar, modifier = Modifier.weight(1f))
        Switch(checked, change)
    }
}

@Composable
private fun OptionRow(options: List<String>, current: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            FilterChip(selected = current == option, onClick = { change(option) },
                label = { Text(option.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() },
                    maxLines = 1) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
}

@Composable
private fun Onboarding(store: AnytimeStore, update: (() -> Unit) -> Unit, finish: () -> Unit) {
    var primary by remember { mutableStateOf(store.string("primary", "Anytime")) }
    var moon by remember { mutableStateOf(true) }
    var personal by remember { mutableStateOf(true) }
    var symbolic by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var outside by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = {}, title = { Text("ANYTIME", color = Lunar, letterSpacing = 3.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Your time.", color = Cyan, fontSize = 24.sp)
            Text("How would you like to experience it?", color = Muted)
            Spacer(Modifier.height(10.dp))
            Text("PRIMARY", color = Magenta, letterSpacing = 2.sp)
            OptionRow(listOf("Anytime", "Gregorian", "Moon"), primary) { primary = it }
            Text("Anytime follows 13 chambers of 28 days. The Moon follows its own real cycle.",
                color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("LAYERS", color = Magenta, letterSpacing = 2.sp)
            ToggleSetting("Moon phases", moon) { moon = it }
            ToggleSetting("Personal cycles", personal) { personal = it }
            ToggleSetting("Date digit reduction · symbolic", symbolic) { symbolic = it }
            Text("Gregorian dates remain available for appointments and export.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Text("YOUR ORIGIN · OPTIONAL", color = Magenta, letterSpacing = 2.sp)
            OutlinedTextField(origin, { origin = it }, label = { Text("Origin time zone") },
                supportingText = { Text("An IANA zone, such as Europe/London") })
            OutlinedTextField(outside, { outside = it }, label = { Text("Day Outside Time · MM-DD") },
                supportingText = { Text("Leave blank and choose later; stored encrypted on device.") })
            if (issue.isNotBlank()) Text(issue, color = Magenta)
        } },
        confirmButton = { TextButton(onClick = {
            try {
                val zone = ZoneId.of(origin.trim())
                val day = outside.trim().takeIf(String::isNotBlank)?.let { MonthDay.parse("--$it") }
                update {
                    store.set("originZone", zone.id); store.setBirthMonthDay(day)
                    store.set("primary", primary); store.set("moon", moon)
                    store.set("personal", personal); store.set("numerology", symbolic)
                    store.set("onboarded", true)
                }
                finish()
            } catch (e: Exception) {
                issue = e.message ?: "Check the origin zone and date"
            }
        }) { Text("Begin →") } })
}
