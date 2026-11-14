package dev.moku.mobile.ui.search

import dev.moku.mobile.backend.BackendMedia
import dev.moku.mobile.backend.MediaContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDedupeTest {
    private fun media(id: String, origin: String, title: String, description: String? = null, inLibrary: Boolean = false) =
        BackendMedia(id, origin, MediaContentType.MANGA, title, null, description = description, inLibrary = inLibrary)

    @Test
    fun sameTitleFromTwoSourcesShowsOnce() {
        val out = dedupeMedia(listOf(media("1", "a", "The Apothecary Diaries"), media("9", "b", "Apothecary Diaries (Official)")))
        assertEquals(listOf("a"), out.map { it.originId })
    }

    @Test
    fun libraryCopyWins() {
        val out = dedupeMedia(listOf(media("1", "a", "Frieren"), media("2", "b", "Frieren", inLibrary = true)))
        assertEquals(listOf("b"), out.map { it.originId })
    }

    @Test
    fun matchesOnDescriptionWhenTitlesDiffer() {
        val desc = "An elf mage who outlived her party sets out to understand the people she travelled with for a decade."
        val out = dedupeMedia(listOf(media("1", "a", "Sousou no Frieren", desc), media("2", "b", "Frieren: Beyond Journey's End", desc)))
        assertEquals(1, out.size)
    }

    @Test
    fun differentSeriesStay() {
        assertEquals(2, dedupeMedia(listOf(media("1", "a", "Berserk"), media("2", "b", "Vagabond"))).size)
    }

    @Test
    fun interleaveTakesOneFromEachInTurn() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
    }
}
