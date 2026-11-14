package dev.moku.mobile.ui.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.moku.mobile.backend.BackendUnit
import dev.moku.mobile.backend.BackendUnitContent
import dev.moku.mobile.library.ReadingProgressEntity

internal data class LoadedUnit(
    val unitId: String,
    val content: BackendUnitContent,
    val offline: Boolean,
    val progress: ReadingProgressEntity?,
)

internal class Chunk<T>(val unitId: String, val items: List<T>, val offline: Boolean)

internal sealed interface StripEntry {
    val chunk: Int

    data class Header(override val chunk: Int) : StripEntry
    data class Item(override val chunk: Int, val local: Int) : StripEntry
}

// Chapters stitched into one scrolling list, the way desktop's longstrip and novel reader work.
@Stable
internal class StripState<T>(
    initial: Chunk<T>,
    private val convert: (BackendUnitContent) -> List<T>?,
) {
    val chunks = mutableStateListOf(initial)
    var appending by mutableStateOf(false)
        private set
    var prepending by mutableStateOf(false)
        private set
    var atEnd by mutableStateOf(false)
        private set
    var atStart by mutableStateOf(false)
        private set

    fun entries(headers: Boolean): List<StripEntry> = buildList {
        chunks.forEachIndexed { c, chunk ->
            if (headers || c > 0) add(StripEntry.Header(c))
            chunk.items.indices.forEach { add(StripEntry.Item(c, it)) }
        }
    }

    suspend fun append(units: List<BackendUnit>, load: suspend (String) -> LoadedUnit?) {
        if (appending || atEnd || units.isEmpty()) return
        val last = chunks.last()
        val next = neighbours(units, units.firstOrNull { it.id == last.unitId }).second
        if (next == null) {
            atEnd = true
            return
        }
        appending = true
        try {
            val loaded = load(next.id)
            val items = loaded?.let { convert(it.content) }
            if (loaded != null && items != null && chunks.none { it.unitId == next.id }) {
                chunks.add(Chunk(next.id, items, loaded.offline))
            }
        } finally {
            appending = false
        }
    }

    suspend fun prepend(units: List<BackendUnit>, load: suspend (String) -> LoadedUnit?) {
        if (prepending || atStart || units.isEmpty()) return
        val first = chunks.first()
        val prev = neighbours(units, units.firstOrNull { it.id == first.unitId }).first
        if (prev == null) {
            atStart = true
            return
        }
        prepending = true
        try {
            val loaded = load(prev.id)
            val items = loaded?.let { convert(it.content) }
            if (loaded != null && items != null && chunks.none { it.unitId == prev.id }) {
                chunks.add(0, Chunk(prev.id, items, loaded.offline))
            }
        } finally {
            prepending = false
        }
    }
}
