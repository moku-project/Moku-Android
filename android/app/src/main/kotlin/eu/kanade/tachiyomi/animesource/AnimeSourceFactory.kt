// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), licensed under Apache License 2.0.

package eu.kanade.tachiyomi.animesource

@Suppress("unused")
interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}
