package dev.anicloud.sovereign.prototype

import android.content.Context

class FoundationPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "anicloud_foundation_preferences",
        Context.MODE_PRIVATE,
    )

    fun layoutForDisplay(displayId: Int): LayoutPreference = enumValueOrDefault(
        preferences.getString("layout_$displayId", null),
        LayoutPreference.Automatic,
    )

    fun setLayoutForDisplay(displayId: Int, value: LayoutPreference) {
        preferences.edit().putString("layout_$displayId", value.name).apply()
    }

    fun appearance(): Appearance = enumValueOrDefault(
        preferences.getString("appearance", null),
        Appearance.Obsidian,
    )

    fun setAppearance(value: Appearance) {
        preferences.edit().putString("appearance", value.name).apply()
    }

    fun defaultAnswerMode(): AnswerMode = enumValueOrDefault(
        preferences.getString("answer_mode", null),
        AnswerMode.Adaptive,
    )

    fun setDefaultAnswerMode(value: AnswerMode) {
        preferences.edit().putString("answer_mode", value.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        fallback: T,
    ): T = runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
}
