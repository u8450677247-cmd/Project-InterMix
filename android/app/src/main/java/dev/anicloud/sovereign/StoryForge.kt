package dev.anicloud.sovereign.prototype

const val StoryForgeMissionKind = "story_forge"
const val WorkspaceMissionKind = "workspace"
const val StoryForgeTargetChapters = 120
const val StoryForgeBenchmarkFolder = "story-forge-orbit"

val StoryForgeBenchmarkPremise = """
    Write a cohesive atmospheric science-fantasy novel about Nia Sol, a maintenance apprentice in
    the floating city of Vesper, and Lumen, an alien archive intelligence that wakes inside a
    broken weather instrument. Begin when metallic rain makes forgotten memories audible across
    the city. The city survives by trading carefully edited memories for energy, but the exchange
    is slowly erasing its own founding disaster. Nia wants to recover the truth about her missing
    mother; Lumen wants to understand whether preserving every memory can itself become a form of
    harm.

    Keep these world rules stable: Vesper hangs beneath three silent orbital rings; memory rain can
    reveal an existing memory but cannot invent one; Lumen can communicate through light, sound,
    and machines but cannot directly control a human body; using the archive at high intensity
    permanently changes one sensory detail in the local environment; death is irreversible. Let
    consequences accumulate.

    Develop a patient relationship from suspicion to earned trust. Maintain a recurring brass moth,
    a cracked blue compass, and the phrase “the sky keeps receipts,” allowing each to change meaning
    through the story. Give supporting characters independent motives, especially engineer Mara
    Venn, union courier Ivo, and civic archivist Saint Orra. Seed mysteries before resolving them,
    preserve injuries and promises, vary quiet and kinetic scenes, and avoid recap-heavy openings.
    Each installment should be a substantial scene with concrete action, sensory detail, conflict,
    and a changed situation.

    Build toward Nia discovering that her mother voluntarily became part of the weather archive to
    prevent the city from repeating its founding catastrophe. The ending must force Nia and Lumen
    to choose between perfect public recall and a limited, consent-based archive. Resolve the
    central choice and emotional arc while leaving one honest sign that Vesper's wider world
    continues. Do not use meta commentary, chapter numbers, benchmark language, controller
    language, or claims about how much remains.
""".trimIndent()

/** The reviewed endurance fixture owns one exact top-level folder. */
fun isReservedStoryForgeBenchmarkRoot(rawPath: String): Boolean = runCatching {
    normalizeWorkspacePath(rawPath).equals(StoryForgeBenchmarkFolder, ignoreCase = true)
}.getOrDefault(false)

private const val MaximumStoryTitleCharacters = 120
private const val MinimumStoryBodyCharacters = 240
private const val MaximumStoryBodyCharacters = 8_000
private const val MaximumStoryContinuityCharacters = 1_200

data class StoryChapterProposal(
    val title: String,
    val body: String,
    val continuity: String,
)

data class StoryChapterCommit(
    val ordinal: Int,
    val chapterBytes: Long,
    val totalFileBytes: Long,
    val alreadyCommitted: Boolean,
    val committedTitle: String,
    val committedBody: String,
    val committedContinuity: String,
    val detail: String,
)

/**
 * The model supplies prose only. Android owns the ordinal, filesystem append, idempotency marker,
 * durable checkpoint, and exact completion boundary.
 */
fun validateStoryChapterProposal(raw: StoryChapterProposal): StoryChapterProposal {
    val title = raw.title.replace("\u0000", "").trim()
    val body = raw.body.replace("\u0000", "").trim()
    val continuity = raw.continuity.replace("\u0000", "").trim()
    require(title.isNotBlank()) { "A private Story Forge chapter needs a title." }
    require('\n' !in title && '\r' !in title) { "Story Forge titles use one line." }
    require(title.length <= MaximumStoryTitleCharacters) {
        "Story Forge titles are limited to $MaximumStoryTitleCharacters characters."
    }
    require(body.length >= MinimumStoryBodyCharacters) {
        "A Story Forge chapter needs at least $MinimumStoryBodyCharacters characters of prose."
    }
    require(body.length <= MaximumStoryBodyCharacters) {
        "Story Forge chapters are limited to $MaximumStoryBodyCharacters characters."
    }
    require(continuity.isNotBlank()) { "A private continuity capsule is required." }
    require(continuity.length <= MaximumStoryContinuityCharacters) {
        "Story Forge continuity is limited to $MaximumStoryContinuityCharacters characters."
    }
    val combined = "$title\n$body\n$continuity".uppercase()
    require("<INTERMIX_" !in combined) { "Controller protocol cannot enter story prose." }
    require("ANICLOUD_CHAPTER:" !in combined) { "Chapter markers belong to Android." }
    return StoryChapterProposal(title = title, body = body, continuity = continuity)
}

fun storyChapterMarker(ordinal: Int): String {
    require(ordinal in 1..StoryForgeTargetChapters) { "Story chapter ordinal is outside the benchmark." }
    return "<!-- ANICLOUD_CHAPTER:${ordinal.toString().padStart(3, '0')} -->"
}
