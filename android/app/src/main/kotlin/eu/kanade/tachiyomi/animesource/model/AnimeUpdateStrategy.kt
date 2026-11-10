// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), licensed under Apache License 2.0.

package eu.kanade.tachiyomi.animesource.model

enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

enum class FetchType {
    Seasons,
    Episodes,
}
