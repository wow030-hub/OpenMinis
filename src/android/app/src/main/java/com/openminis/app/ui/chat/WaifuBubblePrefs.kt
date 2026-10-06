package com.openminis.app.ui.chat

import android.content.Context

/**
 * [T-waifu-bubble] SharedPreferences-backed toggle for Waifu mode (multi-bubble
 * rendering). When enabled, frozen assistant text blocks are split into
 * per-sentence bubbles, mimicking Operit's WaifuMessageProcessor effect.
 */
object WaifuBubblePrefs {
    private const val PREFS_NAME = "waifu_bubble_prefs"
    private const val KEY_ENABLED = "waifu_bubble_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
