package dev.moku.mobile.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "moku_content_filter_prefs"
private const val KEY_LEVEL = "block_level"

/**
 * The active content-filter enforcement level (0=off, 1=moderate, 2=strict), applied to Search
 * results via [dev.moku.mobile.backend.FilteredContentBackend]. Separate from the individual
 * [dev.moku.mobile.library.ContentFilterRule] rows (each rule carries its own block level; this
 * is the global dial that decides how many of them actually trip).
 */
object ContentFilterController {
    var level by mutableIntStateOf(1)
        private set

    fun init(context: Context) {
        level = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_LEVEL, 1)
    }

    fun setLevel(context: Context, newLevel: Int) {
        level = newLevel
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_LEVEL, newLevel).apply()
    }
}
