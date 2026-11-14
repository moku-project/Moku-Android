package dev.moku.mobile.ui.home

import android.content.Context
import android.util.Log
import dev.moku.mobile.library.LibraryMediaEntity
import dev.moku.mobile.library.Libraries
import dev.moku.mobile.library.ReadingProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class ContinueReadingItem(val media: LibraryMediaEntity, val progress: Float, val unitLabel: String)

const val HEATMAP_WEEKS = 26

data class HomeState(
    val loading: Boolean = true,
    val error: String? = null,
    val continueReading: List<ContinueReadingItem> = emptyList(),
    val streakDays: Int = 0,
    val totalChapters: Int = 0,
    val readTimeSeconds: Long = 0,
    val chaptersThisWeek: Int = 0,
    val seriesStarted: Int = 0,
    // Week-major, Sunday first, ending with the current week; -1 marks days after today.
    val heatmap: List<Int> = emptyList(),
    val upNext: ContinueReadingItem? = null,
)

object HomeDataLoader {
    private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

    suspend fun load(context: Context): HomeState = withContext(Dispatchers.IO) {
        try {
            val repo = Libraries.active(context)
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
                // The most recent title is already the Continue reading card.
                .drop(1)
                .firstOrNull { media -> repo.nextUnread(media.originId, media.mediaId) != null }
            val upNext = upNextMedia?.let { toContinueReadingItem(it, allProgress) }

            HomeState(
                loading = false,
                continueReading = continueReading,
                streakDays = currentStreak(distinctDays),
                totalChapters = completed.size,
                readTimeSeconds = readTimeSeconds,
                chaptersThisWeek = thisWeekCount,
                seriesStarted = library.count { it.lastViewedAt != null },
                heatmap = heatmap(allProgress.groupingBy { DAY_FORMAT.format(it.updatedAt) }.eachCount()),
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
    private fun currentStreak(daysWithActivity: Set<String>): Int {
        val cal = Calendar.getInstance()
        var streak = 0
        while (daysWithActivity.contains(DAY_FORMAT.format(cal.time))) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    private fun heatmap(countsByDay: Map<String, Int>): List<Int> {
        val today = DAY_FORMAT.format(Calendar.getInstance().time)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))
        cal.add(Calendar.WEEK_OF_YEAR, -(HEATMAP_WEEKS - 1))
        var future = false
        return List(HEATMAP_WEEKS * 7) {
            val key = DAY_FORMAT.format(cal.time)
            val count = if (future) -1 else countsByDay[key] ?: 0
            if (key == today) future = true
            cal.add(Calendar.DAY_OF_YEAR, 1)
            count
        }
    }
}
