package dev.anicloud.sovereign.prototype

const val StoryForgeMissionKind = "story_forge"
const val WorkspaceMissionKind = "workspace"
const val StoryForgeTargetChapters = 120

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
