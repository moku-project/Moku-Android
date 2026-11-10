// Adapted from Aniyomi (https://github.com/aniyomiorg/aniyomi), licensed under Apache License 2.0.

package eu.kanade.tachiyomi.animesource

import androidx.preference.PreferenceScreen

@Suppress("unused")
interface ConfigurableAnimeSource {

    fun setupPreferenceScreen(screen: PreferenceScreen)

}
