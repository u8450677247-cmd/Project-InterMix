package dev.anicloud.sovereign.prototype

const val ComposerMaxVisibleLines = 7
const val AuthenticationGraceMillis = 30_000L
// The fixed desktop cockpit reserves 240 dp for navigation and 320 dp for
// System Lens. It therefore activates at Android's large-window breakpoint,
// not at the ordinary 840 dp expanded breakpoint where chat would be squeezed.
const val DesktopThresholdDp = 1200

enum class AnswerMode(val label: String, val description: String) {
    Performance("Performance", "Prefer the fast conversational model"),
    Adaptive("Adaptive", "Route each response by measured need"),
    Quality("Quality", "Prefer the deeper reasoning model"),
}

enum class Appearance(val label: String) {
    Obsidian("Sovereign Obsidian"),
    System("System"),
    Light("Light"),
}

enum class LayoutPreference(val label: String) {
    Automatic("Automatic"),
    Phone("Phone"),
    Desktop("Desktop"),
}

enum class FoundationLayout {
    Phone,
    Desktop,
}

enum class Destination(val label: String) {
    Home("Home"),
    Chat("Chat"),
    Agents("Agents"),
    System("System"),
}

enum class RuntimePhase(val label: String) {
    Ready("Ready"),
    Recalling("Recalling"),
    Grounding("Grounding"),
    Reasoning("Reasoning"),
    Verifying("Verifying"),
    Recovering("Recovering"),
    Degraded("Degraded"),
    Offline("Offline"),
}

data class AnswerModeSelection(
    val defaultMode: AnswerMode = AnswerMode.Adaptive,
    val oneResponseOverride: AnswerMode? = null,
) {
    fun modeForNextResponse(): AnswerMode = oneResponseOverride ?: defaultMode

    fun afterResponse(): AnswerModeSelection = copy(oneResponseOverride = null)
}

fun resolveFoundationLayout(
    widthDp: Int,
    preference: LayoutPreference,
): FoundationLayout = when (preference) {
    LayoutPreference.Phone -> FoundationLayout.Phone
    LayoutPreference.Desktop -> FoundationLayout.Desktop
    LayoutPreference.Automatic -> if (widthDp >= DesktopThresholdDp) {
        FoundationLayout.Desktop
    } else {
        FoundationLayout.Phone
    }
}

/** Pure state holder so the 30-second rule can be tested without Android. */
class AuthenticationGrace(
    private val graceMillis: Long = AuthenticationGraceMillis,
) {
    private var authenticated = false
    private var backgroundedAtMillis: Long? = null

    fun markAuthenticated() {
        authenticated = true
        backgroundedAtMillis = null
    }

    fun markBackgrounded(nowMillis: Long) {
        if (authenticated) backgroundedAtMillis = nowMillis
    }

    fun lock() {
        authenticated = false
        backgroundedAtMillis = null
    }

    fun needsAuthentication(nowMillis: Long): Boolean {
        if (!authenticated) return true
        val backgroundedAt = backgroundedAtMillis ?: return false
        return nowMillis - backgroundedAt > graceMillis
    }
}
