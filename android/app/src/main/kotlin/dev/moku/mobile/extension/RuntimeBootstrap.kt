package dev.moku.mobile.extension

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.getOrNull

/**
 * Registers the Injekt singletons any loaded extension (manga or anime) may resolve via
 * `by injectLazy()` — shared across every backend/loader call site so it's only wired up
 * once. `Application` matters because some extensions (e.g. Aniyomi's
 * GoogleDrive) store their own SharedPreferences via `Injekt.get<Application>()` rather than
 * taking a Context parameter — a real host app registers this the same way Mihon/Aniyomi's
 * own app class does at startup.
 */
object RuntimeBootstrap {
    fun ensure(context: Context) {
        val app = context.applicationContext as Application
        if (Injekt.getOrNull<Application>() == null) {
            Injekt.addSingletonFactory { app }
        }
        if (Injekt.getOrNull<NetworkHelper>() == null) {
            Injekt.addSingletonFactory { NetworkHelper(app) }
        }
        if (Injekt.getOrNull<Json>() == null) {
            Injekt.addSingletonFactory { Json { ignoreUnknownKeys = true } }
        }
    }
}
