package dev.moku.mobile.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.moku.mobile.backend.BackendMedia

enum class FilterField { GENRE, TAG, TITLE, DESCRIPTION }
enum class ContentBlockLevel { MODERATE, STRICT }

data class ContentFilterRule(
    val id: Long = 0,
    val category: String,
    val field: FilterField,
    val keyword: String,
    val minWeight: Int = 0,
    val blockLevel: ContentBlockLevel,
    val isDefault: Boolean = false,
)

/** Local-mode equivalent of Tsunagu's `contentFilterRules`/`addContentFilterRule`/etc — same rule shape, own table. */
class ContentFilterDao(private val db: LocalLibraryDatabase) {
    suspend fun add(rule: ContentFilterRule): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.writableDatabase.insert(
            "content_filter_rules", null,
            ContentValues().apply {
                put("category", rule.category)
                put("field", rule.field.name)
                put("keyword", rule.keyword)
                put("minWeight", rule.minWeight)
                put("blockLevel", rule.blockLevel.ordinal + 1)
                put("isDefault", if (rule.isDefault) 1 else 0)
            },
        )
    }

    suspend fun remove(id: Long) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.writableDatabase.delete("content_filter_rules", "id = ?", arrayOf(id.toString()))
        Unit
    }

    suspend fun reset() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.writableDatabase.delete("content_filter_rules", "isDefault = 0", null)
        Unit
    }

    suspend fun getAll(): List<ContentFilterRule> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.readableDatabase.query("content_filter_rules", null, null, null, null, null, "id ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ContentFilterRule(
                            id = c.getLongOrNull("id")!!,
                            category = c.getStringOrNull("category")!!,
                            field = FilterField.valueOf(c.getStringOrNull("field")!!),
                            keyword = c.getStringOrNull("keyword")!!,
                            minWeight = c.getLongOrNull("minWeight")?.toInt() ?: 0,
                            blockLevel = ContentBlockLevel.entries[c.getLongOrNull("blockLevel")!!.toInt() - 1],
                            isDefault = c.getLongOrNull("isDefault") == 1L,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Kotlin port of `internal/contentfilter/matcher.go`'s keyword matching — genre/title/
 * description only (BackendMedia carries no per-tag weight metadata the way Tsunagu's
 * AniList-backfilled `TagFacet` does, so tag-weight rules are accepted but never match
 * locally; a real gap versus the server, not a silent approximation — flagged here rather
 * than pretended away).
 */
object ContentFilterMatcher {
    private val alias = mapOf(
        "mature" to "adult", "18+" to "adult", "r-18" to "adult", "r18" to "adult",
        "explicit" to "pornographic", "h" to "hentai", "doujinshi (18+)" to "hentai",
        "guro" to "gore", "splatter" to "gore", "grotesque" to "gore",
    )

    private fun norm(s: String) = s.lowercase().trim()

    private fun expand(terms: List<String>): List<String> = terms.flatMap { raw ->
        val t = norm(raw)
        if (t.isEmpty()) emptyList() else listOfNotNull(t, alias[t])
    }

    /** 0 = not blocked; otherwise the ContentBlockLevel ordinal+1 it was blocked at (lower = stricter tripped first). */
    fun blockRank(media: BackendMedia, rules: List<ContentFilterRule>): Int {
        if (rules.isEmpty()) return 0
        var best = 0
        fun take(level: Int) {
            if (level != 0 && (best == 0 || level < best)) best = level
        }

        val titleRules = rules.filter { it.field == FilterField.TITLE }
        if (titleRules.isNotEmpty()) {
            val tl = norm(media.title)
            titleRules.forEach { r -> if (tl.contains(r.keyword)) take(r.blockLevel.ordinal + 1) }
        }
        val descRules = rules.filter { it.field == FilterField.DESCRIPTION }
        if (descRules.isNotEmpty() && !media.description.isNullOrEmpty()) {
            val dl = norm(media.description)
            descRules.forEach { r -> if (dl.contains(r.keyword)) take(r.blockLevel.ordinal + 1) }
        }
        val genreRules = rules.filter { it.field == FilterField.GENRE }
        if (genreRules.isNotEmpty()) {
            val genres = expand(media.genres)
            genreRules.forEach { r -> if (genres.any { it == r.keyword || it.contains(r.keyword) }) take(r.blockLevel.ordinal + 1) }
        }
        // FilterField.TAG rules exist in the schema but never match locally — see class kdoc.
        return best
    }

    /** [level]: 0=unrestricted (nothing filtered), 1=moderate, 2=strict. */
    fun isHidden(blockRank: Int, level: Int): Boolean = blockRank != 0 && blockRank <= level
}
