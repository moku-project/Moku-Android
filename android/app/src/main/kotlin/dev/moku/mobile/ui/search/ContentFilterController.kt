package dev.moku.mobile.ui.search

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

private const val PREFS_NAME = "moku_content_filter_prefs"
private const val KEY_LEVEL = "block_level"

/** The global filter level (0 off, 1 moderate, 2 strict) that decides which content-filter rules apply. */
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
