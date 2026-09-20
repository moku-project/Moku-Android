package dev.moku.mobile.ui.home

import android.content.Context
import android.util.Log
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.LocalLibraryRepository
import dev.moku.mobile.library.ReadingProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class ContinueReadingItem(val media: LibraryMediaEntity, val progress: Float, val unitLabel: String)

/** One cell in the "last 7 days" strip — [hasActivity] drives which cells render highlighted. */
data class ActivityDay(val label: String, val hasActivity: Boolean, val isToday: Boolean)

/** What [HomeScreen] renders — a stats dashboard over the on-device library DB, no mock content. */
data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val continueReading: List<ContinueReadingItem> = emptyList(),
    val streakDays: Int = 0,
    val totalChapters: Int = 0,
    val readTimeSeconds: Long = 0,
    val chaptersThisWeek: Int = 0,
    val activity: List<ActivityDay> = emptyList(),
    val upNext: ContinueReadingItem? = null,
)

/**
 * Loads the Home dashboard entirely from the on-device library DB ([LocalLibraryRepository]) —
 * streak/chapter/activity stats are real rollups over [ReadingProgressEntity] rows, not
 * placeholders. An empty library legitimately produces all-zero stats, same as the reference
 * design's "Nothing here yet" state — this loader doesn't fabricate activity to fill the UI.
 */
object HomeDataLoader {
    private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
    private val WEEKDAY_FORMAT = SimpleDateFormat("EEEEE", Locale.US).apply { timeZone = TimeZone.getDefault() }

    suspend fun load(context: Context): HomeState = withContext(Dispatchers.IO) {
        try {
            val repo = LocalLibraryRepository(context)
            val library = repo.getLibrary()
            val allProgress = repo.getProgressSince(0L)

            val continueReading = library
                .filter { it.lastViewedAt != null }
                .sortedByDescending { it.lastViewedAt }
                .take(10)
                .map { media -> toContinueReadingItem(media, allProgress) }

            val completed = allProgress.filter { it.completed }
            val distinctDays = allProgress.map { DAY_FORMAT.format(it.updatedAt) }.toSet()

            val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val thisWeekCount = completed.count { it.updatedAt >= sevenDaysAgo }
            val readTimeSeconds = completed.sumOf { (it.durationSeconds ?: 0.0).toLong() }

            val upNextMedia = library
                .filter { it.lastViewedAt != null }
                .sortedByDescending { it.lastViewedAt }
                .firstOrNull { media -> repo.nextUnread(media.originId, media.mediaId) != null }
            val upNext = upNextMedia?.let { toContinueReadingItem(it, allProgress) }

            HomeState(
                loading = false,
                continueReading = continueReading,
                streakDays = currentStreak(distinctDays),
                totalChapters = completed.size,
                readTimeSeconds = readTimeSeconds,
                chaptersThisWeek = thisWeekCount,
                activity = lastSevenDays(distinctDays),
                upNext = upNext,
            )
        } catch (t: Throwable) {
            Log.d("HomeDataLoader", "load failed: ${t.javaClass.simpleName}: ${t.message}", t)
            HomeState(loading = false, error = "Check your connection and try again.")
        }
    }

    private fun toContinueReadingItem(media: LibraryMediaEntity, allProgress: List<ReadingProgressEntity>): ContinueReadingItem {
        val latest = allProgress
            .filter { it.originId == media.originId && it.mediaId == media.mediaId }
            .maxByOrNull { it.updatedAt }
        return ContinueReadingItem(
            media = media,
            progress = latest?.progress ?: 0f,
            unitLabel = if (latest != null) "${(latest.progress * 100).toInt()}% complete" else "Not started",
        )
    }

    /** Consecutive days with activity, counting back from today; breaks the moment a day is missing. */
    private fun currentStreak(daysWithActivity: Set<String>): Int {
        val cal = Calendar.getInstance()
        var streak = 0
        while (daysWithActivity.contains(DAY_FORMAT.format(cal.time))) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private fun lastSevenDays(daysWithActivity: Set<String>): List<ActivityDay> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        return (0..6).map { offset ->
            val key = DAY_FORMAT.format(cal.time)
            val day = ActivityDay(
                label = WEEKDAY_FORMAT.format(cal.time),
                hasActivity = daysWithActivity.contains(key),
                isToday = offset == 6,
            )
            cal.add(Calendar.DAY_OF_YEAR, 1)
            day
        }
    }
}
